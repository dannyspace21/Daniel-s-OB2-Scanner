package com.daniel.carscanner.obd

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/*
 * WHAT THIS FILE DOES
 * Defines an "active test" (switching a part on to prove it works) and saves
 * your list of tests to the phone so they're still there next time.
 */

/**
 * A user-defined actuator test, e.g. "Cooling fan on". Commands are vehicle-specific,
 * so the app ships with none pre-filled. Add them once you've confirmed them for your car.
 */
data class ActiveTest(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val notes: String = "",
    val header: String = "7E0",          // which module to talk to (7E0 = engine on most CAN cars)
    val startCommands: List<String>,
    val stopCommands: List<String>,
    val keepAlive: String = "3E00",      // sent every 2 s while running; blank = none
    val maxSeconds: Int = 10,
)

/** Saves and loads your tests as a small JSON file in the app's private storage. */
class ActiveTestStore(private val file: File) {
    fun load(): List<ActiveTest> = try {
        if (!file.exists()) emptyList()
        else JSONArray(file.readText()).let { arr -> (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) } }
    } catch (_: Exception) { emptyList() }

    fun save(list: List<ActiveTest>) {
        file.writeText(JSONArray(list.map(::toJson)).toString(2))
    }

    private fun toJson(t: ActiveTest) = JSONObject()
        .put("id", t.id).put("name", t.name).put("notes", t.notes).put("header", t.header)
        .put("start", JSONArray(t.startCommands)).put("stop", JSONArray(t.stopCommands))
        .put("keepAlive", t.keepAlive).put("maxSeconds", t.maxSeconds)

    private fun fromJson(o: JSONObject): ActiveTest {
        fun list(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return ActiveTest(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Unnamed test"),
            notes = o.optString("notes"),
            header = o.optString("header", "7E0"),
            startCommands = list("start"),
            stopCommands = list("stop"),
            keepAlive = o.optString("keepAlive", ""),
            maxSeconds = o.optInt("maxSeconds", 10),
        )
    }
}
