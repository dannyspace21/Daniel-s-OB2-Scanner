package com.daniel.carscanner.obd

enum class Risk { Normal, Actuator, Dangerous }

/*
 * WHAT THIS FILE DOES
 * Sorts every command you type into three groups before it's sent:
 *   Normal    - reading data. Sent right away.
 *   Actuator  - switches a real part on (fan, pump) or erases codes and
 *               emissions readiness. Asks you to confirm first.
 *   Dangerous - can reset, unlock, rewrite or reprogram a module, or change how
 *               the adapter or the car's network behaves. Blocked unless Expert
 *               mode is on. A wrong one of these can disable a module.
 *
 * It also decides what is allowed to reach the adapter at all (isSendable):
 * one printable command per line, so nothing can hide a second command
 * behind a line break.
 */

/**
 * Guards raw commands. "Actuator" commands move real parts or erase codes and need
 * a confirmation. "Dangerous" ones can reset, reprogram, unlock or rewrite a module
 * and are blocked unless Expert mode is on.
 */
object Safety {
    /**
     * Service IDs (the first byte) that can change or break a module.
     * UDS (ISO 14229) and KWP2000 (ISO 14230) share most of these numbers.
     *   11 ECU reset              27 security unlock        28 communication control
     *   2E/3B write data          31 run a routine          34/35/36/37 download/upload (flashing)
     *   38 file transfer          3D write memory           83 timing parameters
     *   84 secured transmission   85 DTC setting on/off     86 response on event
     *   87 link control (changes the network's speed)
     */
    private val dangerous = setOf(
        0x11, 0x27, 0x28, 0x2E, 0x31, 0x34, 0x35, 0x36, 0x37, 0x38, 0x3B, 0x3D,
        0x83, 0x84, 0x85, 0x86, 0x87,
    )

    /** Diagnostic session types that put a module into programming (flashing) mode. */
    private val programmingSessions = setOf(0x02, 0x85)

    /** Adapter commands that change the adapter itself or send raw frames past this check. */
    private val dangerousAdapter = listOf(
        "ATPP",   // ELM327: permanently changes start-up settings
        "STPX",   // STN chips: sends any frame, bypassing this check
        "STSBR",  // STN chips: changes the serial speed (adapter stops answering)
        "STBR",
    )

    /** Reading DTCs is fine; erasing them also erases readiness and freeze frame. */
    private fun erasesCodes(sid: Int) = sid == 0x04 || sid == 0x14

    /**
     * True if this text may be sent to the adapter: printable ASCII only, so a line
     * break can't smuggle in a second command that skipped the safety check.
     */
    fun isSendable(cmd: String): Boolean =
        cmd.isNotBlank() && cmd.length <= 128 && cmd.all { it in ' '..'~' }

    /** True for a header a module can be addressed with: 3 (11-bit), 6 or 8 (29-bit) hex digits. */
    fun isValidHeader(header: String): Boolean {
        val h = header.replace(" ", "")
        return Hex.isHex(h) && h.length in setOf(3, 6, 8)
    }

    /**
     * What's wrong with an active test, in plain words, or null if it's fine.
     * Shown live under the form, so you can fix it before saving.
     */
    fun testProblem(header: String, commands: List<String>, seconds: Int): String? = when {
        header.isNotBlank() && !isValidHeader(header) -> "Module address must be 3, 6 or 8 hex digits, like 7E0."
        commands.any { !isSendable(it) } -> "Use one command per line, with only letters, numbers and spaces."
        seconds !in 1..120 -> "Time limit must be between 1 and 120 seconds."
        else -> null
    }

    /**
     * What's wrong with a request that repeats several times a second (body signals, Watch),
     * or null if it's fine. These may only read, never change anything.
     */
    fun readRequestProblem(header: String, command: String): String? = when {
        header.isNotBlank() && !isValidHeader(header) -> "Module address must be 3, 6 or 8 hex digits, like 750."
        command.isBlank() -> "Enter a request, like 2101 or 22F190."
        !isSendable(command) || !Hex.isHex(command.filterNot { it.isWhitespace() }) || classify(command) != Risk.Normal ->
            "Only read requests work here (like 21xx or 22xxxx), because it repeats several times a second."
        else -> null
    }

    fun classify(cmd: String): Risk {
        if (cmd.any { it.isISOControl() }) return Risk.Dangerous   // hidden line breaks: never allowed through
        val c = cmd.filterNot { it.isWhitespace() }.uppercase()
        if (dangerousAdapter.any { c.startsWith(it) }) return Risk.Dangerous
        if (c.startsWith("AT") || c.startsWith("ST")) return Risk.Normal
        if (!Hex.isHex(c) || c.length < 2) return Risk.Normal       // the adapter answers "?" to anything else
        val sid = c.take(2).toInt(16)
        if (sid == 0x10 && c.length >= 4 && c.substring(2, 4).toInt(16) in programmingSessions) return Risk.Dangerous
        return when {
            sid in dangerous -> Risk.Dangerous
            sid == 0x2F || sid == 0x30 -> Risk.Actuator
            erasesCodes(sid) -> Risk.Actuator
            else -> Risk.Normal
        }
    }
}
