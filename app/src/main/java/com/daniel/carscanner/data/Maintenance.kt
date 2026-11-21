package com.daniel.carscanner.data

import com.daniel.carscanner.obd.Level
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/*
 * WHAT THIS FILE DOES
 * A simple service tracker per car: oil changes, filters, fluids...
 * Each item is due every so many miles and/or months, whichever comes first.
 * Tap "Done" when you do it; the app records today's date and mileage.
 *
 * The starting intervals are typical values. Edit them to match your owner's
 * manual; your car's maker knows best.
 */
data class MaintItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val everyMiles: Int,      // 0 = not based on mileage
    val everyMonths: Int,     // 0 = not based on time
    val lastDay: Long? = null,   // day it was last done (days since 1970), null = never recorded
    val lastMiles: Int? = null,
)

/** How an item stands right now, for the colored dot and the line under its name. */
data class MaintStatus(val level: Level, val text: String)

object Maintenance {

    /** Typical starting list for a gasoline car. */
    fun defaults(): List<MaintItem> = listOf(
        MaintItem(name = "Oil and filter", everyMiles = 5_000, everyMonths = 6),
        MaintItem(name = "Tire rotation", everyMiles = 5_000, everyMonths = 6),
        MaintItem(name = "Engine air filter", everyMiles = 15_000, everyMonths = 12),
        MaintItem(name = "Cabin air filter", everyMiles = 15_000, everyMonths = 12),
        MaintItem(name = "Brake fluid", everyMiles = 30_000, everyMonths = 36),
        MaintItem(name = "Spark plugs", everyMiles = 60_000, everyMonths = 0),
        MaintItem(name = "Coolant", everyMiles = 60_000, everyMonths = 60),
        MaintItem(name = "Transmission fluid", everyMiles = 60_000, everyMonths = 0),
        MaintItem(name = "Battery check", everyMiles = 0, everyMonths = 12),
    )

    /**
     * Works out if an item is fine (green), due soon (amber) or overdue (red).
     * today = days since 1970, miles = current odometer if known.
     */
    fun status(item: MaintItem, today: Long, miles: Int?): MaintStatus {
        if (item.lastDay == null && item.lastMiles == null) return MaintStatus(Level.Unknown, "Not recorded yet. Tap Done after you do it.")
        val parts = mutableListOf<String>()
        var worst = Level.Good
        fun bump(l: Level) { if (l.severity > worst.severity) worst = l }

        if (item.everyMiles > 0 && item.lastMiles != null && miles != null) {
            val left = item.lastMiles + item.everyMiles - miles
            parts += if (left < 0) "%,d mi overdue".format(-left) else "%,d mi left".format(left)
            bump(when { left < 0 -> Level.Problem; left < item.everyMiles / 10 -> Level.Watch; else -> Level.Good })
        }
        if (item.everyMonths > 0 && item.lastDay != null) {
            val dueDay = item.lastDay + item.everyMonths * 30L
            val left = dueDay - today
            parts += when {
                left < -30 -> "${-left / 30} mo overdue"
                left < 0 -> "${-left} days overdue"
                left < 30 -> "$left days left"
                else -> "${left / 30} mo left"
            }
            bump(when { left < 0 -> Level.Problem; left < 21 -> Level.Watch; else -> Level.Good })
        }
        if (parts.isEmpty()) return MaintStatus(Level.Unknown, "Enter your mileage to track this one.")
        return MaintStatus(worst, parts.joinToString(", "))
    }

    fun toJson(list: List<MaintItem>): String = JSONArray(list.map {
        JSONObject().put("id", it.id).put("name", it.name).put("everyMiles", it.everyMiles).put("everyMonths", it.everyMonths)
            .put("lastDay", it.lastDay ?: -1L).put("lastMiles", it.lastMiles ?: -1)
    }).toString()

    fun fromJson(s: String?): List<MaintItem>? = try {
        if (s.isNullOrBlank()) null else {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                MaintItem(
                    id = o.optString("id"), name = o.optString("name"),
                    everyMiles = o.optInt("everyMiles", 0), everyMonths = o.optInt("everyMonths", 0),
                    lastDay = o.optLong("lastDay", -1L).takeIf { it >= 0 }, lastMiles = o.optInt("lastMiles", -1).takeIf { it >= 0 },
                )
            }
        }
    } catch (_: Exception) { null }
}
