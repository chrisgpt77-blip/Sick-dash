package com.sickdash.can

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Serial-over-Bluetooth-LE, as used by DauntlessOBD (service FFF0, notify FFF1, write FFF2),
 * OBDLink CX, Vgate iCar Pro BLE and most other BLE ELM327 adapters. The right characteristics
 * are found by their properties, preferring the well-known FFF0 / FFE0 services.
 */
@SuppressLint("MissingPermission")
class BleSerialTransport(private val context: Context, private val address: String) : Transport {
    override var label = "Bluetooth LE $address"; private set

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var payload = 20
    private val incoming = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false

    @Volatile private var step: CountDownLatch? = null
    @Volatile private var stepOk = false
    @Volatile private var writeDone: CountDownLatch? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) finish(true)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                finish(false)
                writeDone?.countDown()
                closed = true
                incoming.offer(EOF)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) payload = (mtu - 3).coerceIn(20, 512)
            finish(true)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(status == BluetoothGatt.GATT_SUCCESS)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            finish(status == BluetoothGatt.GATT_SUCCESS)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone?.countDown()
        }

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") c.value?.let { incoming.offer(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.offer(value.copyOf())
        }
    }

    private fun finish(ok: Boolean) {
        stepOk = ok
        step?.countDown()
    }

    /** Runs one GATT operation and waits for its callback. */
    private fun await(what: String, timeoutMs: Long = 8000, start: () -> Boolean) {
        val latch = CountDownLatch(1)
        step = latch
        stepOk = false
        if (!start()) throw IOException("Bluetooth LE: could not $what")
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("Bluetooth LE: timed out trying to $what")
        if (!stepOk) throw IOException("Bluetooth LE: failed to $what")
    }

    override fun open() {
        if (address.isBlank()) throw IOException("Pick your Bluetooth LE adapter in Settings")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This phone has no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        label = device.name ?: address
        await("connect to $label", 12_000) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        }
        val g = gatt!!
        runCatching { await("negotiate packet size", 3000) { g.requestMtu(247) } }
        await("discover services") { g.discoverServices() }

        val services = g.services.sortedByDescending { it.uuid in PREFERRED_SERVICES }
        for (svc in services) {
            val notify = svc.characteristics.firstOrNull { it.properties and (PROP_NOTIFY or PROP_INDICATE) != 0 }
            val write = svc.characteristics.firstOrNull { it.properties and (PROP_WRITE or PROP_WRITE_NR) != 0 }
            if (notify != null && write != null) { rx = notify; tx = write; break }
        }
        val r = rx ?: throw IOException("$label has no serial service — is it an OBD adapter?")
        g.setCharacteristicNotification(r, true)
        r.getDescriptor(CCCD)?.let { d ->
            val value = if (r.properties and PROP_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            await("subscribe to data") {
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
                else @Suppress("DEPRECATION") run { d.value = value; g.writeDescriptor(d) }
            }
        }
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    override val input = object : InputStream() {
        private var buf = ByteArray(0)
        private var pos = 0
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (pos >= buf.size) {
                val next = incoming.take()
                if (next === EOF) { incoming.offer(EOF); return -1 }
                buf = next
                pos = 0
            }
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }
    }

    override val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val g = gatt ?: throw IOException("Bluetooth LE closed")
            val c = tx ?: throw IOException("Bluetooth LE closed")
            val noResponse = c.properties and PROP_WRITE == 0
            val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            var i = off
            while (i < off + len) {
                if (closed) throw IOException("Bluetooth LE disconnected")
                val chunk = b.copyOfRange(i, minOf(off + len, i + payload))
                val latch = CountDownLatch(1)
                writeDone = latch
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    var status = g.writeCharacteristic(c, chunk, type)
                    var tries = 0
                    while (status == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && tries++ < 50) {
                        Thread.sleep(5)
                        status = g.writeCharacteristic(c, chunk, type)
                    }
                    status == BluetoothStatusCodes.SUCCESS
                } else @Suppress("DEPRECATION") run {
                    c.writeType = type
                    c.value = chunk
                    g.writeCharacteristic(c)
                }
                if (!started) throw IOException("Bluetooth LE write failed")
                latch.await(1000, TimeUnit.MILLISECONDS)
                i += chunk.size
            }
        }
    }

    override fun close() {
        closed = true
        incoming.offer(EOF)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        private val EOF = ByteArray(0)
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val PREFERRED_SERVICES = setOf(
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
        )
        private const val PROP_NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        private const val PROP_INDICATE = BluetoothGattCharacteristic.PROPERTY_INDICATE
        private const val PROP_WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
        private const val PROP_WRITE_NR = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    }
}
