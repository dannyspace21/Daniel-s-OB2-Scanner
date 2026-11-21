package com.daniel.carscanner.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/*
 * WHAT THIS FILE DOES
 * Talks to Bluetooth Low Energy (BLE) adapters, like the Vgate iCar Pro BLE,
 * vLinker, OBDLink CX and "iPhone compatible" ELM327s. These don't pair in
 * Android settings; the app finds them by scanning.
 *
 * BLE has no standard "serial cable" mode, so each brand hides its data channel
 * in a slightly different place. We search the adapter for one channel we can
 * write commands to, and one that sends replies back ("notify").
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : ObdTransport {

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val input: ReceiveChannel<ByteArray> = channel
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var writeDone: CompletableDeferred<Boolean>? = null
    private val writeMutex = Mutex()

    /** Android calls these functions when something happens on the BLE link. */
    private val callback = object : BluetoothGattCallback() {
        // Link came up -> ask the adapter what channels it has. Link dropped -> report it.
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (!ready.isCompleted) ready.completeExceptionally(IOException("BLE adapter disconnected (status $status)"))
                    channel.close()
                }
            }
        }

        // Adapter listed its channels -> pick ours and turn on replies.
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val pair = pickCharacteristics(g)
            if (pair == null) {
                ready.completeExceptionally(IOException("This BLE device doesn't look like an OBD adapter"))
                return
            }
            val (notify, write) = pair
            writeChar = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD_UUID)
            if (cccd == null) { ready.complete(Unit); return }
            val value = if (notify.properties and PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        // Replies are switched on -> the link is ready to use.
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready.complete(Unit)
        }

        // One chunk of a command was delivered.
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // The adapter sent us reply bytes (Android 13+).
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            channel.trySend(value)
        }

        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val v = c.value
                if (v != null) channel.trySend(v.copyOf())
            }
        }
    }

    /**
     * Looks through the adapter's channels and returns (reply channel, command channel).
     * Known adapter layouts (FFF0, FFE0, Vgate) are tried first.
     */
    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val preferred = listOf("0000fff0", "0000ffe0", "e7810a71", "000018f0")
        val services = g.services.sortedBy { s ->
            val i = preferred.indexOfFirst { s.uuid.toString().lowercase().startsWith(it) }
            if (i < 0) 99 else i
        }
        for (s in services) {
            val n = s.characteristics.firstOrNull { it.properties and (PROPERTY_NOTIFY or PROPERTY_INDICATE) != 0 }
            val w = s.characteristics.firstOrNull { it.properties and (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) != 0 }
            if (n != null && w != null) return n to w
        }
        return null
    }

    /** Connects, finds the data channels and waits up to 15 s for the link to be ready. */
    override suspend fun connect() {
        withContext(Dispatchers.Main) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
        try {
            withTimeoutOrNull(15_000) { ready.await() }
                ?: throw IOException("Timed out connecting to the BLE adapter")
        } catch (e: Exception) {
            close(); throw e
        }
    }

    /** Sends a command in 20-byte pieces (the BLE limit), waiting for each piece to be delivered. */
    override suspend fun write(bytes: ByteArray) {
        writeMutex.withLock {
            val g = gatt ?: throw IOException("Not connected")
            val c = writeChar ?: throw IOException("Not connected")
            val type = if (c.properties and PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            for (chunk in bytes.toList().chunked(20)) {
                val data = chunk.toByteArray()
                val done = CompletableDeferred<Boolean>().also { writeDone = it }
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    c.writeType = type
                    @Suppress("DEPRECATION")
                    c.value = data
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
                if (!started) throw IOException("The adapter rejected a write")
                withTimeoutOrNull(2_000) { done.await() } ?: throw IOException("Write to adapter timed out")
            }
        }
    }

    /** Disconnects and releases the adapter. */
    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        channel.close()
    }

    companion object {
        /** Standard BLE switch that turns on "send me replies" for a channel. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
