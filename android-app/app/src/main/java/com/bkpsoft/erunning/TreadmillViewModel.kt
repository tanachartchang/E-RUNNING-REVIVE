package com.bkpsoft.erunning

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.bkpsoft.erunning.ble.BleLink
import com.bkpsoft.erunning.ble.FtmsServer
import com.bkpsoft.erunning.ble.HeartRateLink
import com.bkpsoft.erunning.ble.Link
import com.bkpsoft.erunning.ble.LinkListener
import com.bkpsoft.erunning.ble.Protocol
import com.bkpsoft.erunning.ble.Protocol.Msg
import com.bkpsoft.erunning.ble.Protocol.State
import com.bkpsoft.erunning.ble.SimulatorLink
import com.bkpsoft.erunning.data.Settings
import com.bkpsoft.erunning.data.Store
import com.bkpsoft.erunning.data.Workout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TreadmillInfo(
    val modelName: String? = null, val model: Int? = null, val serial: Long? = null,
    val minSpeed: Int = 10, val maxSpeed: Int = 120, val unit: Int = 0, val speedKnown: Boolean = false,
    val minIncline: Int = 0, val maxIncline: Int = 0, val total: Long? = null,
)

data class FoundDevice(val address: String, val name: String, val rssi: Int)

enum class ScanTarget { TREADMILL, HEART_RATE }
enum class GoalType { NONE, TIME, DISTANCE, CALORIES }

sealed interface Conn {
    data object Disconnected : Conn
    data class Connecting(val name: String) : Conn
    data class Connected(val name: String) : Conn
}

@SuppressLint("MissingPermission")
class TreadmillViewModel(app: Application) : AndroidViewModel(app), LinkListener {
    private val store = Store(app)
    private val main = Handler(Looper.getMainLooper())
    private val bt get() = (getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    var settings by mutableStateOf(store.loadSettings()); private set
    var conn by mutableStateOf<Conn>(Conn.Disconnected); private set
    var info by mutableStateOf(TreadmillInfo()); private set
    var state by mutableStateOf<Int?>(null); private set
    var live by mutableStateOf<Protocol.Live?>(null); private set
    var countdown by mutableStateOf(0); private set
    var alert by mutableStateOf<Pair<String, String>?>(null)
    val log = mutableStateListOf<String>()
    val history = mutableStateListOf<Workout>().apply { addAll(store.loadHistory()) }

    var goalType by mutableStateOf(GoalType.NONE)
    var goalValue by mutableStateOf("")

    var hrStatus by mutableStateOf("Not connected"); private set
    var hrConnected by mutableStateOf(false); private set
    var ftmsStatus by mutableStateOf("Off"); private set
    var ftmsOn by mutableStateOf(false); private set
    private var ftms: FtmsServer? = null

    private var watchBpm by mutableStateOf(0)
    private var watchAt by mutableStateOf(0L)

    // speed/incline the user just asked for; shown until the treadmill catches up
    var cmdSpeed by mutableStateOf(0); private set
    var cmdIncline by mutableStateOf(0); private set
    var cmdAt by mutableStateOf(0L); private set

    val found = mutableStateListOf<FoundDevice>()
    var scanning by mutableStateOf<ScanTarget?>(null); private set

    private var link: Link? = null
    private var hrLink: HeartRateLink? = null
    private class Pending(val data: IntArray, var tries: Int = 0)
    private val queue = ArrayDeque<Pending>()
    private val rx = ArrayList<Byte>()

    private class Session(val start: Long) {
        var last: Protocol.Live? = null
        var summary: Msg.Summary? = null
        var summaryAsked = false
        var peakSpeed = 0
        var hrSum = 0L
        var hrN = 0
    }
    private var session: Session? = null
    private var goalStopSent = false

    // ---------------- scanning ----------------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.device.name ?: r.scanRecord?.deviceName ?: return
            val i = found.indexOfFirst { it.address == r.device.address }
            val d = FoundDevice(r.device.address, name, r.rssi)
            if (i >= 0) found[i] = d else found.add(d)
            found.sortByDescending { it.rssi }
        }
        override fun onScanFailed(errorCode: Int) { scanning = null; alert = "Scan failed" to "Bluetooth error $errorCode. Try turning Bluetooth off and on." }
    }

    fun startScan(target: ScanTarget) {
        val scanner = bt?.bluetoothLeScanner ?: run { alert = "Bluetooth is off" to "Turn on Bluetooth and try again."; return }
        stopScan()
        found.clear()
        scanning = target
        val filters = if (target == ScanTarget.HEART_RATE)
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HeartRateLink.HR_SERVICE)).build()) else emptyList()
        scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        main.postDelayed(stopScanRunnable, 15_000)
    }

    private val stopScanRunnable = Runnable { stopScan() }
    fun stopScan() {
        main.removeCallbacks(stopScanRunnable)
        if (scanning != null) runCatching { bt?.bluetoothLeScanner?.stopScan(scanCb) }
        scanning = null
    }

    // ---------------- treadmill connection ----------------
    fun connect(address: String) {
        stopScan()
        disconnect()
        val device = bt?.getRemoteDevice(address) ?: return
        val l = BleLink(getApplication(), device, BleLink.parseUuid(settings.customService), this)
        link = l
        conn = Conn.Connecting(l.name)
        l.connect()
        updateSettings(settings.copy(lastDevice = address))
    }

    fun startDemo() {
        disconnect()
        val l = SimulatorLink(this)
        link = l
        conn = Conn.Connecting(l.name)
        l.start()
    }

    fun disconnect() {
        val l = link ?: return
        link = null
        l.close()
        onClosed()
    }

    override fun onLinkReady(link: Link) {
        if (link !== this.link) return
        conn = Conn.Connected(link.name)
        info = TreadmillInfo()
        state = null
        alert = null
        queue.clear()
        if (settings.weightKg > 0) send(Protocol.user(0, settings.weightKg.coerceAtMost(255)))
        requestInfo()
        main.removeCallbacks(poll)
        main.postDelayed(poll, 200)
    }

    override fun onLinkClosed(reason: String?) {
        link = null
        onClosed()
        if (reason != null) alert = "Disconnected" to reason
    }

    private fun onClosed() {
        main.removeCallbacks(poll)
        queue.clear()
        if (session != null) finishSession()
        conn = Conn.Disconnected
        state = null
        countdown = 0
    }

    override fun onLinkLog(text: String) = addLog("i", text)

    fun requestInfo() {
        send(Protocol.infoModel())
        send(Protocol.infoSpeed())
        send(Protocol.infoIncline())
        send(Protocol.infoTotal())
    }

    private fun send(p: IntArray) { if (link != null) queue.addLast(Pending(p)) }

    /** Same loop as the original app: next queued command (max 3 tries) or a status poll, every 200 ms. */
    private val poll = object : Runnable {
        override fun run() {
            val l = link ?: return
            if (!l.busy) {
                while (queue.isNotEmpty() && queue.first().tries >= 3) queue.removeFirst()
                val p = queue.firstOrNull()?.also { it.tries++ }?.data ?: Protocol.status()
                val bytes = Protocol.frame(p)
                l.write(bytes)
                if (p[0] != Protocol.STATUS) addLog(">", Protocol.hex(bytes))
            }
            main.postDelayed(this, 200)
        }
    }

    override fun onLinkBytes(bytes: ByteArray) {
        // One notification is normally one frame; reassemble in case a module splits it.
        if (rx.isEmpty() && bytes.firstOrNull()?.toInt() != 0x02) return
        bytes.forEach { rx.add(it) }
        if (rx.size > 64) { rx.clear(); return }
        val frame = rx.toByteArray()
        val p = Protocol.unframe(frame) ?: return
        rx.clear()
        if (p[0] != Protocol.STATUS) {
            addLog("<", Protocol.hex(frame))
            if (queue.isNotEmpty() && queue.first().data[0] == p[0]) queue.removeFirst()
        }
        onMessage(Protocol.parse(p))
    }

    private fun onMessage(m: Msg) {
        when (m) {
            is Msg.Model -> info = info.copy(modelName = m.name, model = m.model, serial = m.serial)
            is Msg.SpeedRange -> info = info.copy(maxSpeed = m.max, minSpeed = m.min, unit = m.unit, speedKnown = true)
            is Msg.InclineRange -> info = info.copy(maxIncline = m.max, minIncline = m.min)
            is Msg.Total -> info = info.copy(total = m.total)
            is Msg.Summary -> session?.summary = m
            is Msg.Status -> onStatus(m)
            is Msg.Other -> Unit
        }
    }

    private fun onStatus(m: Msg.Status) {
        state = m.state
        m.live?.let { live = it }
        pushFtms()
        countdown = if (m.state == State.START) m.countdown else 0

        if ((m.state == State.START || m.state == State.RUNNING) && session == null) {
            session = Session(System.currentTimeMillis())
            goalStopSent = false
            live = null
            alert = null
        }
        val s = session
        if (s != null && m.live != null) {
            s.last = m.live
            s.peakSpeed = maxOf(s.peakSpeed, m.live.speed)
            val bpm = heartRate()
            if (bpm > 0 && m.state == State.RUNNING) { s.hrSum += bpm; s.hrN++ }
            if (m.state == State.RUNNING && !goalStopSent && goalProgress() >= 1f) { goalStopSent = true; send(Protocol.stop()) }
        }
        if (s != null && (m.state == State.END || m.state == State.STOPPING) && !s.summaryAsked) {
            s.summaryAsked = true
            send(Protocol.sportSummary())
        }
        if (m.state == State.ERROR) alert = "Error E%02d".format(m.error) to (Protocol.errors[m.error] ?: "Treadmill fault")
        if (m.state == State.SAFETY) alert = "E00" to "Safety switch is off. Put the safety key back on the treadmill."
        if (s != null && (m.state == State.NORMAL || m.state == State.ERROR || m.state == State.SAFETY)) finishSession()
    }

    // ---------------- controls ----------------
    fun start() { alert = null; send(Protocol.start()) }
    fun stop() = send(Protocol.stop())

    private fun recentCmd() = System.currentTimeMillis() - cmdAt < 2500
    fun shownSpeed(): Int = if (state == State.RUNNING && recentCmd()) cmdSpeed else live?.speed ?: 0
    fun shownIncline(): Int = if (state == State.RUNNING && recentCmd()) cmdIncline else live?.incline ?: 0

    private fun replaceSpeedCommand(p: IntArray) {
        queue.removeAll { it.data[0] == Protocol.CONTROL && it.data[1] == 0x02 }
        send(p)
    }

    fun setSpeed(raw: Int) {
        if (state != State.RUNNING) return
        val v = raw.coerceIn(info.minSpeed, info.maxSpeed)
        cmdIncline = shownIncline(); cmdSpeed = v; cmdAt = System.currentTimeMillis()
        replaceSpeedCommand(Protocol.speed(v))
    }

    fun changeSpeed(delta: Int) = setSpeed(shownSpeed() + delta)

    fun setIncline(value: Int) {
        if (state != State.RUNNING || info.maxIncline <= 0) return
        val v = value.coerceIn(info.minIncline, info.maxIncline)
        cmdSpeed = shownSpeed(); cmdIncline = v; cmdAt = System.currentTimeMillis()
        replaceSpeedCommand(Protocol.speedIncline(cmdSpeed, v))
    }

    // ---------------- goal ----------------
    fun goalProgress(): Float {
        val v = goalValue.replace(',', '.').toFloatOrNull() ?: return 0f
        val l = live ?: return 0f
        if (v <= 0f) return 0f
        return when (goalType) {
            GoalType.NONE -> 0f
            GoalType.TIME -> l.time / (v * 60f)
            GoalType.DISTANCE -> (l.distance / (if (settings.miles) MI else 1000.0)).toFloat() / v
            GoalType.CALORIES -> l.calories / 10f / v
        }
    }

    fun setGoal(type: GoalType, value: String) { goalType = type; goalValue = value; goalStopSent = false }

    // ---------------- heart rate ----------------
    fun heartRate(): Int {
        if (watchBpm > 0 && System.currentTimeMillis() - watchAt < 5000) return watchBpm
        return live?.heartRate ?: 0
    }

    fun connectHeartRate(address: String) {
        stopScan()
        hrLink?.close()
        val device = bt?.getRemoteDevice(address) ?: return
        val l = HeartRateLink(getApplication(), device,
            onBpm = { watchBpm = it; watchAt = System.currentTimeMillis(); hrStatus = "${hrLink?.name ?: "Watch"} · $it bpm" },
            onState = { ok, msg -> hrConnected = ok; hrStatus = msg; if (!ok) { hrLink = null; watchBpm = 0 } },
        )
        hrLink = l
        hrStatus = "Connecting to ${l.name}…"
        l.connect()
    }

    fun disconnectHeartRate() {
        hrLink?.close(); hrLink = null; hrConnected = false; watchBpm = 0; hrStatus = "Not connected"
    }

    // ---------------- FTMS broadcast to watch ----------------
    fun startFtms() {
        if (ftms != null) return
        val i = info
        val s = FtmsServer(getApplication(), onState = { ftmsStatus = it; addLog("w", it) }, onLog = { addLog("w", it) })
        ftms = s
        ftmsOn = true
        ftmsStatus = "Starting…"
        s.start(toFtmsSpeed(i.minSpeed), toFtmsSpeed(i.maxSpeed), i.minIncline * 10, maxOf(i.maxIncline, 15) * 10)
        pushFtms()
    }

    fun stopFtms() { ftms?.stop(); ftms = null; ftmsOn = false; ftmsStatus = "Off" }

    /** Treadmill speed is 0.1 km/h (or 0.1 mph); FTMS wants 0.01 km/h. Incline levels are taken as percent. */
    private fun toFtmsSpeed(raw: Int) = if (info.unit == 1) (raw * 16.09344).toInt() else raw * 10

    private fun pushFtms() {
        val s = ftms ?: return
        val l = live
        val active = l != null && (state == State.RUNNING || state == State.STOPPING || state == State.END)
        s.update(FtmsServer.Data(
            speed = if (active && state == State.RUNNING) toFtmsSpeed(l!!.speed) else 0,
            incline = if (active) l!!.incline * 10 else 0,
            distance = if (active) l!!.distance else 0,
            kcal = if (active) l!!.calories / 10 else 0,
            heartRate = heartRate(),
            time = if (active) l!!.time else 0,
            steps = l?.steps ?: 0,
        ))
    }

    // ---------------- history ----------------
    private fun finishSession() {
        val s = session ?: return
        session = null
        val last = s.last ?: return
        val sum = s.summary
        val w = if (sum != null && sum.distance >= last.distance)
            Workout(s.start, sum.time, sum.distance, sum.calories / 10.0, sum.steps, s.peakSpeed / 10.0, avgHr(s), speedUnit())
        else Workout(s.start, last.time, last.distance, last.calories / 10.0, last.steps, s.peakSpeed / 10.0, avgHr(s), speedUnit())
        if (w.time < 30 && w.distance < 10) return
        history.add(w)
        store.saveHistory(history)
    }

    private fun avgHr(s: Session) = if (s.hrN > 0) (s.hrSum / s.hrN).toInt() else 0

    fun deleteWorkout(w: Workout) { history.remove(w); store.saveHistory(history) }
    fun clearHistory() { history.clear(); store.saveHistory(history) }
    fun historyCsv() = Store.toCsv(history)

    // ---------------- misc ----------------
    fun updateSettings(s: Settings) { settings = s; store.saveSettings(s) }
    fun speedUnit() = if (info.unit == 1) "mph" else "km/h"
    fun distUnit() = if (settings.miles) "mi" else "km"
    fun toDist(m: Int) = if (settings.miles) m / MI else m / 1000.0
    val isRunning get() = state == State.RUNNING || state == State.START || state == State.STOPPING

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun addLog(dir: String, text: String) {
        android.util.Log.i("erun", "$dir $text")
        log.add(0, "${timeFmt.format(Date())} $dir $text")
        if (log.size > 200) log.removeRange(200, log.size)
    }

    override fun onCleared() {
        stopScan()
        disconnect()
        disconnectHeartRate()
        stopFtms()
    }

    companion object { const val MI = 1609.344 }
}
