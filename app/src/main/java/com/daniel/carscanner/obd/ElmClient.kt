package com.daniel.carscanner.obd

import com.daniel.carscanner.bt.ObdTransport
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/*
 * WHAT THIS FILE DOES
 * Every OBD adapter app on earth speaks "ELM327", a simple text language:
 * you send "010C" and the car answers "41 0C 1A F8" (that's engine rpm).
 * This class sends those commands one at a time and reads the answers.
 *
 * Commands starting with AT talk to the adapter itself (ATZ = reset it).
 * Everything else is passed through to the car.
 */

/** Thrown when the car or adapter doesn't answer in time. Not always serious. */
class ElmTimeout(msg: String) : IOException(msg)

class ElmClient(
    private val transport: ObdTransport,
    private val log: (String) -> Unit,
) {
    private val mutex = Mutex()
    var isCan = true; private set
    /** True for cars that use long 29-bit network addresses (some European and newer cars). */
    var isCan29 = false; private set
    var protocolName = "Unknown"; private set

    /**
     * Sends one command and returns the reply text.
     * The adapter prints ">" when it's done, so we read until we see that.
     * Only one command runs at a time (the lock), because the adapter can't juggle two.
     * quiet = true keeps fast repeated reads (live gauges) out of the Console log.
     */
    suspend fun send(cmd: String, timeoutMs: Long = 4_000, quiet: Boolean = false): String = mutex.withLock {
        while (transport.input.tryReceive().isSuccess) { /* drop stale bytes */ }
        if (!quiet) log("> $cmd")
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        val finished = withTimeoutOrNull(timeoutMs) {
            while (sb.indexOf(">") < 0) {
                val chunk = try {
                    transport.input.receive()
                } catch (e: ClosedReceiveChannelException) {
                    throw IOException("Adapter disconnected")
                }
                sb.append(String(chunk, Charsets.US_ASCII))
            }
            true
        }
        val text = sb.toString().substringBefore('>')
            .replace("\r", "\n").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(cmd, ignoreCase = true) }
            .joinToString("\n")
        if (finished == null) {
            if (!quiet) log("(no reply) $text")
            throw ElmTimeout("No reply to $cmd")
        }
        if (!quiet) log(text.ifEmpty { "(empty)" })
        text
    }

    /** Resets the adapter, picks the protocol and confirms the car answers. Returns the adapter version. */
    suspend fun initialize(): String {
        send("ATZ", 5_000)
        delay(300)
        val version = runCatching { send("ATI") }.getOrDefault("ELM327")
        send("ATE0")      // echo off
        send("ATL0")      // no linefeeds
        send("ATS1")      // spaces on (we strip them anyway)
        send("ATH0")      // headers off for standard OBD
        send("ATSP0")     // auto protocol
        val probe = send("0100", 12_000)
        val up = probe.uppercase()
        if (Hex.dataLines(probe).none { it.startsWith("4100") }) {
            throw IOException(
                when {
                    "UNABLE" in up || "NO DATA" in up || "BUS" in up ->
                        "The adapter is connected, but the car didn't answer. Turn the ignition on and try again."
                    else -> "The adapter replied with something unexpected: $probe"
                }
            )
        }
        protocolName = runCatching { send("ATDP") }.getOrDefault("Unknown").removePrefix("AUTO, ")
        val dpn = runCatching { send("ATDPN") }.getOrDefault("").trim().uppercase()
        val p = if (dpn.length == 2 && dpn[0] == 'A') dpn[1] else dpn.lastOrNull()
        isCan = p != null && (p in '6'..'9' || p in 'A'..'C')
        isCan29 = p == '7' || p == '9'
        return version
    }

    /**
     * Asks the car which live values it supports. The car answers with a list of
     * on/off bits, 32 PIDs at a time, so we read 0100, 0120, 0140... until it stops.
     */
    suspend fun supportedPids(): Set<Int> {
        val set = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xA0) {
            val tag = "41%02X".format(base)
            val line = Hex.dataLines(send("01%02X".format(base), quiet = true))
                .firstOrNull { it.startsWith(tag) } ?: break
            val bits = line.substring(4).take(8).toLongOrNull(16) ?: break
            for (i in 0 until 32) if (bits and (1L shl (31 - i)) != 0L) set += base + i + 1
            if (base + 0x20 !in set) break
            base += 0x20
        }
        return set
    }

    /** Reads one live value (e.g. coolant temp) and converts it. Returns null if the car didn't answer. */
    suspend fun readPid(pid: Pid): Double? {
        val tag = "41%02X".format(pid.code)
        val line = Hex.dataLines(send("01%02X".format(pid.code), quiet = true))
            .firstOrNull { it.startsWith(tag) } ?: return null
        val bytes = Hex.bytes(line.substring(4))
        if (bytes.size < pid.bytes) return null
        return pid.decode(bytes)
    }

    /** Check-engine light state and number of stored codes (mode 01 PID 01). */
    suspend fun monitorStatus(): Pair<Boolean, Int>? {
        val line = Hex.dataLines(send("0101")).firstOrNull { it.startsWith("4101") && it.length >= 6 } ?: return null
        val a = line.substring(4, 6).toInt(16)
        return (a and 0x80 != 0) to (a and 0x7F)
    }

    /** Reads trouble codes. mode "03" = stored, "07" = pending, "0A" = permanent. */
    suspend fun readCodes(mode: String): List<String> {
        val replyTag = "%02X".format(mode.toInt(16) + 0x40)
        return DtcDecoder.parse(send(mode, 8_000), replyTag, isCan)
    }

    /** Mode 04: clears stored + pending codes and freeze frame, turns off the check-engine light. */
    suspend fun clearCodes(): Boolean {
        val r = send("04", 8_000)
        return Hex.dataLines(r).any { it.startsWith("44") }
    }

    /** Reads the 17-character VIN. Some older cars don't report it. */
    suspend fun readVin(): String? {
        val lines = Hex.dataLines(send("0902", 8_000))
        val hex = if (isCan) {
            val joined = lines.joinToString("")
            val i = joined.indexOf("4902")
            if (i < 0) return null
            joined.substring(i + 6)          // skip 49 02 and the item count
        } else {
            lines.filter { it.startsWith("4902") }.joinToString("") { it.substring(6) }
        }
        val vin = Hex.bytes(hex).map { it.toChar() }.filter { it.isLetterOrDigit() }.joinToString("")
        return vin.takeLast(17).ifEmpty { null }
    }

    /** Asks the adapter for the voltage at the OBD port (works even if the car doesn't report it). */
    suspend fun batteryVoltage(): String = send("ATRV", quiet = true)

    /** The command that points the adapter back at "all modules" after a test talked to one module. */
    fun resetHeaderCommand() = if (isCan) "ATSH7DF" else "ATSH686AF1"
}
