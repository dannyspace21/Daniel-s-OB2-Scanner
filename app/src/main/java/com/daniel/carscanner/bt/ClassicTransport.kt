package com.daniel.carscanner.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/*
 * WHAT THIS FILE DOES
 * Talks to "Classic" Bluetooth adapters, the kind you pair in Android settings
 * with a PIN like 1234. Most ELM327 clones, Veepeak and OBDLink MX+ work this way.
 * It behaves like a wireless serial cable.
 */
@SuppressLint("MissingPermission")
class ClassicTransport(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter?,
) : ObdTransport {

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val input: ReceiveChannel<ByteArray> = channel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: BluetoothSocket? = null

    /**
     * Opens the link. First tries the standard serial-port connection; if the
     * adapter refuses (common on cheap clones), it tries a backup method.
     * Then starts a background reader that passes incoming bytes to [input].
     */
    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            try { adapter?.cancelDiscovery() } catch (_: SecurityException) { }
            val first = device.createRfcommSocketToServiceRecord(SPP_UUID)
            val s = try {
                first.connect(); first
            } catch (e: IOException) {
                runCatching { first.close() }
                // Many clone adapters only accept the hidden "channel 1" socket.
                try {
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    val fallback = m.invoke(device, 1) as BluetoothSocket
                    fallback.connect(); fallback
                } catch (e2: Exception) {
                    throw IOException("Couldn't open the Bluetooth link. Is the adapter plugged in and paired?", e)
                }
            }
            socket = s
            scope.launch {
                val buf = ByteArray(1024)
                try {
                    val ins = s.inputStream
                    while (isActive) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) channel.send(buf.copyOf(n))
                    }
                } catch (_: IOException) { }
                channel.close()
            }
        }
    }

    /** Sends a command to the adapter. */
    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val out = socket?.outputStream ?: throw IOException("Not connected")
            out.write(bytes)
            out.flush()
        }
    }

    /** Stops the background reader and closes the Bluetooth socket. */
    override fun close() {
        scope.cancel()
        runCatching { socket?.close() }
        channel.close()
    }

    companion object {
        /** The standard Bluetooth "serial port" ID every SPP device uses. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
