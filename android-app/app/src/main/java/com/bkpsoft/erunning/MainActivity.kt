package com.bkpsoft.erunning

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.bkpsoft.erunning.ui.App

class MainActivity : ComponentActivity() {
    private val vm: TreadmillViewModel by viewModels()
    private var pending: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) ensureEnabled() else {
            pending = null
            vm.alert = "Bluetooth permission needed" to "Allow \"Nearby devices\" (or Location on older Android) so the app can find your treadmill."
        }
    }

    private val enableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (adapterOn()) runPending() else pending = null
    }

    // Results arrive just before onResume, which would hide the overlay again, so YouTube is opened from onResume.
    private var youTubeOnResume = false
    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { youTubeOnResume = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SpeedOverlayService.vm = vm
        setContent { App(vm, withBluetooth = ::withBluetooth, openYouTube = ::openYouTube) }
    }

    override fun onResume() {
        super.onResume()
        if (youTubeOnResume) { youTubeOnResume = false; launchYouTube() }
        else SpeedOverlayService.stop(this) // overlay is only for when another app is in front
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) { SpeedOverlayService.stop(this); SpeedOverlayService.vm = null }
    }

    /** Opens YouTube with the floating speed control on top; asks for "Display over other apps" the first time. */
    fun openYouTube() {
        if (vm.conn is Conn.Connected && !Settings.canDrawOverlays(this))
            overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        else launchYouTube()
    }

    private fun launchYouTube() {
        if (vm.conn is Conn.Connected) {
            if (!Settings.canDrawOverlays(this)) {
                vm.alert = "Speed control needs permission" to "Allow \"Display over other apps\" for E-Running to control speed while watching YouTube."
                return
            }
            SpeedOverlayService.start(this)
        }
        val yt = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
        runCatching { startActivity(yt) }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun adapterOn() = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true

    /** Runs [action] once Bluetooth permissions are granted and the adapter is on. */
    fun withBluetooth(action: () -> Unit) {
        pending = action
        val missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray()) else ensureEnabled()
    }

    private fun ensureEnabled() {
        if (adapterOn()) runPending()
        else runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            .onFailure { pending = null; vm.alert = "Bluetooth is off" to "Turn on Bluetooth and try again." }
    }

    private fun runPending() { val a = pending; pending = null; a?.invoke() }
}
