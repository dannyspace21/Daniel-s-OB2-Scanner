package com.daniel.carscanner.obd

import android.content.Context

/*
 * WHAT THIS FILE DOES
 * Loads the built-in library of 2,066 standard trouble-code definitions
 * (every brand shares these: P0xxx, P2xxx, P3xxx engine codes and U0xxx
 * network codes). The list lives in assets/dtc_generic.tsv inside the app,
 * so it works with no internet.
 *
 * Source: the python-OBD project (GPL-2.0), checked against well-known codes.
 * Manufacturer-specific codes aren't in any free, reliable list, so for those
 * the app offers a search for your exact car instead.
 */
object DtcLibrary {
    @Volatile private var codes: Map<String, String> = emptyMap()

    /** How many definitions are loaded (shown on the About screen). */
    val size: Int get() = codes.size

    /** Reads the file once. Called in the background when the app starts. */
    fun load(context: Context) {
        if (codes.isNotEmpty()) return
        codes = try {
            context.assets.open("dtc_generic.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab == 5) line.substring(0, 5) to line.substring(6).trim() else null
                }.toMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** The definition for a code, or null if it's not a standard code. */
    fun lookup(code: String): String? = codes[code]
}
