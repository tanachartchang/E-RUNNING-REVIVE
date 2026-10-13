package com.bkpsoft.erunning.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bkpsoft.erunning.Conn
import com.bkpsoft.erunning.FoundDevice
import com.bkpsoft.erunning.GoalType
import com.bkpsoft.erunning.ScanTarget
import com.bkpsoft.erunning.TreadmillViewModel
import com.bkpsoft.erunning.ble.Protocol
import com.bkpsoft.erunning.ble.Protocol.State
import java.text.DateFormat
import java.util.Date

private val Green = Color(0xFF1F8F4E)
private val GreenDark = Color(0xFF35C06F)

@Composable
fun App(vm: TreadmillViewModel, withBluetooth: (() -> Unit) -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = GreenDark, onPrimary = Color(0xFF08130C),
        secondaryContainer = Color(0xFF1F3A2A), onSecondaryContainer = Color(0xFFD5F5E1),
        background = Color(0xFF0F1115), surface = Color(0xFF0F1115), surfaceContainer = Color(0xFF181B21),
    ) else lightColorScheme(
        primary = Green, onPrimary = Color.White,
        secondaryContainer = Color(0xFFDDEFE3), onSecondaryContainer = Color(0xFF0B3A1E),
        background = Color(0xFFF7F8F7), surface = Color(0xFFF7F8F7), surfaceContainer = Color(0xFFFFFFFF),
    )
    MaterialTheme(colorScheme = colors) {
        var tab by rememberSaveable { mutableStateOf(0) }
        val view = LocalView.current
        view.keepScreenOn = vm.settings.keepScreenOn && vm.isRunning

        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf("Run", "History", "Device", "Settings").forEachIndexed { i, label ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, label = { Text(label) },
                            icon = { Text(listOf("🏃", "📊", "📶", "⚙️")[i], fontSize = 20.sp) })
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                TopBar(vm, withBluetooth)
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> RunScreen(vm)
                        1 -> HistoryScreen(vm)
                        2 -> DeviceScreen(vm, withBluetooth)
                        else -> SettingsScreen(vm)
                    }
                }
            }
        }

        if (vm.scanning != null) DevicePicker(vm)
        vm.alert?.let { (title, text) ->
            AlertDialog(onDismissRequest = { vm.alert = null }, confirmButton = { TextButton({ vm.alert = null }) { Text("OK") } },
                title = { Text(title) }, text = { Text(text) })
        }
        if (vm.countdown > 0) CountdownOverlay(vm.countdown)
    }
}

@Composable
private fun TopBar(vm: TreadmillViewModel, withBluetooth: (() -> Unit) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("E-Running", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                when (val c = vm.conn) { Conn.Disconnected -> "Not connected"; is Conn.Connecting -> "Connecting to ${c.name}…"; is Conn.Connected -> c.name },
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.conn is Conn.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (vm.conn == Conn.Disconnected) {
            Button({ withBluetooth { vm.startScan(ScanTarget.TREADMILL) } }) { Text("Connect") }
        } else {
            FilledTonalButton({ vm.disconnect() }) { Text("Disconnect") }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)

private fun hms(s: Int) = "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
private fun ms(s: Double) = if (s.isFinite() && s > 0 && s < 360000) "%d:%02d".format((s / 60).toInt(), (s % 60).toInt()) else "--:--"

// ---------------- RUN ----------------
@Composable
private fun RunScreen(vm: TreadmillViewModel) {
    val connected = vm.conn is Conn.Connected
    val running = vm.state == State.RUNNING
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        val hasIncline = vm.info.maxIncline > 0
        val canStart = connected && (vm.state == State.NORMAL || vm.state == State.END)
        val canStop = connected && (vm.state == State.RUNNING || vm.state == State.START)
        SectionCard {
            // Treadmill-console layout: incline left, status center, speed right; quick picks stacked high to low
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("Incline")
                    Value(if (hasIncline) vm.shownIncline().toString() else "–", "%")
                    if (hasIncline) PresetColumn(QUICK_PICKS, running) { vm.setIncline(it) }
                }
                Column(Modifier.weight(1.4f).padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val st = vm.state
                    Text(
                        (if (!connected) "Not connected" else if (st == null) "Waiting…" else Protocol.stateName[st] ?: "State $st").uppercase(),
                        textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        color = when (st) { State.RUNNING -> MaterialTheme.colorScheme.primary; State.ERROR, State.SAFETY -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                    )
                    Spacer(Modifier.height(8.dp))
                    val l = vm.live
                    val time = l?.time ?: 0
                    val distM = l?.distance ?: 0
                    val d = vm.toDist(distM)
                    val hr = vm.heartRate()
                    listOf(
                        Triple("Time", hms(time), ""),
                        Triple("Distance", "%.2f".format(d), vm.distUnit()),
                        Triple("Calories", "%.1f".format((l?.calories ?: 0) / 10.0), "kcal"),
                        Triple("Pace", if (d > 0.01) ms(time / d) else "--:--", "/" + vm.distUnit()),
                        Triple("Heart rate", if (hr > 0) "$hr" else "--", "bpm"),
                        Triple("Avg speed", if (time > 0) "%.1f".format(distM / 1000.0 / (time / 3600.0) / (if (vm.info.unit == 1) 1.609344 else 1.0)) else "0.0", vm.speedUnit()),
                        Triple("Steps", "${l?.steps ?: 0}", ""),
                        Triple("Cadence", if (time > 10) "${((l?.steps ?: 0) / (time / 60.0)).toInt()}" else "0", "spm"),
                    ).forEach { (label, value, unit) ->
                        Column(Modifier.padding(vertical = 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Label(label)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                                if (unit.isNotEmpty()) Text(" $unit", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("Speed")
                    Value("%.1f".format(vm.shownSpeed() / 10.0), vm.speedUnit())
                    PresetColumn(QUICK_PICKS.map { it * 10 }, running, { "${it / 10}" }) { vm.setSpeed(it) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlusMinus(running && hasIncline, { vm.setIncline(vm.shownIncline() + 1) }, { vm.setIncline(vm.shownIncline() - 1) }, Modifier.weight(1f))
                Row(Modifier.weight(1.4f).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ vm.start() }, Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(14.dp), enabled = canStart,
                        contentPadding = PaddingValues(0.dp)) { Text("Start", fontSize = 16.sp) }
                    Button({ vm.stop() }, Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(14.dp), enabled = canStop,
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text("Stop", fontSize = 16.sp) }
                }
                PlusMinus(running, { vm.changeSpeed(vm.settings.speedStep) }, { vm.changeSpeed(-vm.settings.speedStep) }, Modifier.weight(1f))
            }
        }

        SectionCard {
            Label("Goal · treadmill stops when reached")
            Spacer(Modifier.height(8.dp))
            val types = listOf(GoalType.NONE to "None", GoalType.TIME to "Time", GoalType.DISTANCE to "Dist.", GoalType.CALORIES to "kcal")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                types.forEachIndexed { i, (t, label) ->
                    SegmentedButton(selected = vm.goalType == t, onClick = { vm.setGoal(t, vm.goalValue) },
                        shape = SegmentedButtonDefaults.itemShape(i, types.size)) { Text(label) }
                }
            }
            if (vm.goalType != GoalType.NONE) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(vm.goalValue, { vm.setGoal(vm.goalType, it) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(when (vm.goalType) { GoalType.TIME -> "Minutes"; GoalType.DISTANCE -> vm.distUnit(); else -> "kcal" }) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator({ vm.goalProgress().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Quick picks 15, 13 … 1; values outside the treadmill's range are clamped when picked. */
private val QUICK_PICKS = (15 downTo 1 step 2).toList()

@Composable
private fun Value(value: String, unit: String) {
    Text(value, fontSize = 36.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp, maxLines = 1)
    Text(unit.ifEmpty { " " }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // keeps both columns level
}

@Composable
private fun PresetColumn(values: List<Int>, enabled: Boolean, label: (Int) -> String = { "$it" }, onPick: (Int) -> Unit) {
    Column(Modifier.padding(top = 8.dp).width(76.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { v ->
            FilledTonalButton({ onPick(v) }, Modifier.fillMaxWidth().height(40.dp), enabled = enabled, shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(0.dp)) { Text(label(v), fontSize = 16.sp) }
        }
    }
}

@Composable
private fun PlusMinus(enabled: Boolean, onPlus: () -> Unit, onMinus: () -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onPlus, Modifier.size(56.dp), enabled = enabled, shape = CircleShape, contentPadding = PaddingValues(0.dp)) { Text("+", fontSize = 26.sp) }
        FilledTonalButton(onMinus, Modifier.size(56.dp), enabled = enabled, shape = CircleShape, contentPadding = PaddingValues(0.dp)) { Text("−", fontSize = 26.sp) }
    }
}

@Composable
private fun CountdownOverlay(n: Int) {
    Box(Modifier.fillMaxSize().padding(0.dp), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(32.dp)) {
            Column(Modifier.padding(horizontal = 56.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Label("Starting")
                Text("$n", fontSize = 120.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---------------- HISTORY ----------------
@Composable
private fun HistoryScreen(vm: TreadmillViewModel) {
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(vm.historyCsv().toByteArray()) } }
    }
    val list = vm.history.reversed()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            SectionCard {
                Label("Totals")
                val rows = listOf(
                    "Workouts" to "${vm.history.size}",
                    "Distance" to "%.2f %s".format(vm.history.sumOf { vm.toDist(it.distance) }, vm.distUnit()),
                    "Time" to hms(vm.history.sumOf { it.time }),
                    "Calories" to "%.0f kcal".format(vm.history.sumOf { it.calories }),
                )
                rows.forEach { (k, v) -> Row(Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(k, Modifier.weight(1f)); Text(v, fontWeight = FontWeight.SemiBold) } }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ exporter.launch("treadmill-history.csv") }, enabled = vm.history.isNotEmpty()) { Text("Export CSV") }
                    TextButton({ confirmClear = true }, enabled = vm.history.isNotEmpty()) { Text("Delete all") }
                }
            }
        }
        if (list.isEmpty()) item { Text("No workouts yet. Runs are saved here automatically when the treadmill stops.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(list) { w ->
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row {
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(w.date)), Modifier.weight(1f))
                    Text("%.2f %s".format(vm.toDist(w.distance), vm.distUnit()), fontWeight = FontWeight.Bold)
                }
                val d = vm.toDist(w.distance)
                Text(
                    "${hms(w.time)} · ${"%.1f".format(w.calories)} kcal · ${w.steps} steps" + (if (w.avgHr > 0) " · ${w.avgHr} bpm" else "") +
                        " · ${if (d > 0) ms(w.time / d) else "--:--"} /${vm.distUnit()}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Delete all workouts?") }, text = { Text("This cannot be undone.") },
        confirmButton = { TextButton({ vm.clearHistory(); confirmClear = false }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
}

// ---------------- DEVICE ----------------
private const val SHOW_WATCH_BROADCAST = false

@Composable
private fun DeviceScreen(vm: TreadmillViewModel, withBluetooth: (() -> Unit) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label("Treadmill") }
                TextButton({ vm.requestInfo() }, enabled = vm.conn is Conn.Connected) { Text("Refresh") }
            }
            val i = vm.info
            val rows = listOf(
                "Model" to (i.modelName ?: "—"),
                "Model number" to (i.model?.toString() ?: "—"),
                "Serial" to (i.serial?.toString() ?: "—"),
                "Speed range" to (if (i.speedKnown) "${i.minSpeed / 10.0} – ${i.maxSpeed / 10.0} ${vm.speedUnit()}" else "—"),
                "Incline range" to (if (i.maxIncline > 0) "${i.minIncline} – ${i.maxIncline}" else if (i.speedKnown) "No incline" else "—"),
                "Total distance" to (i.total?.toString() ?: "—"),
            )
            rows.forEach { (k, v) -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text(k, Modifier.weight(1f)); Text(v, fontWeight = FontWeight.SemiBold) } }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label("Heart rate · watch or chest strap") }
                if (vm.hrConnected) TextButton({ vm.disconnectHeartRate() }) { Text("Disconnect") }
                else TextButton({ withBluetooth { vm.startScan(ScanTarget.HEART_RATE) } }) { Text("Connect") }
            }
            Text(vm.hrStatus, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Huawei Watch GT: open Settings → Workout settings → turn on \"HR data broadcasts\" (wording varies by model), or start a workout. " +
                    "Without a watch, the treadmill's hand-grip sensor is used.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Watch broadcast (FtmsServer): hidden for now. Huawei Watch GT 7 Pro only accepts it as a cycling
        // speed sensor, and only from a phone that is not its Huawei Health companion. Set to true to show it.
        if (SHOW_WATCH_BROADCAST) SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label("Send speed & incline to watch") }
                if (vm.ftmsOn) TextButton({ vm.stopFtms() }) { Text("Stop") }
                else TextButton({ withBluetooth { vm.startFtms() } }) { Text("Start") }
            }
            Text(vm.ftmsStatus, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "The phone appears as a standard Bluetooth (FTMS) treadmill. On the watch start Indoor Run and look for a " +
                    "\"connect to equipment\" option, or pair it from the watch's Bluetooth settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard {
            Label("Bluetooth log")
            Spacer(Modifier.height(6.dp))
            Text(vm.log.take(80).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------- SETTINGS ----------------
@Composable
private fun SettingsScreen(vm: TreadmillViewModel) {
    val s = vm.settings
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SectionCard {
            Label("Distance units")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                SegmentedButton(!s.miles, { vm.updateSettings(s.copy(miles = false)) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Kilometres") }
                SegmentedButton(s.miles, { vm.updateSettings(s.copy(miles = true)) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Miles") }
            }
            Spacer(Modifier.height(16.dp))
            Label("Speed step for − / +")
            val steps = listOf(1 to "0.1", 5 to "0.5", 10 to "1.0")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                steps.forEachIndexed { i, (v, label) ->
                    SegmentedButton(s.speedStep == v, { vm.updateSettings(s.copy(speedStep = v)) }, SegmentedButtonDefaults.itemShape(i, steps.size)) { Text(label) }
                }
            }
            Spacer(Modifier.height(16.dp))
            var weight by remember { mutableStateOf(s.weightKg.toString()) }
            OutlinedTextField(weight, { weight = it; it.toIntOrNull()?.let { w -> vm.updateSettings(vm.settings.copy(weightKg = w.coerceIn(0, 255))) } },
                Modifier.fillMaxWidth(), label = { Text("Body weight (kg)") }, singleLine = true,
                supportingText = { Text("Sent to the treadmill on connect for its calorie count.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Keep screen on while running", Modifier.weight(1f))
                Switch(s.keepScreenOn, { vm.updateSettings(s.copy(keepScreenOn = it)) })
            }
        }
        SectionCard {
            var svc by remember { mutableStateOf(s.customService) }
            OutlinedTextField(svc, { svc = it; vm.updateSettings(vm.settings.copy(customService = it)) }, Modifier.fillMaxWidth(),
                label = { Text("Custom service UUID (advanced)") }, singleLine = true, placeholder = { Text("e.g. fff0") },
                supportingText = { Text("Leave empty. Only needed if Connect says no data channel was found.") })
        }
        SectionCard {
            Label("Try without a treadmill")
            Spacer(Modifier.height(8.dp))
            FilledTonalButton({ vm.startDemo() }) { Text("Start demo treadmill") }
        }
        Text(
            "Free community app for E-RUNNING treadmills abandoned by their manufacturer. No account, no ads, no cloud: all data stays on your phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp, 4.dp, 4.dp, 24.dp),
        )
    }
}

// ---------------- DEVICE PICKER ----------------
@Composable
private fun DevicePicker(vm: TreadmillViewModel) {
    val target = vm.scanning ?: return
    AlertDialog(
        onDismissRequest = { vm.stopScan() },
        title = { Text(if (target == ScanTarget.TREADMILL) "Choose your treadmill" else "Choose heart-rate device") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Searching… make sure it's switched on.", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(vm.found, key = FoundDevice::address) { d ->
                        val last = d.address == vm.settings.lastDevice
                        TextButton(
                            { if (target == ScanTarget.TREADMILL) vm.connect(d.address) else vm.connectHeartRate(d.address) },
                            Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name + if (last) "  (last used)" else "", fontWeight = FontWeight.SemiBold)
                                Text("${d.address} · ${d.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ vm.stopScan() }) { Text("Cancel") } },
    )
}
