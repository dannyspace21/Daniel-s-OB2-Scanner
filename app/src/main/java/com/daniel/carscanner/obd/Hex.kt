package com.daniel.carscanner.obd

/*
 * WHAT THIS FILE DOES
 * Cars answer in hexadecimal text like "41 0C 1A F8". These helpers clean that
 * text up so the rest of the app can read it.
 */
object Hex {
    private val frameLine = Regex("^([0-9A-F]):([0-9A-F]+)$")
    private val lengthLine = Regex("^[0-9A-F]{3}$")
    private val hexLine = Regex("^[0-9A-F]+$")

    /**
     * Turns raw ELM327 text into clean hex messages. Multi-frame CAN replies
     * ("014" / "0:49020131..." / "1:...") are stitched back into one message.
     */
    fun dataLines(resp: String): List<String> {
        val out = mutableListOf<String>()
        val multi = StringBuilder()
        for (raw in resp.lines()) {
            val l = raw.replace(" ", "").trim().uppercase()
            if (l.isEmpty()) continue
            val m = frameLine.find(l)
            when {
                m != null -> multi.append(m.groupValues[2])
                lengthLine.matches(l) -> if (multi.isNotEmpty()) { out += multi.toString(); multi.clear() }
                hexLine.matches(l) -> out += l
            }
        }
        if (multi.isNotEmpty()) out += multi.toString()
        return out
    }

    /** "1AF8" -> [0x1A, 0xF8]. */
    fun bytes(hex: String): List<Int> =
        hex.chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }

    /** True if the text is only 0-9 and A-F. */
    fun isHex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
}
