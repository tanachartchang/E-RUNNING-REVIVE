package com.bkpsoft.erunning.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Standard Bluetooth Heart Rate Service (0x180D): Huawei watches with heart-rate broadcast turned on
 * (e.g. Watch GT 7 Pro), Garmin/Polar/Coros broadcast, and chest straps.
 */
@SuppressLint("MissingPermission")
class HeartRateLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onBpm: (Int) -> Unit,
    private val onState: (connected: Boolean, message: String) -> Unit,
) {
    val name: String = device.name ?: device.address
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var closed = false

    fun connect() {
        gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, cb)
    }

    fun close() {
        if (closed) return
        closed = true
        gatt?.disconnect(); gatt?.close(); gatt = null
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED && !closed) { close(); onState(false, "Heart-rate monitor disconnected") }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                if (ch == null) { close(); onState(false, "$name does not broadcast heart rate"); return@post }
                g.setCharacteristicNotification(ch, true)
                val d = ch.getDescriptor(BleLink.CCCD)
                if (d != null) {
                    if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    else @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
                }
                onState(true, "Connected to $name")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val bpm = parse(value) ?: return
            main.post { onBpm(bpm) }
        }

        @Deprecated("Android 12 and lower")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val bpm = parse(c.value ?: return) ?: return
            main.post { onBpm(bpm) }
        }
    }

    companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        fun parse(v: ByteArray): Int? {
            if (v.size < 2) return null
            return if (v[0].toInt() and 1 != 0 && v.size >= 3) (v[1].toInt() and 0xff) or ((v[2].toInt() and 0xff) shl 8)
            else v[1].toInt() and 0xff
        }
    }
}
