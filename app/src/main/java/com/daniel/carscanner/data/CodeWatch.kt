package com.daniel.carscanner.data

import org.json.JSONObject

/*
 * WHAT THIS FILE DOES
 * Catches codes that come back after you clear them.
 *
 * When you clear codes, the app remembers which codes were there and when.
 * The next time it reads codes (Codes screen, health check or module scan),
 * any of those that reappear are flagged: the repair didn't hold, or the
 * cause is still there. You get an alert once per return.
 *
 * Codes are remembered per place: "ENGINE" for the engine computer, or a
 * module's address (like "7B0") for module codes.
 */
data class ReturnedCode(
    val code: String,
    val where: String,      // shown to you, e.g. "Engine computer" or "ABS / brakes"
    val placeKey: String,   // stable key, e.g. "ENGINE" or "7B0"
    val clearedAt: Long,
)

object CodeWatch {
    const val ENGINE = "ENGINE"

    fun key(place: String, code: String) = "$place|$code"

    /** Which of the codes just read were cleared earlier. */
    fun returned(cleared: Map<String, Long>, place: String, where: String, codes: List<String>): List<ReturnedCode> =
        codes.distinct().mapNotNull { c -> cleared[key(place, c)]?.let { ReturnedCode(c, where, place, it) } }

    /** "3 days", "5 hours" or "a few minutes" since a time. */
    fun ago(t: Long, now: Long): String {
        val mins = (now - t) / 60_000
        return when {
            mins < 60 -> "a few minutes"
            mins < 48 * 60 -> "${mins / 60} hour${if (mins / 60 == 1L) "" else "s"}"
            else -> "${mins / 1440} days"
        }
    }

    fun toJson(map: Map<String, Long>): String = JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }.toString()

    fun fromJson(s: String?): Map<String, Long> = try {
        if (s.isNullOrBlank()) emptyMap() else {
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { o.optLong(it, 0L) }
        }
    } catch (_: Exception) { emptyMap() }
}
