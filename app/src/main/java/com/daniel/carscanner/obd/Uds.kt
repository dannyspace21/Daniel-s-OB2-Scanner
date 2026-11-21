package com.daniel.carscanner.obd

/*
 * WHAT THIS FILE DOES
 * When a module refuses a command, it answers "7F <service> <reason>".
 * This turns that reason number into English, e.g. "security access denied",
 * so you know WHY a test didn't run.
 */
object Uds {
    private val nrc = mapOf(
        0x10 to "general reject",
        0x11 to "service not supported by this module",
        0x12 to "sub-function not supported",
        0x13 to "wrong message length or format",
        0x21 to "module busy, try again",
        0x22 to "conditions not correct (check engine state, gear, speed)",
        0x24 to "request sequence error",
        0x31 to "request out of range (wrong ID)",
        0x33 to "security access denied",
        0x35 to "invalid security key",
        0x36 to "too many attempts",
        0x37 to "time delay not expired",
        0x7E to "not supported in the current diagnostic session",
        0x7F to "service not supported in the current diagnostic session",
    )

    /** Returns (service, code) for a negative response, ignoring "response pending" (0x78). */
    fun negative(resp: String): Pair<Int, Int>? =
        Hex.dataLines(resp).firstNotNullOfOrNull { l ->
            if (l.startsWith("7F") && l.length >= 6) {
                val code = l.substring(4, 6).toInt(16)
                if (code == 0x78) null else l.substring(2, 4).toInt(16) to code
            } else null
        }

    /** One-line summary of a reply: accepted, rejected (and why), or no answer. */
    fun describe(resp: String): String {
        val up = resp.uppercase()
        negative(resp)?.let { (_, code) -> return "Rejected: ${nrc[code] ?: "code %02X".format(code)}" }
        if ("NO DATA" in up) return "No reply from that module"
        if ("?" in up) return "The adapter didn't understand that command"
        val line = Hex.dataLines(resp).firstOrNull()
        return if (line != null) "Accepted ($line)" else resp
    }
}
