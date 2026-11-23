package com.daniel.carscanner.obd

/*
 * WHAT THIS FILE DOES
 * "Mode 06": the engine computer's own self-test results for each emissions
 * monitor (oxygen sensors, catalytic converter, EVAP leak check, EGR, misfire
 * per cylinder...). For every test it keeps the last measured value and the
 * allowed minimum and maximum, so you can see a part that is close to failing
 * before the check engine light comes on.
 *
 * This works on CAN cars (most from 2008 on). Format of each result (9 bytes):
 *   MID  which monitor          TID  which test        UASID  units and scaling
 *   VALUE (2 bytes)  MIN (2 bytes)  MAX (2 bytes)
 * Pass = MIN <= VALUE <= MAX. That doesn't depend on the units, so pass/fail is
 * always right even when the units aren't known.
 *
 * Names and units come from SAE J1979 (Appendix D, E and F).
 */

/** One self-test result. value/min/max are already scaled; unit may be blank. */
data class MonitorTest(
    val mid: Int,
    val tid: Int,
    val uasid: Int,
    val rawValue: Int,
    val rawMin: Int,
    val rawMax: Int,
) {
    val monitor: String get() = Mode06.monitorName(mid)
    val test: String get() = Mode06.testName(mid, tid)
    private val scaling: Mode06.Scaling? get() = Mode06.scaling(uasid)
    val unit: String get() = scaling?.unit ?: ""
    val known: Boolean get() = scaling != null
    val value: Double get() = Mode06.scale(uasid, rawValue)
    val min: Double get() = Mode06.scale(uasid, rawMin)
    val max: Double get() = Mode06.scale(uasid, rawMax)
    /** Compared in the car's own raw numbers (signed where the unit is signed). */
    val passed: Boolean
        get() {
            val signed = uasid >= 0x80
            fun s(x: Int) = if (signed && x >= 0x8000) x - 0x10000 else x
            return s(rawValue) in s(rawMin)..s(rawMax)
        }
    /**
     * Passed, but within 10% of a limit. Only real limits count: many tests set just one
     * (a misfire count may be 0 up to a maximum; 0 is perfect, not "near a limit"), and an
     * unused limit is left at the extreme (0 or FFFF, or 8000 / 7FFF when signed).
     */
    val nearLimit: Boolean
        get() {
            if (!passed) return false
            val signed = uasid >= 0x80
            fun s(x: Int) = if (signed && x >= 0x8000) x - 0x10000 else x
            val v = s(rawValue); val lo = s(rawMin); val hi = s(rawMax)
            val span = (hi - lo).toDouble()
            if (span <= 0) return false
            val lowReal = if (signed) rawMin != 0x8000 else rawMin != 0
            val highReal = if (signed) rawMax != 0x7FFF else rawMax != 0xFFFF
            val margin = span * 0.1
            return (lowReal && v - lo < margin) || (highReal && hi - v < margin)
        }
}

object Mode06 {

    /** Units and scaling for one UASID. value = raw * factor + offset. */
    data class Scaling(val factor: Double, val unit: String, val offset: Double = 0.0)

    // Unsigned (0x01-0x41). Signed versions use the same scaling at 0x80 + n where listed below.
    private val unsigned = mapOf(
        0x01 to Scaling(1.0, ""), 0x02 to Scaling(0.1, ""), 0x03 to Scaling(0.01, ""), 0x04 to Scaling(0.001, ""),
        0x05 to Scaling(0.0000305, ""), 0x06 to Scaling(0.000305, ""), 0x07 to Scaling(0.25, "rpm"),
        0x08 to Scaling(0.01, "km/h"), 0x09 to Scaling(1.0, "km/h"), 0x0A to Scaling(0.122, "mV"),
        0x0B to Scaling(0.001, "V"), 0x0C to Scaling(0.01, "V"), 0x0D to Scaling(0.00390625, "mA"),
        0x0E to Scaling(0.001, "A"), 0x0F to Scaling(0.01, "A"), 0x10 to Scaling(1.0, "ms"),
        0x11 to Scaling(100.0, "ms"), 0x12 to Scaling(1.0, "s"), 0x13 to Scaling(1.0, "mΩ"),
        0x14 to Scaling(1.0, "Ω"), 0x15 to Scaling(1.0, "kΩ"), 0x16 to Scaling(0.1, "°C", -40.0),
        0x17 to Scaling(0.01, "kPa"), 0x18 to Scaling(0.0117, "kPa"), 0x19 to Scaling(0.079, "kPa"),
        0x1A to Scaling(1.0, "kPa"), 0x1B to Scaling(10.0, "kPa"), 0x1C to Scaling(0.01, "°"),
        0x1D to Scaling(0.5, "°"), 0x1E to Scaling(0.0000305, "λ"), 0x1F to Scaling(0.05, "A/F"),
        0x20 to Scaling(0.0039062, ""), 0x21 to Scaling(1.0, "mHz"), 0x22 to Scaling(1.0, "Hz"),
        0x23 to Scaling(1.0, "kHz"), 0x24 to Scaling(1.0, "counts"), 0x25 to Scaling(1.0, "km"),
        0x26 to Scaling(0.1, "mV/ms"), 0x27 to Scaling(0.01, "g/s"), 0x28 to Scaling(1.0, "g/s"),
        0x29 to Scaling(0.25, "Pa/s"), 0x2A to Scaling(0.001, "kg/h"), 0x2B to Scaling(1.0, "switches"),
        0x2C to Scaling(0.01, "g/cyl"), 0x2D to Scaling(0.01, "mg/stroke"), 0x2E to Scaling(1.0, ""),
        0x2F to Scaling(0.01, "%"), 0x30 to Scaling(0.001526, "%"), 0x31 to Scaling(0.001, "L"),
        0x32 to Scaling(0.0000305, "in"), 0x33 to Scaling(0.00024414, ""), 0x34 to Scaling(1.0, "min"),
        0x35 to Scaling(10.0, "ms"), 0x36 to Scaling(0.01, "g"), 0x37 to Scaling(0.1, "g"),
        0x38 to Scaling(1.0, "g"), 0x39 to Scaling(0.01, "%", -327.68), 0x3A to Scaling(0.001, "g"),
        0x3B to Scaling(0.0001, "g"), 0x3C to Scaling(0.1, "µs"), 0x3D to Scaling(0.01, "mA"),
        0x3F to Scaling(0.01, "L"), 0x40 to Scaling(1.0, "ppm"), 0x41 to Scaling(0.01, "µA"),
    )

    // Signed (two's complement) versions.
    private val signed = mapOf(
        0x81 to Scaling(1.0, ""), 0x82 to Scaling(0.1, ""), 0x83 to Scaling(0.01, ""), 0x84 to Scaling(0.001, ""),
        0x85 to Scaling(0.0000305, ""), 0x86 to Scaling(0.000305, ""), 0x8A to Scaling(0.122, "mV"),
        0x8B to Scaling(0.001, "V"), 0x8C to Scaling(0.01, "V"), 0x8D to Scaling(0.00390625, "mA"),
        0x8E to Scaling(0.001, "A"), 0x90 to Scaling(1.0, "ms"), 0x96 to Scaling(0.1, "°C"),
        0x9C to Scaling(0.01, "°"), 0x9D to Scaling(0.5, "°"), 0xA8 to Scaling(1.0, "g/s"),
        0xA9 to Scaling(0.25, "Pa/s"), 0xAD to Scaling(0.01, "mg/stroke"), 0xAE to Scaling(0.1, "mg/stroke"),
        0xAF to Scaling(0.01, "%"), 0xB0 to Scaling(0.003052, "%"), 0xB1 to Scaling(2.0, "mV/s"),
        0xFC to Scaling(0.01, "kPa"), 0xFD to Scaling(0.001, "kPa"), 0xFE to Scaling(0.25, "Pa"),
    )

    fun scaling(uasid: Int): Scaling? = unsigned[uasid] ?: signed[uasid]

    /** Raw 16-bit number to its real value. Unknown units: the raw number. */
    fun scale(uasid: Int, raw: Int): Double {
        val v = if (uasid >= 0x80 && raw >= 0x8000) raw - 0x10000 else raw
        val s = scaling(uasid) ?: return v.toDouble()
        return v * s.factor + s.offset
    }

    /** Monitor names (SAE J1979 Appendix D). */
    fun monitorName(mid: Int): String = when (mid) {
        in 0x01..0x10 -> "Oxygen sensor, bank ${(mid - 1) / 4 + 1} sensor ${(mid - 1) % 4 + 1}"
        in 0x21..0x24 -> "Catalytic converter, bank ${mid - 0x20}"
        in 0x31..0x34 -> "EGR, bank ${mid - 0x30}"
        in 0x35..0x38 -> "Variable valve timing, bank ${mid - 0x34}"
        0x39 -> "EVAP leak check (cap off / 0.150\")"
        0x3A -> "EVAP leak check (0.090\")"
        0x3B -> "EVAP leak check (0.040\")"
        0x3C -> "EVAP leak check (0.020\")"
        0x3D -> "EVAP purge flow"
        in 0x41..0x50 -> "Oxygen sensor heater, bank ${(mid - 0x41) / 4 + 1} sensor ${(mid - 0x41) % 4 + 1}"
        in 0x61..0x64 -> "Heated catalyst, bank ${mid - 0x60}"
        in 0x71..0x74 -> "Secondary air ${mid - 0x70}"
        in 0x81..0x84 -> "Fuel system, bank ${mid - 0x80}"
        0x85, 0x86 -> "Boost pressure, bank ${mid - 0x84}"
        0x90, 0x91 -> "NOx adsorber, bank ${mid - 0x8F}"
        0x98, 0x99 -> "NOx catalyst, bank ${mid - 0x97}"
        0xA1 -> "Misfire, all cylinders"
        in 0xA2..0xAD -> "Misfire, cylinder ${mid - 0xA1}"
        0xB0, 0xB1 -> "Particulate filter, bank ${mid - 0xAF}"
        else -> "Monitor %02X".format(mid)
    }

    /** Standard test names (SAE J1979 Appendix F); manufacturer tests (80+) get a number. */
    fun testName(mid: Int, tid: Int): String = when {
        mid in 0xA1..0xAD && tid == 0x0B -> "Misfires, average of the last 10 drives"
        mid in 0xA1..0xAD && tid == 0x0C -> "Misfires, last or current drive"
        tid == 0x01 -> "Rich-to-lean switch voltage"
        tid == 0x02 -> "Lean-to-rich switch voltage"
        tid == 0x03 -> "Low voltage for switch time"
        tid == 0x04 -> "High voltage for switch time"
        tid == 0x05 -> "Rich-to-lean switch time"
        tid == 0x06 -> "Lean-to-rich switch time"
        tid == 0x07 -> "Lowest voltage in test"
        tid == 0x08 -> "Highest voltage in test"
        tid == 0x09 -> "Time between switches"
        tid == 0x0A -> "Sensor period"
        tid >= 0x80 -> "Manufacturer test %02X".format(tid)
        else -> "Test %02X".format(tid)
    }

    /** Which monitor IDs a "0600 / 0620 / ..." reply says are supported (bitmap after 46 xx). */
    fun supportedMids(base: Int, line: String): Set<Int> {
        val tag = "46%02X".format(base)
        if (!line.startsWith(tag) || line.length < tag.length + 8) return emptySet()
        val bits = line.substring(tag.length, tag.length + 8).toLongOrNull(16) ?: return emptySet()
        return (1..32).filter { (bits shr (32 - it)) and 1L == 1L }.map { base + it }.toSet()
    }

    /** Pulls every test result out of reply lines to "06 xx" (CAN format). */
    fun parse(lines: List<String>): List<MonitorTest> {
        val out = mutableListOf<MonitorTest>()
        for (line in lines) {
            if (!line.startsWith("46")) continue
            val b = Hex.bytes(line.substring(2))
            var i = 0
            while (i + 9 <= b.size) {
                val mid = b[i]
                // Support replies (MID 00, 20, 40...) are bitmaps, not results.
                if (mid % 0x20 == 0) break
                out += MonitorTest(
                    mid = mid, tid = b[i + 1], uasid = b[i + 2],
                    rawValue = b[i + 3] * 256 + b[i + 4],
                    rawMin = b[i + 5] * 256 + b[i + 6],
                    rawMax = b[i + 7] * 256 + b[i + 8],
                )
                i += 9
            }
        }
        return out.distinctBy { Triple(it.mid, it.tid, it.rawValue) }
    }
}
