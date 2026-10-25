package com.sickdash.can

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** A byte pipe to the adapter. [open] blocks; call it from Dispatchers.IO. */
interface Transport : AutoCloseable {
    val label: String
    val input: InputStream
    val output: OutputStream
    fun open()
}

class BluetoothSppTransport(private val context: Context, private val address: String) : Transport {
    private var socket: BluetoothSocket? = null
    override var label = "Bluetooth $address"; private set
    override val input: InputStream get() = socket!!.inputStream
    override val output: OutputStream get() = socket!!.outputStream

    @SuppressLint("MissingPermission")
    override fun open() {
        if (address.isBlank()) throw IOException("Pick your Bluetooth OBD adapter in Settings")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This phone has no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        label = device.name ?: address
        adapter.cancelDiscovery()
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (e: IOException) {
            // Many cheap ELM327 clones only answer on channel 1 without SDP.
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
        }
        socket = s
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

class TcpTransport(private val host: String, private val port: Int) : Transport {
    private var socket: Socket? = null
    override val label = "Wi-Fi $host:$port"
    override val input: InputStream get() = socket!!.getInputStream()
    override val output: OutputStream get() = socket!!.getOutputStream()

    override fun open() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 5000)
        socket = s
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}

/** USB-serial adapters (CH340/FTDI/CP210x/CDC: ELM327 USB, CANable/slcan, Arduino bridges). */
class UsbSerialTransport(private val context: Context, private val baud: Int) : Transport {
    private var port: UsbSerialPort? = null
    override var label = "USB"; private set

    override val input = object : InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val p = port ?: return -1
                val tmp = if (off == 0) b else ByteArray(len)
                val n = try { p.read(tmp, 200) } catch (e: IOException) { return -1 }
                if (n > 0) {
                    if (tmp !== b) System.arraycopy(tmp, 0, b, off, n)
                    return n
                }
            }
        }
    }

    override val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            val p = port ?: throw IOException("USB closed")
            p.write(b.copyOfRange(off, off + len), 1000)
        }
    }

    override fun open() {
        val manager = context.getSystemService(UsbManager::class.java)
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
            ?: throw IOException("No USB serial adapter plugged in")
        if (!manager.hasPermission(driver.device)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            manager.requestPermission(driver.device, PendingIntent.getBroadcast(context, 0, intent, flags))
            throw IOException("Allow USB access on the phone, then it will retry")
        }
        val connection = manager.openDevice(driver.device) ?: throw IOException("Could not open USB device")
        val p = driver.ports.first()
        p.open(connection)
        runCatching { p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
        runCatching { p.dtr = true; p.rts = true }
        port = p
        label = "USB ${driver.javaClass.simpleName.removeSuffix("SerialDriver")}"
    }

    override fun close() {
        runCatching { port?.close() }
        port = null
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.sickdash.USB_PERMISSION"
    }
}
