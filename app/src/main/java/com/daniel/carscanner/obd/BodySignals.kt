package com.daniel.carscanner.obd

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/*
 * WHAT THIS FILE DOES
 * A "body signal" teaches the app how to read one thing from your car's
 * body computer, like "driver door open" or "left turn signal".
 *
 * Every car is different, so you set each one up once:
 *   header    which module to ask (for example 750 on Lexus, with a sub-address)
 *   command   the request to send (found with Techstream or the Watch tool)
 *   byteIndex which byte of the reply holds the value (0 = the first byte)
 *   bit       which bit (0-7) for on/off things, or -1 to use the whole byte
 *             as a position (windows, sunroof), scaled between rawMin and rawMax
 *   invert    flip it, for cars that report 1 = closed instead of 1 = open
 *   failure   true if this bit means "bulb burned out" rather than "on"
 *   fast      read it every cycle (needed for turn signals to blink in step)
 * Signals are saved per car (by VIN).
 */
data class BodySignal(
    val id: String = UUID.randomUUID().toString(),
    val vin: String,
    val element: Element,
    val header: String,
    val command: String,
    val byteIndex: Int,
    val bit: Int,
    val invert: Boolean = false,
    val failure: Boolean = false,
    val fast: Boolean = false,
    val rawMin: Int = 0,
    val rawMax: Int = 255,
) {
    /** Reads this signal's value (0..1) out of a reply, or null if the reply doesn't contain it. */
    fun decode(reply: String): Float? {
        val line = Hex.dataLines(reply).firstOrNull { !it.startsWith("7F") } ?: return null
        val bytes = Hex.bytes(line)
        if (byteIndex !in bytes.indices) return null
        val b = bytes[byteIndex]
        return if (bit in 0..7) {
            val on = ((b shr bit) and 1 == 1) != invert
            if (on) 1f else 0f
        } else {
            val span = (rawMax - rawMin).takeIf { it != 0 } ?: return null
            val f = ((b - rawMin).toFloat() / span).coerceIn(0f, 1f)
            if (invert) 1f - f else f
        }
    }

    /** Short description for lists, e.g. "750 / 2121, byte 4, bit 2". */
    val where: String
        get() = "$header / $command, byte $byteIndex, " + if (bit in 0..7) "bit $bit" else "whole byte"
}

/** Saves and loads body signals for all cars in one small file. */
class BodySignalStore(private val file: File) {
    fun load(): List<BodySignal> = try {
        if (!file.exists()) emptyList() else {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val e = Element.byKey(o.optString("element")) ?: return@mapNotNull null
                BodySignal(
                    id = o.optString("id", UUID.randomUUID().toString()), vin = o.optString("vin"), element = e,
                    header = o.optString("header"), command = o.optString("command"),
                    byteIndex = o.optInt("byteIndex", 0), bit = o.optInt("bit", 0),
                    invert = o.optBoolean("invert", false), failure = o.optBoolean("failure", false),
                    fast = o.optBoolean("fast", false), rawMin = o.optInt("rawMin", 0), rawMax = o.optInt("rawMax", 255),
                )
            }
        }
    } catch (_: Exception) { emptyList() }

    fun save(list: List<BodySignal>) {
        file.writeText(JSONArray(list.map { s ->
            JSONObject().put("id", s.id).put("vin", s.vin).put("element", s.element.key).put("header", s.header)
                .put("command", s.command).put("byteIndex", s.byteIndex).put("bit", s.bit).put("invert", s.invert)
                .put("failure", s.failure).put("fast", s.fast).put("rawMin", s.rawMin).put("rawMax", s.rawMax)
        }).toString(2))
    }
}
