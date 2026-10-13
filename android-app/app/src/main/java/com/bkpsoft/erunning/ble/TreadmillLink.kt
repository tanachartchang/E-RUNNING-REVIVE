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

/** Raw byte transport to a treadmill: real BLE or the built-in simulator. */
interface Link {
    val name: String
    val busy: Boolean
    fun write(bytes: ByteArray)
    fun close()
}

interface LinkListener {
    fun onLinkReady(link: Link)
    fun onLinkBytes(bytes: ByteArray)
    fun onLinkClosed(reason: String?)
    fun onLinkLog(text: String)
}

/**
 * BLE GATT link. Like the original app it finds the vendor service (not a standard 0x18xx one) that has
 * a notify characteristic and a write characteristic, enables notifications and writes without response.
 * All listener callbacks are delivered on the main thread.
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val customService: UUID?,
    private val listener: LinkListener,
) : Link {
    override val name: String = device.name ?: device.address
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var ready = false
    private var closed = false

    /** True while a write is in flight; the poll loop skips a tick instead of overlapping GATT ops. */
    @Volatile override var busy = false
        private set

    fun connect() {
        gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, callback)
    }

    override fun write(bytes: ByteArray) {
        val g = gatt ?: return
        val ch = writeChar ?: return
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        busy = true
        main.postDelayed({ busy = false }, 400) // safety net if the write callback never arrives
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, bytes, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { ch.writeType = type; ch.value = bytes; g.writeCharacteristic(ch) }
        }
        if (!ok) busy = false
    }

    override fun close() {
        if (closed) return
        closed = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun fail(reason: String) {
        if (closed) return
        close()
        listener.onLinkClosed(reason)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    listener.onLinkLog("connected, discovering services")
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail(if (ready) "Treadmill disconnected" else "Could not connect (status $status)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { setup(g, status) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { markReady() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            busy = false
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { listener.onLinkBytes(value) }
        }

        @Deprecated("Android 12 and lower")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = c.value?.copyOf() ?: return
            main.post { listener.onLinkBytes(v) }
        }
    }

    private fun markReady() {
        if (ready || closed) return
        ready = true
        listener.onLinkReady(this)
    }

    private fun setup(g: BluetoothGatt, status: Int) {
        if (closed) return
        if (status != BluetoothGatt.GATT_SUCCESS) return fail("Service discovery failed ($status)")
        val candidates = g.services.filter { s ->
            if (customService != null) s.uuid == customService else !isStandard(s.uuid)
        }
        val writable = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val notifiable = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        for (s in candidates) {
            val notify = s.characteristics.firstOrNull { it.properties and notifiable != 0 } ?: continue
            val write = s.characteristics.firstOrNull { it != notify && it.properties and writable != 0 }
                ?: s.characteristics.firstOrNull { it.properties and writable != 0 } ?: continue
            writeChar = write
            listener.onLinkLog("service ${s.uuid}\nnotify ${notify.uuid}\nwrite ${write.uuid}")
            enableNotify(g, notify)
            return
        }
        fail("No treadmill data channel found. Find the service UUID with nRF Connect and set it in Settings.")
    }

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD) ?: return markReady()
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, value)
        else @Suppress("DEPRECATION") run { cccd.value = value; g.writeDescriptor(cccd) }
        main.postDelayed({ markReady() }, 1500) // some modules never confirm the descriptor write
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val STANDARD = Regex("^000018[0-9a-f]{2}-0000-1000-8000-00805f9b34fb$", RegexOption.IGNORE_CASE)
        fun isStandard(u: UUID) = STANDARD.matches(u.toString())

        fun parseUuid(s: String): UUID? {
            val t = s.trim().lowercase()
            if (t.isEmpty()) return null
            return runCatching {
                if (Regex("^[0-9a-f]{4}$").matches(t)) UUID.fromString("0000$t-0000-1000-8000-00805f9b34fb") else UUID.fromString(t)
            }.getOrNull()
        }
    }
}
