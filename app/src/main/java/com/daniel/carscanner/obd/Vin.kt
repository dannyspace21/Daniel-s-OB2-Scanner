package com.daniel.carscanner.obd

/*
 * WHAT THIS FILE DOES
 * Turns a 17-character VIN into a friendly name like "2017 Lexus" for the
 * Home screen. Only the maker (first 3 characters) and model year
 * (10th character) are decoded.
 */
object Vin {
    // First characters of the VIN -> maker. Longer, more specific prefixes come first.
    private val makers = listOf(
        "JTH" to "Lexus", "JTJ" to "Lexus", "2T2" to "Lexus", "58A" to "Lexus", "58B" to "Lexus",
        "JT" to "Toyota", "4T" to "Toyota", "5T" to "Toyota", "2T" to "Toyota",
        "KNA" to "Kia", "KND" to "Kia", "KNM" to "Kia", "5XX" to "Kia", "3KP" to "Kia",
        "ZDM" to "Ducati", "JHM" to "Honda", "1HG" to "Honda", "2HG" to "Honda",
        "WBA" to "BMW", "WBS" to "BMW", "1F" to "Ford", "1G" to "GM",
    )

    // Model-year letters repeat every 30 years; we assume 2010 or later.
    private const val YEAR_LETTERS = "ABCDEFGHJKLMNPRSTVWXY"

    fun describe(vin: String): String? {
        if (vin.length != 17) return null
        val maker = makers.firstOrNull { vin.startsWith(it.first) }?.second
        val c = vin[9]
        val year = when {
            c in '1'..'9' -> 2000 + (c - '0')
            YEAR_LETTERS.indexOf(c) >= 0 -> 2010 + YEAR_LETTERS.indexOf(c)
            else -> null
        }
        return listOfNotNull(year?.toString(), maker).joinToString(" ").ifEmpty { null }
    }
}
