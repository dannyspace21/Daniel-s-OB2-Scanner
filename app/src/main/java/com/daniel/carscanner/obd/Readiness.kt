package com.daniel.carscanner.obd

/*
 * WHAT THIS FILE DOES
 * Answers "will my car pass the emissions inspection?"
 *
 * Every car runs self-tests ("monitors") on its emissions parts while you drive.
 * Inspection stations check that the check engine light is off and that the
 * monitors have finished. After clearing codes or disconnecting the battery,
 * they all reset to "not ready" and need normal driving to complete again.
 * Most states allow one unfinished monitor on 2001 and newer cars.
 *
 * Reads OBD mode 01 PID 01: byte A = light and code count, byte B = always-on
 * monitors, bytes C and D = which other monitors exist and which are still running.
 */
data class Monitor(val name: String, val complete: Boolean)

data class Readiness(val milOn: Boolean, val codeCount: Int, val diesel: Boolean, val monitors: List<Monitor>) {
    val incomplete: Int get() = monitors.count { !it.complete }

    /** Plain-English verdict for the Codes screen. */
    val verdict: Pair<Level, String>
        get() = when {
            milOn -> Level.Problem to "Will fail: check engine light is on."
            incomplete == 0 -> Level.Good to "Ready for inspection."
            incomplete == 1 -> Level.Watch to "Likely ready. Most states allow one unfinished monitor on 2001+ cars."
            else -> Level.Problem to "Not ready yet: $incomplete monitors unfinished. Drive normally for a few days, mixing highway and town."
        }

    companion object {
        private val spark = listOf("Catalyst", "Heated catalyst", "Evaporative system", "Secondary air system",
            "A/C refrigerant", "Oxygen sensors", "Oxygen sensor heaters", "EGR / VVT system")
        private val diesel = listOf("NMHC catalyst", "NOx / SCR aftertreatment", "Reserved", "Boost pressure",
            "Reserved", "Exhaust gas sensor", "Particulate filter", "EGR / VVT system")

        /** Parses the four data bytes after "41 01". */
        fun parse(a: Int, b: Int, c: Int, d: Int): Readiness {
            val isDiesel = (b shr 3) and 1 == 1
            val list = mutableListOf<Monitor>()
            // Always-on monitors: supported bits 0-2, "not complete" bits 4-6
            listOf("Misfire", "Fuel system", "Components").forEachIndexed { i, name ->
                if ((b shr i) and 1 == 1) list += Monitor(name, (b shr (i + 4)) and 1 == 0)
            }
            val names = if (isDiesel) diesel else spark
            for (i in 0..7) {
                if ((c shr i) and 1 == 1 && names[i] != "Reserved") list += Monitor(names[i], (d shr i) and 1 == 0)
            }
            return Readiness(milOn = a and 0x80 != 0, codeCount = a and 0x7F, diesel = isDiesel, monitors = list)
        }
    }
}
