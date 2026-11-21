package com.daniel.carscanner.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * WHAT THIS FILE DOES
 * Your garage: every car you've connected to or looked up by VIN.
 * Each car keeps its own details, paint color, recalls, tests, body signals,
 * maintenance and history. Tap a car in the Garage to switch to it.
 */
data class GarageCar(
    val vin: String,
    val nickname: String = "",
    val lastSeen: Long = 0L,        // last time the app connected to it (0 = only looked up)
    val odometerMiles: Int? = null, // from the car (newer cars) or typed in Maintenance
    val odometerFromCar: Boolean = false,
)

object GarageJson {
    fun toJson(list: List<GarageCar>): String = JSONArray(list.map {
        JSONObject().put("vin", it.vin).put("nickname", it.nickname).put("lastSeen", it.lastSeen)
            .put("odometer", it.odometerMiles ?: -1).put("odoFromCar", it.odometerFromCar)
    }).toString()

    fun fromJson(s: String?): List<GarageCar> = try {
        if (s.isNullOrBlank()) emptyList() else {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                GarageCar(
                    vin = o.optString("vin"), nickname = o.optString("nickname"),
                    lastSeen = o.optLong("lastSeen", 0L),
                    odometerMiles = o.optInt("odometer", -1).takeIf { it >= 0 },
                    odometerFromCar = o.optBoolean("odoFromCar", false),
                )
            }.filter { it.vin.length == 17 }
        }
    } catch (_: Exception) { emptyList() }
}
