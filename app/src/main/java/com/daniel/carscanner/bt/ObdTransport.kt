package com.daniel.carscanner.bt

import kotlinx.coroutines.channels.ReceiveChannel

/*
 * WHAT THIS FILE DOES
 * Defines the "pipe" between the phone and the OBD adapter. There are two kinds
 * of Bluetooth adapters (Classic and BLE). Both implement this same interface,
 * so the rest of the app doesn't care which one you plugged in.
 */
interface ObdTransport {
    /** Bytes arriving from the adapter, in the order they came. */
    val input: ReceiveChannel<ByteArray>

    /** Opens the Bluetooth link. Throws an error with a readable message if it fails. */
    suspend fun connect()

    /** Sends bytes (a command) to the adapter. */
    suspend fun write(bytes: ByteArray)

    /** Closes the link and frees Bluetooth. Safe to call more than once. */
    fun close()
}
