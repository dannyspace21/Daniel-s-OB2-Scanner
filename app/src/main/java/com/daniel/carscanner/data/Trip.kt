package com.daniel.carscanner.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * WHAT THIS FILE DOES
 * Trip and fuel economy tracking.
 *
 * About once a second the app reads your speed and how much fuel the engine is
 * using, then adds up distance and fuel to get miles per gallon. Fuel use comes
 * from the car's fuel-rate reading when it has one, otherwise it's worked out
 * from the mass air flow sensor (air ÷ 14.7 = fuel, for gasoline).
 * Expect it to be within about 10% of a fill-up calculation.
 */
object FuelMath {
    /** Grams of gasoline in a gallon (about 737 g per liter × 3.785 liters). */
    private const val GRAMS_PER_GALLON = 2_790.0

    /** Gallons per hour from mass air flow (grams of air per second), for a gasoline engine. */
    fun gphFromMaf(gramsAirPerSec: Double): Double = gramsAirPerSec / 14.7 * 3_600.0 / GRAMS_PER_GALLON
}

/** Adds up a trip as readings arrive. */
class TripMeter(val startedAt: Long) {
    var miles = 0.0; private set
    var gallons = 0.0; private set
    var seconds = 0.0; private set
    var mph = 0.0; private set
    var gph: Double? = null; private set
    private var lastTime: Long? = null
    private var lastMph = 0.0
    private var lastGph: Double? = null

    /** One reading: speed now, fuel use now (null if the car can't tell us), and the time. */
    fun feed(speedMph: Double, fuelGph: Double?, now: Long) {
        lastTime?.let { t0 ->
            // Gaps over 5 s (signal dropped) count as 5 s so one hiccup can't add phantom miles
            val dt = ((now - t0) / 1000.0).coerceIn(0.0, 5.0)
            miles += (speedMph + lastMph) / 2 * dt / 3_600
            seconds += dt
            val g0 = lastGph
            if (fuelGph != null && g0 != null) gallons += (fuelGph + g0) / 2 * dt / 3_600
        }
        lastTime = now; lastMph = speedMph; lastGph = fuelGph
        mph = speedMph; gph = fuelGph
    }

    /** Average MPG so far, once enough fuel has been counted to be meaningful. */
    val averageMpg: Double? get() = if (gallons > 0.002 && miles > 0.05) miles / gallons else null

    /** MPG right now. Null when stopped (idling has no MPG). Capped at 99 for coasting. */
    val instantMpg: Double? get() { val g = gph ?: return null; return if (mph < 1 || g <= 0.01) null else (mph / g).coerceAtMost(99.0) }

    val averageMph: Double get() = if (seconds > 0) miles / (seconds / 3_600) else 0.0

    fun toRecord() = TripRecord(startedAt, seconds.toLong(), miles, gallons.takeIf { it > 0.002 })
}

/** A finished trip, saved per car. */
data class TripRecord(val startedAt: Long, val seconds: Long, val miles: Double, val gallons: Double?) {
    val mpg: Double? get() = gallons?.let { if (it > 0 && miles > 0.05) miles / it else null }

    companion object {
        fun listToJson(list: List<TripRecord>): String = JSONArray(list.map {
            JSONObject().put("start", it.startedAt).put("seconds", it.seconds).put("miles", it.miles).put("gallons", it.gallons ?: -1.0)
        }).toString()

        fun listFromJson(s: String?): List<TripRecord> = try {
            if (s.isNullOrBlank()) emptyList() else {
                val a = JSONArray(s)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    TripRecord(o.optLong("start", 0L), o.optLong("seconds", 0L), o.optDouble("miles", 0.0),
                        o.optDouble("gallons", -1.0).takeIf { it >= 0 })
                }
            }
        } catch (_: Exception) { emptyList() }
    }
}
