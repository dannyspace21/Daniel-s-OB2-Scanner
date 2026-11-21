package com.daniel.carscanner.data

import com.daniel.carscanner.obd.Level
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * WHAT THIS FILE DOES
 * Keeps a record of every code read, health check and module scan, per car,
 * so you can see what changed over time ("this code came back after I cleared it")
 * and share a clean report with a mechanic or a forum.
 */
data class HistoryEntry(
    val vin: String,
    val time: Long,
    val kind: String,          // "Codes", "Health check", "Module scan"
    val title: String,         // one-line summary
    val level: Level,          // worst result, for the dot
    val lines: List<String>,   // details
)

class HistoryStore(private val file: File) {
    fun load(): List<HistoryEntry> = try {
        if (!file.exists()) emptyList() else {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val ls = o.optJSONArray("lines")
                HistoryEntry(
                    vin = o.optString("vin"), time = o.optLong("time", 0L), kind = o.optString("kind"),
                    title = o.optString("title"),
                    level = runCatching { Level.valueOf(o.optString("level")) }.getOrDefault(Level.Unknown),
                    lines = if (ls == null) emptyList() else (0 until ls.length()).map { ls.getString(it) },
                )
            }
        }
    } catch (_: Exception) { emptyList() }

    /** Saves, keeping the newest 300 entries. */
    fun save(list: List<HistoryEntry>) {
        file.writeText(JSONArray(list.takeLast(300).map {
            JSONObject().put("vin", it.vin).put("time", it.time).put("kind", it.kind).put("title", it.title)
                .put("level", it.level.name).put("lines", JSONArray(it.lines))
        }).toString())
    }
}

object Reports {
    private val fmt = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US)
    fun date(t: Long): String = fmt.format(Date(t))

    /** A plain-text report you can text, email or post. */
    fun build(carName: String, vin: String, entries: List<HistoryEntry>): String = buildString {
        appendLine("Vehicle report: $carName")
        appendLine("VIN: $vin")
        appendLine()
        entries.sortedByDescending { it.time }.forEach { e ->
            appendLine("${date(e.time)}  ${e.kind}: ${e.title}")
            e.lines.forEach { appendLine("  - $it") }
            appendLine()
        }
        append("Made with Daniel's Car Mechanic Scanner")
    }
}
