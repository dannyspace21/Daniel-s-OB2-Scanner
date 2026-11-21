package com.daniel.carscanner.obd

import org.json.JSONArray
import org.json.JSONObject

/*
 * WHAT THIS FILE DOES
 * Automatic setup for live view: the app finds which bit in your car's data
 * means "driver door open", "brake pressed", "headlights on" and so on,
 * without you needing to know any codes.
 *
 * How it works:
 *  1. Find:  every module in the car is asked for each block of data it will
 *            share (read requests only, nothing is changed). The blocks that
 *            answer are remembered for this car.
 *  2. Learn: for each part, you're asked to do something simple:
 *              "Close the driver door"  -> the app reads every block
 *              "Open the driver door"   -> reads again
 *              "Close it again"         -> reads again
 *            The bit that followed exactly what you did is the signal.
 *            Bits that change on their own (counters, sensors) are ignored.
 *
 * Everything here is plain logic with no Bluetooth, so it's unit tested.
 */

/** One block of data: a module plus the read request that returns it. */
data class DataSource(val address: ModuleAddress, val request: String) {
    val key: String get() = address.label + " " + request
}

/** One part to learn, and what to ask you to do. Some parts share a signal (both brake lights). */
data class LearnStep(
    val id: String,
    val title: String,
    val elements: List<Element>,
    val offText: String,
    val onText: String,
    val blinks: Boolean = false,
)

/** A bit that followed what you did. invert = the bit reads 0 when the part is on/open. */
data class FoundBit(val byteIndex: Int, val bit: Int, val invert: Boolean)

object Learn {

    /** The parts the app can learn, in a sensible order (doors first, so later steps can skip door bits). */
    val steps: List<LearnStep> = listOf(
        LearnStep("door_fl", "Driver door", listOf(Element.DoorFL), "Close the driver door", "Open the driver door"),
        LearnStep("door_fr", "Passenger door", listOf(Element.DoorFR), "Close the passenger door", "Open the passenger door"),
        LearnStep("door_rl", "Rear left door", listOf(Element.DoorRL), "Close the rear left door", "Open the rear left door"),
        LearnStep("door_rr", "Rear right door", listOf(Element.DoorRR), "Close the rear right door", "Open the rear right door"),
        LearnStep("trunk", "Trunk", listOf(Element.Trunk), "Close the trunk", "Open the trunk"),
        LearnStep("hood", "Hood", listOf(Element.Hood), "Close the hood", "Open the hood (pull the release)"),
        LearnStep("locked", "Door locks", listOf(Element.Locked), "Unlock the doors with the door switch", "Lock the doors with the door switch"),
        LearnStep("brake", "Brake lights", listOf(Element.BrakeL, Element.BrakeR), "Take your foot off the brake", "Press and hold the brake pedal"),
        LearnStep("headlights", "Headlights", listOf(Element.HeadlightL, Element.HeadlightR), "Turn the headlight switch off", "Turn the headlights on"),
        LearnStep("high_beam", "High beams", listOf(Element.HighBeam), "Headlights on, high beams off", "Turn the high beams on"),
        LearnStep("fog", "Fog lights", listOf(Element.Fog), "Fog lights off", "Turn the fog lights on"),
        LearnStep("turn_l", "Left turn signal", listOf(Element.TurnL), "Turn signals off", "Turn on the left turn signal", blinks = true),
        LearnStep("turn_r", "Right turn signal", listOf(Element.TurnR), "Turn signals off", "Turn on the right turn signal", blinks = true),
        LearnStep("hazard", "Hazard lights", listOf(Element.Hazard), "Hazard lights off", "Press the hazard button", blinks = true),
        LearnStep("reverse", "Reverse lights", listOf(Element.Reverse), "Foot on the brake, shifter in Park", "Keep your foot on the brake and shift to Reverse"),
        LearnStep("dome", "Interior light", listOf(Element.Dome), "Doors closed, interior light off", "Turn the interior light on with its switch"),
        LearnStep("wipers", "Wipers", listOf(Element.Wipers), "Wipers off", "Turn the wipers on (low)"),
    )

    /** Every read request to try on a module for one dialect. */
    val kwpRequests: List<String> = (0x01..0xFF).map { "21%02X".format(it) }
    val udsRequests: List<String> = (0x0100..0x01FF).map { "22%04X".format(it) }

    /** Addresses never worth reading for body data (airbag module). */
    fun skip(a: ModuleAddress): Boolean = a.ext == null && !a.is29 && a.request == 0x780

    /**
     * The data bytes of a positive reply to [request], or null if it refused or didn't answer.
     * Allows for a leading sub-address byte (Toyota/Lexus body network), which is dropped so
     * byte positions are the same however the module is reached.
     */
    fun payload(lines: List<String>, request: String): List<Int>? {
        val r = request.filterNot { it.isWhitespace() }.uppercase()
        if (r.length < 2 || !Hex.isHex(r)) return null
        val tag = "%02X".format(r.take(2).toInt(16) + 0x40) + r.substring(2)
        for (l in lines) {
            val line = when {
                l.startsWith(tag) -> l
                l.length > 2 && l.substring(2).startsWith(tag) -> l.substring(2)
                else -> null
            } ?: continue
            return Hex.bytes(line)
        }
        return null
    }

    /** True if the reply says the module doesn't support this kind of request at all (7F xx 11). */
    fun serviceUnsupported(lines: List<String>, request: String): Boolean {
        val sid = request.take(2).uppercase()
        return lines.any { it.contains("7F${sid}11") }
    }

    /**
     * Finds bits that followed what you did:
     *   off    readings while the part was off/closed (include extra quiet readings to weed out noise)
     *   on     readings while it was on/open
     *   back   readings after switching it off again
     * A bit qualifies if it was steady while off, steady at the same value when back,
     * and different while on (for blinking parts, different at least once).
     */
    fun candidates(off: List<List<Int>>, on: List<List<Int>>, back: List<List<Int>>, blinks: Boolean): List<FoundBit> {
        val all = off + on + back
        if (off.isEmpty() || on.isEmpty() || back.isEmpty()) return emptyList()
        val len = all.minOf { it.size }
        val out = mutableListOf<FoundBit>()
        for (i in 0 until len) for (bit in 0..7) {
            fun v(s: List<Int>) = (s[i] shr bit) and 1
            val offVals = off.map(::v).toSet()
            if (offVals.size != 1) continue
            val v0 = offVals.first()
            if (back.map(::v).toSet() != setOf(v0)) continue
            val onVals = on.map(::v)
            val followed = if (blinks) onVals.any { it != v0 } else onVals.all { it != v0 }
            if (followed) out += FoundBit(i, bit, invert = v0 == 1)
        }
        return out
    }

    // ---- Saving the list of data blocks found for a car ----

    fun sourcesToJson(list: List<DataSource>): String = JSONArray(list.map {
        JSONObject().put("req", it.address.request).put("resp", it.address.response)
            .put("ext", it.address.ext ?: -1).put("cmd", it.request)
    }).toString()

    fun sourcesFromJson(s: String?): List<DataSource> = try {
        if (s.isNullOrBlank()) emptyList() else {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                DataSource(
                    ModuleAddress(o.getInt("req"), o.getInt("resp"), o.optInt("ext", -1).takeIf { it >= 0 }),
                    o.getString("cmd"),
                )
            }
        }
    } catch (_: Exception) { emptyList() }
}
