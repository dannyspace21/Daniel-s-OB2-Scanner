package com.daniel.carscanner.obd

enum class Risk { Normal, Actuator, Dangerous }

/*
 * WHAT THIS FILE DOES
 * Sorts every command you type into three groups before it's sent:
 *   Normal    - reading data. Sent right away.
 *   Actuator  - switches a real part on (fan, pump). Asks you to confirm first.
 *   Dangerous - can reset, unlock, rewrite or reprogram a module. Blocked unless
 *               Expert mode is on. A wrong one of these can disable a module.
 */

/**
 * Guards raw commands. "Actuator" commands move real parts and need a confirmation.
 * "Dangerous" ones can reset, reprogram, unlock or rewrite a module and are blocked
 * unless Expert mode is on.
 */
object Safety {
    private val dangerous = setOf(0x11, 0x27, 0x28, 0x2E, 0x31, 0x34, 0x35, 0x36, 0x37, 0x3B, 0x3D, 0x85)

    fun classify(cmd: String): Risk {
        val c = cmd.replace(" ", "").uppercase()
        if (c.startsWith("ATPP")) return Risk.Dangerous     // permanently changes the adapter
        if (c.startsWith("AT") || c.startsWith("ST")) return Risk.Normal
        if (!Hex.isHex(c) || c.length < 2) return Risk.Normal
        if (c.startsWith("1002")) return Risk.Dangerous     // programming session
        val sid = c.take(2).toInt(16)
        return when (sid) {
            in dangerous -> Risk.Dangerous
            0x2F, 0x30 -> Risk.Actuator
            else -> Risk.Normal
        }
    }
}
