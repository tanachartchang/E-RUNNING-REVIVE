package com.bkpsoft.erunning.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Makes the phone look like standard Bluetooth fitness sensors so a watch or fitness app can pick up live data:
 *  - FTMS treadmill (0x1826): speed, incline, distance, energy, heart rate, time
 *  - Running Speed and Cadence foot pod (0x1814): speed, cadence, stride, distance
 *  - Cycling Speed and Cadence sensor (0x1816): speed as wheel revolutions, step cadence as crank revolutions
 * Watches that only accept sensors (e.g. Huawei Watch GT) can use the last two; they have no incline field.
 * FTMS control point requests are acknowledged but not acted on.
 */
@SuppressLint("MissingPermission")
class FtmsServer(
    private val context: Context,
    private val onState: (message: String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    /** Values in FTMS units. */
    data class Data(
        val speed: Int,       // 0.01 km/h
        val incline: Int,     // 0.1 %
        val distance: Int,    // m
        val kcal: Int,
        val heartRate: Int,   // 0 = unknown
        val time: Int,        // s
        val steps: Int,
    )

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var server: BluetoothGattServer? = null
    private val subscribers = mutableSetOf<BluetoothDevice>()
    private val connected = mutableSetOf<BluetoothDevice>()
    private var data = Data(0, 0, 0, 0, 0, 0, 0)
    private val subs = mutableMapOf<UUID, MutableSet<BluetoothDevice>>()
    private val stepSamples = ArrayDeque<Pair<Long, Int>>()
    private var wheelRevs = 0.0
    private var crankRevs = 0.0
    private var lastTick = 0L
    private var speedRange = byteArrayOf()
    private var inclineRange = byteArrayOf()
    private var running = false

    private val treadmillData = BluetoothGattCharacteristic(TREADMILL_DATA,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply { addDescriptor(cccd()) }
    private val controlPoint = BluetoothGattCharacteristic(CONTROL_POINT,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
        BluetoothGattCharacteristic.PERMISSION_WRITE).apply { addDescriptor(cccd()) }
    private val rscMeasurement = BluetoothGattCharacteristic(RSC_MEASUREMENT,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply { addDescriptor(cccd()) }
    private val cscMeasurement = BluetoothGattCharacteristic(CSC_MEASUREMENT,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply { addDescriptor(cccd()) }

    fun start(minSpeed: Int, maxSpeed: Int, minIncline: Int, maxIncline: Int) {
        if (running) return
        val adapter = manager.adapter ?: return onState("Bluetooth is off")
        val advertiser = adapter.bluetoothLeAdvertiser ?: return onState("This phone cannot advertise over Bluetooth LE")
        speedRange = u16(minSpeed) + u16(maxSpeed) + u16(10)
        inclineRange = u16(minIncline) + u16(maxIncline) + u16(10)

        val s = server ?: manager.openGattServer(context, callback)?.also { server = it }
            ?: return onState("Could not open a GATT server")
        val svc = BluetoothGattService(FTMS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(BluetoothGattCharacteristic(FEATURE, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        svc.addCharacteristic(treadmillData)
        svc.addCharacteristic(BluetoothGattCharacteristic(SPEED_RANGE, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        svc.addCharacteristic(BluetoothGattCharacteristic(INCLINE_RANGE, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        svc.addCharacteristic(controlPoint)
        val rsc = BluetoothGattService(RSC_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        rsc.addCharacteristic(rscMeasurement)
        rsc.addCharacteristic(BluetoothGattCharacteristic(RSC_FEATURE, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        rsc.addCharacteristic(BluetoothGattCharacteristic(SENSOR_LOCATION, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        val csc = BluetoothGattService(CSC_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        csc.addCharacteristic(cscMeasurement)
        csc.addCharacteristic(BluetoothGattCharacteristic(CSC_FEATURE, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        // Real sensors also expose battery level and device information; some watches read them before subscribing.
        val battery = BluetoothGattService(BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        battery.addCharacteristic(BluetoothGattCharacteristic(BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ).apply { addDescriptor(cccd()) })
        val dis = BluetoothGattService(DIS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        DIS_VALUES.keys.forEach { dis.addCharacteristic(BluetoothGattCharacteristic(it, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)) }
        s.clearServices()
        // addService is asynchronous: add the next one from onServiceAdded
        pendingServices.clear(); pendingServices.addAll(listOf(rsc, csc, battery, dis))
        s.addService(svc)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true).build()
        // Sensor scanners (watches) often only list devices whose name is in the main packet, so the name goes
        // there with the two sensor services; FTMS (+ service data: machine available, treadmill) goes in the
        // scan response. The GATT server offers all three either way.
        val adv = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(RSC_SERVICE))
            .addServiceUuid(ParcelUuid(CSC_SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(FTMS_SERVICE))
            .addServiceData(ParcelUuid(FTMS_SERVICE), byteArrayOf(0x01, 0x01, 0x00))
            .build()
        running = true
        lastTick = 0L; wheelRevs = 0.0; crankRevs = 0.0; stepSamples.clear()
        advertiser.startAdvertising(settings, adv, scanResponse, advertiseCb)
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1000)
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        runCatching { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCb) }
        connected.forEach { runCatching { server?.cancelConnection(it) } }
        server?.close(); server = null
        subscribers.clear(); connected.clear(); subs.clear()
        onState("Off")
    }

    fun update(d: Data) { data = d }

    private val advertiseCb = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            main.post { onState("Broadcasting as \"${manager.adapter?.name ?: "phone"}\", waiting for watch…") }
        }
        override fun onStartFailure(errorCode: Int) {
            main.post { stop(); onState("Advertising failed (error $errorCode)") }
        }
    }

    private val pendingServices = ArrayDeque<BluetoothGattService>()

    /** Steps per minute over the last 10 s. */
    private fun cadence(now: Long): Int {
        stepSamples.addLast(now to data.steps)
        while (stepSamples.size > 1 && now - stepSamples.first().first > 10_000) stepSamples.removeFirst()
        val (t0, s0) = stepSamples.first()
        if (data.speed == 0 || now - t0 < 2000 || data.steps < s0) return 0
        return ((data.steps - s0) * 60_000L / (now - t0)).toInt().coerceIn(0, 254)
    }

    /** Sensors notify about once a second. */
    private val tick = object : Runnable {
        override fun run() {
            val s = server ?: return
            val now = System.currentTimeMillis()
            val dt = if (lastTick == 0L) 0.0 else (now - lastTick) / 1000.0
            lastTick = now
            val cad = cadence(now)
            val mps = data.speed / 360.0 // 0.01 km/h -> m/s
            wheelRevs += mps * dt / WHEEL_M
            crankRevs += cad / 2.0 / 60.0 * dt // one crank revolution per two steps
            if (subscribers.isNotEmpty()) {
                val bytes = treadmillDataBytes(data)
                subscribers.forEach { notify(s, it, treadmillData, bytes, false) }
            }
            subs[RSC_MEASUREMENT]?.takeIf { it.isNotEmpty() }?.let { set ->
                val bytes = rscBytes(data.speed * 256 / 360, cad, data.distance)
                set.forEach { notify(s, it, rscMeasurement, bytes, false) }
            }
            subs[CSC_MEASUREMENT]?.takeIf { it.isNotEmpty() }?.let { set ->
                val bytes = cscBytes(wheelRevs, mps / WHEEL_M, crankRevs, cad / 120.0, now)
                set.forEach { notify(s, it, cscMeasurement, bytes, false) }
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun notify(s: BluetoothGattServer, d: BluetoothDevice, c: BluetoothGattCharacteristic, bytes: ByteArray, confirm: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(d, c, confirm, bytes)
        else @Suppress("DEPRECATION") run { c.value = bytes; s.notifyCharacteristicChanged(d, c, confirm) }
    }

    /** Only counts devices reading our data: every LE link (e.g. the treadmill's) also shows up as a server connection. */
    private fun status() {
        val readers = (subscribers + subs.values.flatten()).filter { it in connected }.toSet()
        onState(if (readers.isEmpty()) "Broadcasting as \"${manager.adapter?.name ?: "phone"}\", waiting for watch…"
            else "${readers.joinToString { it.name ?: it.address }} connected · sending data")
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { main.post { onLog("FTMS: MTU $mtu") } }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post { pendingServices.removeFirstOrNull()?.let { server?.addService(it) } }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) { connected.add(device); onLog("FTMS: ${device.address} connected") }
                else { connected.remove(device); subscribers.remove(device); subs.values.forEach { it.remove(device) }; onLog("FTMS: ${device.address} disconnected") }
                if (running) status()
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            val value = when (c.uuid) {
                FEATURE -> FEATURES
                SPEED_RANGE -> speedRange
                INCLINE_RANGE -> inclineRange
                RSC_FEATURE -> u16(0b111)       // stride length, total distance, walking/running status
                CSC_FEATURE -> u16(0b11)        // wheel and crank revolution data
                SENSOR_LOCATION -> byteArrayOf(7) // in shoe
                BATTERY_LEVEL -> byteArrayOf(100)
                else -> DIS_VALUES[c.uuid]?.toByteArray()
            }
            main.post { onLog("FTMS: read ${short(c.uuid)}") }
            server?.sendResponse(device, requestId,
                if (value != null) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_READ_NOT_PERMITTED,
                offset, value?.drop(offset)?.toByteArray())
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (c.uuid != CONTROL_POINT) main.post { onLog("FTMS: write ${short(c.uuid)} ${Protocol.hex(value)}") }
            if (c.uuid != CONTROL_POINT || value.isEmpty()) return
            main.post { onLog("FTMS: control ${Protocol.hex(value)}") }
            // Response Code (0x80), request op code, result 0x01 = success
            server?.let { notify(it, device, controlPoint, byteArrayOf(0x80.toByte(), value[0], 0x01), true) }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            val u = d.characteristic.uuid
            main.post { onLog("FTMS: read CCCD of ${short(u)}") }
            val on = (u == TREADMILL_DATA && device in subscribers) || subs[u]?.contains(device) == true
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val u = d.characteristic.uuid
            val on = value.isNotEmpty() && value[0].toInt() != 0
            main.post {
                val set = if (u == TREADMILL_DATA) subscribers else subs.getOrPut(u) { mutableSetOf() }
                if (on) set.add(device) else set.remove(device)
                onLog("FTMS: ${short(u)} ${if (on) "subscribed" else "unsubscribed"} by ${device.name ?: device.address}")
                if (running) status()
            }
        }
    }

    companion object {
        private fun uuid16(s: String): UUID = UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb")
        val FTMS_SERVICE = uuid16("1826")
        val FEATURE = uuid16("2acc")
        val TREADMILL_DATA = uuid16("2acd")
        val SPEED_RANGE = uuid16("2ad4")
        val INCLINE_RANGE = uuid16("2ad5")
        val CONTROL_POINT = uuid16("2ad9")
        val RSC_SERVICE = uuid16("1814")
        val RSC_MEASUREMENT = uuid16("2a53")
        val RSC_FEATURE = uuid16("2a54")
        val SENSOR_LOCATION = uuid16("2a5d")
        val CSC_SERVICE = uuid16("1816")
        val CSC_MEASUREMENT = uuid16("2a5b")
        val CSC_FEATURE = uuid16("2a5c")
        /** 700x25c, the usual default wheel size; set the same on the watch if it asks. */
        val BATTERY_SERVICE = uuid16("180f")
        val BATTERY_LEVEL = uuid16("2a19")
        val DIS_SERVICE = uuid16("180a")
        private val DIS_VALUES = mapOf(
            uuid16("2a29") to "E-Running",  // manufacturer
            uuid16("2a24") to "Treadmill",  // model number
            uuid16("2a25") to "0001",       // serial number
            uuid16("2a26") to "1.0",        // firmware revision
            uuid16("2a27") to "1.0",        // hardware revision
        )
        const val WHEEL_M = 2.105
        private val CCCD = BleLink.CCCD
        private fun short(u: UUID) = u.toString().substring(4, 8)
        private fun cccd() = BluetoothGattDescriptor(CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)

        // Machine features: total distance (2), inclination (3), expended energy (9), heart rate (10), elapsed time (12).
        // Target setting features: none.
        private val FEATURES = u32((1 shl 2) or (1 shl 3) or (1 shl 9) or (1 shl 10) or (1 shl 12)) + u32(0)

        private fun u16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
        private fun u24(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte())
        private fun u32(v: Int) = u16(v) + u16(v shr 16)

        /** RSC Measurement (0x2A53): [speed] in 1/256 m/s, [cadence] steps/min, [distance] m. */
        fun rscBytes(speed: Int, cadence: Int, distance: Int): ByteArray {
            val stride = if (cadence > 0) speed * 100 * 60 / 256 / cadence else 0 // cm
            val flags = 0b011 or (if (speed > 2 * 256) 0b100 else 0) // stride + distance, running above 2 m/s
            return byteArrayOf(flags.toByte()) + u16(speed) + byteArrayOf(cadence.toByte()) + u16(stride) + u32(distance * 10)
        }

        /**
         * CSC Measurement (0x2A5B). Event times (1/1024 s) are back-dated to the moment the last whole
         * revolution happened, so the receiver computes a smooth speed from revs / time.
         */
        fun cscBytes(wheel: Double, wheelRate: Double, crank: Double, crankRate: Double, nowMs: Long): ByteArray {
            fun eventTime(revs: Double, rate: Double): Int {
                val back = if (rate > 0) (revs - Math.floor(revs)) / rate else 0.0
                return ((nowMs / 1000.0 - back) * 1024).toLong().and(0xffff).toInt()
            }
            return byteArrayOf(0b11) + u32(wheel.toLong().toInt()) + u16(eventTime(wheel, wheelRate)) +
                u16(crank.toLong().toInt()) + u16(eventTime(crank, crankRate))
        }

        /** Treadmill Data (0x2ACD): speed is always present (flag bit 0 = 0). */
        fun treadmillDataBytes(d: Data): ByteArray {
            var flags = (1 shl 2) or (1 shl 3) or (1 shl 7) or (1 shl 10) // distance, incline, energy, elapsed time
            if (d.heartRate > 0) flags = flags or (1 shl 8)
            var out = u16(flags) + u16(d.speed) + u24(d.distance) +
                u16(d.incline) + u16(0) +                       // inclination, ramp angle
                u16(d.kcal) + u16(0xffff) + byteArrayOf(0xff.toByte()) // total, per hour/minute not available
            if (d.heartRate > 0) out += byteArrayOf(d.heartRate.coerceAtMost(255).toByte())
            out += u16(d.time)
            return out
        }
    }
}
