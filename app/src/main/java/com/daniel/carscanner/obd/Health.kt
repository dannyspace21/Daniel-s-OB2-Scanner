package com.daniel.carscanner.obd

/*
 * WHAT THIS FILE DOES
 * This is the "is this normal?" brain of the app.
 *
 * 1. levelFor() colors a single live reading green / yellow / red on the Live screen.
 * 2. evaluate() looks at ~15 seconds of recorded readings from a health check and
 *    writes plain-English findings, each with a list of likely causes.
 *
 * The ranges are typical values for gasoline engines, not factory specs for any
 * one car. Treat yellow and red as "look here first", not a final verdict.
 */

/** Traffic-light result. severity is used for sorting (worst first). */
enum class Level(val severity: Int) { Good(1), Watch(2), Problem(3), Unknown(0) }

/**
 * One result from the health check.
 * system  which part of the car (Charging, Cooling, Fuel system...)
 * title   short headline, e.g. "Bank 1 running lean"
 * reading the numbers that led to it
 * meaning what it means in plain English
 * causes  the most likely reasons, most common first
 */
data class Finding(
    val system: String,
    val title: String,
    val level: Level,
    val reading: String,
    val meaning: String,
    val causes: List<String> = emptyList(),
)

/**
 * What the engine was doing during the measurement. Many "normal" ranges
 * only apply at warm idle, so we need to know this first.
 */
data class EngineState(val running: Boolean, val warm: Boolean, val idling: Boolean) {
    companion object {
        fun from(values: Map<Int, Double>): EngineState {
            val rpm = values[0x0C] ?: 0.0
            val running = rpm > 300
            val warm = (values[0x05] ?: 0.0) >= 165
            val idling = running && (values[0x0D] ?: 0.0) < 1 && rpm < 1100
            return EngineState(running, warm, idling)
        }
    }
}

object HealthCheck {

    /** System names, in the order the Home screen lists them. */
    const val CHARGING = "Battery & charging"
    const val COOLING = "Cooling"
    const val FUEL = "Fuel system"
    const val O2 = "Oxygen sensors & converter"
    const val ENGINE = "Engine"
    const val CODES = "Trouble codes"
    val systems = listOf(CHARGING, COOLING, FUEL, O2, ENGINE, CODES)

    /** Sensors sampled fast during the check so we can count how often they switch. */
    val o2Pids = listOf(0x14, 0x15, 0x18, 0x19, 0x24, 0x28, 0x34, 0x38)

    /** General sensors sampled a few times during the check. */
    val generalPids = listOf(0x0C, 0x0D, 0x05, 0x5C, 0x42, 0x04, 0x0B, 0x0E, 0x0F, 0x10, 0x06, 0x07, 0x08, 0x09, 0x1F)

    // Likely causes, reused by several rules. Most common first.
    private val leanCauses = listOf(
        "Vacuum leak (cracked hose, intake gasket, PCV valve or hose)",
        "Dirty or failing mass air flow (MAF) sensor",
        "Weak fuel pump or clogged fuel filter",
        "Clogged or weak fuel injectors",
        "Exhaust leak ahead of the oxygen sensor",
    )
    private val richCauses = listOf(
        "Leaking fuel injector",
        "Fuel pressure too high (faulty regulator)",
        "Faulty upstream oxygen or air-fuel sensor",
        "EVAP purge valve stuck open",
        "Very dirty air filter",
    )

    // ------------------------------------------------------------------
    // 1. Instant color for one gauge on the Live screen
    // ------------------------------------------------------------------

    /** Returns green / yellow / red for a single reading right now, or Unknown if we can't judge it. */
    fun levelFor(code: Int, v: Double, s: EngineState): Level = when (code) {
        0x05 -> when { v <= -39 -> Level.Problem; v > 235 -> Level.Problem; v > 222 -> Level.Watch; else -> Level.Good }
        0x5C -> when { v > 265 -> Level.Problem; v > 250 -> Level.Watch; else -> Level.Good }
        0x42 -> if (s.running) when {
            v < 13.0 || v > 15.2 -> Level.Problem
            v < 13.4 || v > 14.9 -> Level.Watch
            else -> Level.Good
        } else when { v < 12.0 -> Level.Problem; v < 12.4 -> Level.Watch; else -> Level.Good }
        0x06, 0x08 -> if (!s.warm) Level.Unknown else when { kotlin.math.abs(v) > 20 -> Level.Problem; kotlin.math.abs(v) > 10 -> Level.Watch; else -> Level.Good }
        0x07, 0x09 -> if (!s.warm) Level.Unknown else when { kotlin.math.abs(v) > 15 -> Level.Problem; kotlin.math.abs(v) > 10 -> Level.Watch; else -> Level.Good }
        0x0C -> if (!(s.idling && s.warm)) Level.Unknown else when { v < 500 -> Level.Problem; v > 950 -> Level.Watch; else -> Level.Good }
        0x0B -> if (!(s.idling && s.warm)) Level.Unknown else if (v > 7) Level.Watch else Level.Good
        0x0E -> if (!(s.idling && s.warm)) Level.Unknown else if (v < 0) Level.Watch else Level.Good
        0x0F -> when { v <= -38 -> Level.Problem; v > 180 -> Level.Watch; else -> Level.Good }
        0x10 -> if (s.running && v < 1.0) Level.Problem else Level.Good
        0x24, 0x28, 0x34, 0x38 -> if (!(s.idling && s.warm)) Level.Unknown else when {
            v in 0.97..1.03 -> Level.Good
            v in 0.94..1.06 -> Level.Watch
            else -> Level.Problem
        }
        0x2F -> if (v < 10) Level.Watch else Level.Good
        else -> Level.Unknown
    }

    // ------------------------------------------------------------------
    // 2. Full health check from recorded samples
    // ------------------------------------------------------------------

    /**
     * samples   every reading recorded per PID during the check
     * o2Seconds how long the fast oxygen-sensor recording lasted
     */
    fun evaluate(samples: Map<Int, List<Double>>, o2Seconds: Double): List<Finding> {
        val out = mutableListOf<Finding>()
        fun avg(c: Int) = samples[c]?.takeIf { it.isNotEmpty() }?.average()
        fun lo(c: Int) = samples[c]?.minOrNull()
        fun hi(c: Int) = samples[c]?.maxOrNull()

        val state = EngineState.from(
            listOfNotNull(
                avg(0x0C)?.let { 0x0C to it }, avg(0x05)?.let { 0x05 to it }, avg(0x0D)?.let { 0x0D to it },
            ).toMap()
        )

        avg(0x42)?.let { out += charging(it, state.running) }
        cooling(avg(0x05), avg(0x5C), avg(0x1F), state)?.let { out += it }
        avg(0x0F)?.let { if (it <= -38) out += Finding(ENGINE, "Intake air temp sensor reads −40 °F", Level.Problem,
            "%.0f °F".format(it), "That reading means the computer sees no signal from the sensor.",
            listOf("Sensor unplugged or wire broken", "Failed sensor (it's built into the MAF on many cars)")) }

        // Most engine checks only mean something with the engine running and warm.
        when {
            !state.running -> out += Finding(ENGINE, "Engine is off", Level.Unknown, "",
                "Only the battery and codes were checked. Start the engine and let it idle until fully warm (about 10 minutes), then run the check again.")
            !state.warm -> out += Finding(ENGINE, "Engine still warming up", Level.Unknown,
                avg(0x05)?.let { "Coolant %.0f °F".format(it) } ?: "",
                "Fuel trims and oxygen sensors are only judged once the coolant passes 165 °F. Run the check again when warm.")
            else -> {
                out += fuelTrims(samples)
                out += oxygenSensors(samples, o2Seconds)
                if (state.idling) out += idle(samples)
                avg(0x10)?.let { if (it < 1.0) out += Finding(ENGINE, "Air flow sensor reads near zero", Level.Problem,
                    "%.1f g/s".format(it), "The engine is running, so air must be flowing. The sensor isn't reporting it.",
                    listOf("MAF sensor unplugged", "Failed MAF sensor", "Damaged wiring to the MAF")) }
            }
        }
        return out.sortedByDescending { it.level.severity }
    }

    /** Battery and alternator. */
    private fun charging(v: Double, running: Boolean): Finding {
        val r = "%.2f V".format(v)
        return if (running) when {
            v < 13.0 -> Finding(CHARGING, "Alternator isn't charging", Level.Problem, r,
                "With the engine running the voltage should be 13.5–14.8 V. The battery is draining instead of charging.",
                listOf("Failing alternator", "Loose or worn drive belt", "Corroded or loose battery cables", "Blown alternator fuse"))
            v < 13.4 -> Finding(CHARGING, "Charging voltage a little low", Level.Watch, r,
                "Slightly below the usual 13.5–14.8 V. Can be normal with lots of accessories on.",
                listOf("Heavy electrical load (lights, rear defrost, blower)", "Aging alternator", "Corroded battery terminals"))
            v > 15.2 -> Finding(CHARGING, "Overcharging", Level.Problem, r,
                "Too much voltage can damage the battery and electronics.",
                listOf("Faulty voltage regulator (inside the alternator)", "Bad ground connection"))
            v > 14.9 -> Finding(CHARGING, "Charging voltage a little high", Level.Watch, r,
                "Slightly above normal. Common for a few minutes after a cold start.",
                listOf("Normal right after starting", "Regulator starting to fail"))
            else -> Finding(CHARGING, "Charging normally", Level.Good, r, "The alternator is keeping the battery charged.")
        } else when {
            v < 12.0 -> Finding(CHARGING, "Battery is low", Level.Problem, r,
                "A healthy, charged battery rests at 12.4–12.9 V. Below 12.0 V it may not start the car.",
                listOf("Battery worn out", "Something draining the battery while parked", "Charging system not recharging while driving"))
            v < 12.4 -> Finding(CHARGING, "Battery partly discharged", Level.Watch, r,
                "Not flat, but not fully charged. A long drive or a charger should bring it up.",
                listOf("Lots of short trips", "Battery getting old", "Small parasitic drain"))
            else -> Finding(CHARGING, "Battery charged", Level.Good, r, "Resting voltage looks healthy.")
        }
    }

    /** Engine temperature and thermostat. */
    private fun cooling(coolant: Double?, oil: Double?, runSec: Double?, s: EngineState): Finding? {
        val c = coolant ?: return null
        val r = "Coolant %.0f °F".format(c) + (oil?.let { ", oil %.0f °F".format(it) } ?: "")
        val hotCauses = listOf("Low coolant or a leak", "Cooling fan not turning on", "Thermostat stuck closed",
            "Failing water pump", "Clogged radiator")
        return when {
            c <= -39 -> Finding(COOLING, "Coolant sensor reads −40 °F", Level.Problem, r,
                "That reading means the computer sees no signal from the sensor.", listOf("Sensor unplugged or wire broken", "Failed sensor"))
            c > 235 -> Finding(COOLING, "Engine overheating", Level.Problem, r,
                "Stop driving and let it cool. Overheating can warp the cylinder heads.", hotCauses)
            c > 222 || (oil ?: 0.0) > 260 -> Finding(COOLING, "Running hot", Level.Watch, r,
                "Above the usual 190–220 °F. Watch it, especially in traffic.", hotCauses)
            s.running && (runSec ?: 0.0) > 900 && c < 160 -> Finding(COOLING, "Engine slow to warm up", Level.Watch, r,
                "After 15 minutes it should be near 190 °F. Running cold hurts mileage and can set P0128.",
                listOf("Thermostat stuck open", "Faulty coolant temperature sensor"))
            s.warm -> Finding(COOLING, "Normal operating temperature", Level.Good, r, "The cooling system is holding a normal temperature.")
            else -> Finding(COOLING, "Warming up", Level.Unknown, r, "Not warm yet, so we can't judge the thermostat.")
        }
    }

    /** Fuel trims: is the engine running lean or rich, and on which side? */
    private fun fuelTrims(samples: Map<Int, List<Double>>): List<Finding> {
        val out = mutableListOf<Finding>()
        val totals = mutableMapOf<Int, Double>()
        for ((bank, st, lt) in listOf(Triple(1, 0x06, 0x07), Triple(2, 0x08, 0x09))) {
            val s = samples[st]?.takeIf { it.isNotEmpty() }?.average()
            val l = samples[lt]?.takeIf { it.isNotEmpty() }?.average()
            if (s == null && l == null) continue
            val total = (s ?: 0.0) + (l ?: 0.0)   // short + long = total correction
            totals[bank] = total
            val r = "Short %+.1f%%, long %+.1f%%, total %+.1f%%".format(s ?: 0.0, l ?: 0.0, total)
            out += when {
                total > 20 -> Finding(FUEL, "Bank $bank running lean", Level.Problem, r,
                    "The computer is adding a lot of extra fuel because it sees too much air.", leanCauses)
                total > 10 -> Finding(FUEL, "Bank $bank slightly lean", Level.Watch, r,
                    "The computer is adding more fuel than usual. Often an early vacuum leak.", leanCauses)
                total < -20 -> Finding(FUEL, "Bank $bank running rich", Level.Problem, r,
                    "The computer is pulling a lot of fuel because it sees too much fuel.", richCauses)
                total < -10 -> Finding(FUEL, "Bank $bank slightly rich", Level.Watch, r,
                    "The computer is removing more fuel than usual.", richCauses)
                else -> Finding(FUEL, "Bank $bank mixture balanced", Level.Good, r, "Fuel corrections are within ±10%.")
            }
        }
        // Comparing the two sides of a V6/V8 narrows the cause a lot.
        val b1 = totals[1]; val b2 = totals[2]
        if (b1 != null && b2 != null) {
            if (kotlin.math.abs(b1 - b2) > 10) {
                out += Finding(FUEL, "Banks don't match", Level.Watch, "Bank 1 %+.1f%%, bank 2 %+.1f%%".format(b1, b2),
                    "Only one side is off, so the cause is probably on that side of the engine, not something they share.",
                    listOf("Vacuum leak on one side of the intake", "Injector problem on one bank",
                        "Oxygen or air-fuel sensor on one bank", "Exhaust leak on one side"))
            } else if (b1 > 10 && b2 > 10) {
                out += Finding(FUEL, "Both banks lean together", Level.Watch, "Bank 1 %+.1f%%, bank 2 %+.1f%%".format(b1, b2),
                    "Both sides are lean by the same amount, which points to something they share.",
                    listOf("Dirty or failing MAF sensor", "Low fuel pressure (pump or filter)", "Large vacuum leak (PCV, brake booster hose)"))
            }
        }
        return out
    }

    /** Counts how many times a narrowband O2 signal crosses 0.45 V (rich/lean switches). */
    private fun crossings(v: List<Double>): Int =
        v.zipWithNext().count { (a, b) -> (a < 0.45) != (b < 0.45) }

    /** Upstream sensors, downstream sensors and the catalytic converter. */
    private fun oxygenSensors(samples: Map<Int, List<Double>>, seconds: Double): List<Finding> {
        val out = mutableListOf<Finding>()
        val secs = seconds.coerceAtLeast(1.0)
        for (bank in 1..2) {
            val upNarrow = samples[if (bank == 1) 0x14 else 0x18]?.takeIf { it.size >= 5 }
            val upWide = (samples[if (bank == 1) 0x24 else 0x28] ?: samples[if (bank == 1) 0x34 else 0x38])?.takeIf { it.size >= 5 }
            val down = samples[if (bank == 1) 0x15 else 0x19]?.takeIf { it.size >= 5 }
            var upSwitches: Int? = null

            if (upNarrow != null) {
                val sw = crossings(upNarrow); upSwitches = sw
                val r = "%.2f–%.2f V, %d switches in %.0f s".format(upNarrow.min(), upNarrow.max(), sw, secs)
                out += when {
                    upNarrow.max() < 0.45 -> Finding(O2, "Bank $bank upstream O2 stuck lean", Level.Problem, r,
                        "The sensor never reports rich. Either the engine is really lean or the sensor is dead.",
                        listOf("Vacuum or exhaust leak", "Low fuel pressure", "Failed oxygen sensor"))
                    upNarrow.min() > 0.45 -> Finding(O2, "Bank $bank upstream O2 stuck rich", Level.Problem, r,
                        "The sensor never reports lean. Either the engine is really rich or the sensor is contaminated.",
                        listOf("Leaking injector", "Fuel pressure too high", "Sensor contaminated by oil or coolant"))
                    upNarrow.max() - upNarrow.min() < 0.3 -> Finding(O2, "Bank $bank upstream O2 is lazy", Level.Watch, r,
                        "A healthy sensor swings widely. This one barely moves.",
                        listOf("Aging sensor (common past 100k miles)", "Sensor heater not working"))
                    sw / secs * 10 < 3 -> Finding(O2, "Bank $bank upstream O2 switching slowly", Level.Watch, r,
                        "A healthy warm sensor switches several times every 10 seconds.",
                        listOf("Aging sensor", "Oil or coolant contamination"))
                    else -> Finding(O2, "Bank $bank upstream O2 working", Level.Good, r, "Switching rich and lean the way it should.")
                }
            }

            if (upWide != null) {
                val a = upWide.average()
                val r = "λ %.3f (range %.3f–%.3f)".format(a, upWide.min(), upWide.max())
                out += when {
                    upWide.max() - upWide.min() < 0.002 -> Finding(O2, "Bank $bank A/F sensor reading flat", Level.Watch, r,
                        "The reading isn't moving at all, which is unusual for a live sensor.",
                        listOf("Sensor heater failure", "Failed air-fuel sensor", "Wiring problem"))
                    a > 1.06 -> Finding(O2, "Bank $bank A/F sensor sees lean", Level.Problem, r, "The exhaust has extra oxygen.", leanCauses)
                    a < 0.94 -> Finding(O2, "Bank $bank A/F sensor sees rich", Level.Problem, r, "The exhaust has extra fuel.", richCauses)
                    a > 1.03 || a < 0.97 -> Finding(O2, "Bank $bank A/F slightly off", Level.Watch, r,
                        "Close to normal but drifting " + (if (a > 1) "lean." else "rich."), if (a > 1) leanCauses else richCauses)
                    else -> Finding(O2, "Bank $bank A/F sensor normal", Level.Good, r, "Mixture is right on target.")
                }
            }

            if (down != null) {
                val sw = crossings(down)
                val r = "Average %.2f V, %d switches in %.0f s".format(down.average(), sw, secs)
                val catWeak = (upSwitches != null && upSwitches >= 4 && sw >= upSwitches * 0.7) ||
                    (upSwitches == null && sw > 6)
                out += when {
                    down.average() < 0.2 -> Finding(O2, "Bank $bank downstream O2 reads low", Level.Watch, r,
                        "Usually sits around 0.5–0.8 V when warm.",
                        listOf("Exhaust leak near the sensor", "Engine running lean", "Failed sensor"))
                    catWeak -> Finding(O2, "Bank $bank catalytic converter may be worn", Level.Watch, r,
                        "The sensor after the converter is switching almost as much as the one before it, so the converter isn't smoothing the exhaust out.",
                        listOf("Catalytic converter worn out (often sets P0420 / P0430)", "Exhaust leak ahead of the downstream sensor",
                            "Aging downstream sensor"))
                    else -> Finding(O2, "Bank $bank catalytic converter looks healthy", Level.Good, r,
                        "The downstream sensor is steady, which means the converter is doing its job.")
                }
            }
        }
        return out
    }

    /** Idle quality, vacuum, timing and load. Only valid at warm idle. */
    private fun idle(samples: Map<Int, List<Double>>): List<Finding> {
        val out = mutableListOf<Finding>()
        samples[0x0C]?.takeIf { it.size >= 2 }?.let { rpm ->
            val a = rpm.average(); val spread = rpm.max() - rpm.min()
            val r = "%.0f rpm (varies by %.0f)".format(a, spread)
            out += when {
                a < 500 -> Finding(ENGINE, "Idle too low", Level.Problem, r, "Risk of stalling.",
                    listOf("Dirty throttle body", "Vacuum leak", "Misfire"))
                a > 950 -> Finding(ENGINE, "Idle high", Level.Watch, r, "Higher than a typical warm idle.",
                    listOf("Vacuum leak", "A/C or heavy electrical load on", "Dirty throttle body or idle relearn needed"))
                spread > 150 -> Finding(ENGINE, "Idle unsteady", Level.Watch, r, "A smooth idle varies by less than about 100 rpm.",
                    listOf("Misfire (spark plugs, ignition coils)", "Vacuum leak", "Dirty throttle body"))
                else -> Finding(ENGINE, "Idle smooth", Level.Good, r, "Steady and in the normal range.")
            }
        }
        samples[0x0B]?.takeIf { it.isNotEmpty() }?.average()?.let { if (it > 7) out += Finding(ENGINE, "Weak intake vacuum", Level.Watch,
            "%.1f psi".format(it), "At warm idle this is usually 3–5 psi.",
            listOf("Vacuum leak", "Ignition timing retarded", "Low compression or cam timing problem")) }
        samples[0x0E]?.takeIf { it.isNotEmpty() }?.average()?.let { if (it < 0) out += Finding(ENGINE, "Computer is pulling timing", Level.Watch,
            "%.1f°".format(it), "Negative timing at idle usually means the knock sensor is hearing something.",
            listOf("Lower octane fuel than the engine wants", "Carbon buildup", "Faulty knock sensor or wiring")) }
        samples[0x04]?.takeIf { it.isNotEmpty() }?.average()?.let { if (it > 45) out += Finding(ENGINE, "High load at idle", Level.Watch,
            "%.0f%%".format(it), "The engine is working harder than usual just to idle.",
            listOf("A/C or other accessory load", "Dragging accessory or alternator", "Vacuum leak")) }
        return out
    }
}
