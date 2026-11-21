package com.daniel.carscanner.obd

/*
 * WHAT THIS FILE DOES
 * Turns the car's raw trouble-code bytes into codes like P0301, and gives each
 * code a short plain-English description.
 *
 * First letter: P = engine/transmission, C = chassis, B = body, U = network.
 * A "1" or "3" after the P means the code is specific to that manufacturer.
 */
object DtcDecoder {
    /** Two raw bytes ("0301") -> "P0301". Returns null for empty slots ("0000"). */
    fun decode(word: String): String? {
        if (word == "0000") return null
        val v = word.toIntOrNull(16) ?: return null
        val letter = "PCBU"[(v shr 14) and 3]
        val first = (v shr 12) and 3
        return "%c%d%03X".format(letter, first, v and 0xFFF)
    }

    /** Pulls every code out of a reply. replyTag is "43" for mode 03, "47" for 07, "4A" for 0A. */
    fun parse(resp: String, replyTag: String, isCan: Boolean): List<String> {
        val codes = linkedSetOf<String>()
        for (line in Hex.dataLines(resp)) {
            if (!line.startsWith(replyTag)) continue
            var payload = line.substring(2)
            if (isCan) payload = payload.drop(2)   // CAN replies include a code count byte
            payload.chunked(4).filter { it.length == 4 }.mapNotNull(::decode).forEach { codes += it }
        }
        return codes.toList()
    }

    /** Descriptions for common codes. Everything else gets a general category. */
    private val known = mapOf(
        "P0011" to "Camshaft timing over-advanced, bank 1",
        "P0016" to "Crankshaft / camshaft position correlation, bank 1",
        "P0101" to "Mass air flow sensor range/performance",
        "P0113" to "Intake air temp sensor circuit high",
        "P0117" to "Coolant temp sensor circuit low",
        "P0118" to "Coolant temp sensor circuit high",
        "P0128" to "Coolant thermostat (engine not reaching temperature)",
        "P0135" to "O2 sensor heater circuit, bank 1 sensor 1",
        "P0141" to "O2 sensor heater circuit, bank 1 sensor 2",
        "P0171" to "System too lean, bank 1",
        "P0172" to "System too rich, bank 1",
        "P0174" to "System too lean, bank 2",
        "P0175" to "System too rich, bank 2",
        "P0300" to "Random/multiple cylinder misfire",
        "P0301" to "Cylinder 1 misfire", "P0302" to "Cylinder 2 misfire",
        "P0303" to "Cylinder 3 misfire", "P0304" to "Cylinder 4 misfire",
        "P0305" to "Cylinder 5 misfire", "P0306" to "Cylinder 6 misfire",
        "P0401" to "EGR flow insufficient",
        "P0420" to "Catalyst efficiency below threshold, bank 1",
        "P0430" to "Catalyst efficiency below threshold, bank 2",
        "P0440" to "Evaporative emission system malfunction",
        "P0441" to "Evaporative emission system incorrect purge flow",
        "P0442" to "Evaporative emission system small leak",
        "P0455" to "Evaporative emission system large leak",
        "P0456" to "Evaporative emission system very small leak",
        "P0480" to "Cooling fan 1 control circuit",
        "P0500" to "Vehicle speed sensor",
        "P0505" to "Idle air control system",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "U0073" to "Control module network bus off",
        "U0100" to "Lost communication with engine computer",
        "U0101" to "Lost communication with transmission computer",
        "U0121" to "Lost communication with ABS module",
        "U0140" to "Lost communication with body control module",
        "U0151" to "Lost communication with airbag module",
        "U0155" to "Lost communication with instrument cluster",
        "U0164" to "Lost communication with climate control module",
    )

    /** Plain-English description of a code. */
    fun describe(code: String): String {
        known[code]?.let { return it }
        val n = code.substring(1).toIntOrNull(16) ?: 0
        return when (code[0]) {
            'P' -> when {
                code[1] == '1' || code[1] == '3' -> "Manufacturer-specific powertrain code"
                n in 0x0100..0x0299 -> "Fuel and air metering"
                n in 0x0300..0x0399 -> "Ignition system or misfire"
                n in 0x0400..0x0499 -> "Emission controls"
                n in 0x0500..0x0599 -> "Speed, idle control or auxiliary inputs"
                n in 0x0600..0x0699 -> "Engine computer or output circuits"
                n in 0x0700..0x0999 -> "Transmission"
                else -> "Powertrain"
            }
            'C' -> "Chassis (brakes, ABS, steering, suspension)"
            'B' -> "Body (airbags, climate, lighting, seats)"
            else -> "Network / module communication"
        }
    }
}
