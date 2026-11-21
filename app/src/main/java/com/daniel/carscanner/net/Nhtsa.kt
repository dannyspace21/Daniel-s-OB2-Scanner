package com.daniel.carscanner.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/*
 * WHAT THIS FILE DOES
 * Talks to the U.S. government's free vehicle databases (NHTSA):
 *   1. decodeVin()  turns your VIN into year, make, model, trim, body style,
 *                   doors, engine and more
 *   2. recalls()    lists open safety recalls for that year, make and model
 * No account or key is needed. Only the VIN (or year/make/model) is sent.
 *
 * These functions do network work, so they must be called on a background
 * thread (the ViewModel does that with Dispatchers.IO).
 */

/** The shapes the car drawing on Home knows how to draw. */
enum class BodyShape { Coupe, Sedan, Hatchback, Wagon, SUV, Van, Pickup, Motorcycle }

/** Pickup cab sizes (changes the drawing and door count). */
enum class CabSize { Regular, Extended, Crew }

/** Everything NHTSA tells us about the car. Blank text means "not reported". */
data class VehicleInfo(
    val year: String,
    val make: String,
    val model: String,
    val trim: String,
    val series: String,
    val bodyClass: String,
    val doors: Int?,
    val driveType: String,
    val engine: String,
    val fuel: String,
    val vehicleType: String,
    val cabType: String,
) {
    /** "Lexus" instead of NHTSA's "LEXUS". Short brand acronyms stay uppercase. */
    val makeName: String
        get() = if (make.uppercase() in setOf("BMW", "GMC", "RAM", "AMC", "MG")) make.uppercase()
        else make.lowercase().split(" ", "-").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** "2017 Lexus RC". Used as the title on Home and in searches. */
    val displayName: String
        get() = listOf(year, makeName, model).filter { it.isNotBlank() }.joinToString(" ")

    /** Which drawing to use, worked out from NHTSA's body class and vehicle type. */
    val shape: BodyShape
        get() {
            val b = bodyClass.lowercase()
            val t = vehicleType.lowercase()
            return when {
                "motorcycle" in t || "motorcycle" in b -> BodyShape.Motorcycle
                "pickup" in b -> BodyShape.Pickup
                "coupe" in b || "convertible" in b || "roadster" in b -> BodyShape.Coupe
                "hatchback" in b || "liftback" in b -> BodyShape.Hatchback
                "wagon" in b -> BodyShape.Wagon
                "sport utility" in b || "crossover" in b || "suv" in b || "multi-purpose" in b -> BodyShape.SUV
                "van" in b -> BodyShape.Van
                "sedan" in b || "saloon" in b -> BodyShape.Sedan
                "truck" in t -> BodyShape.Pickup
                "multipurpose" in t -> BodyShape.SUV
                else -> BodyShape.Sedan
            }
        }

    /** Pickup cab size from NHTSA's cab type text. */
    val cab: CabSize
        get() {
            val c = cabType.lowercase()
            return when {
                "crew" in c -> CabSize.Crew
                listOf("extra", "super", "quad", "double", "king", "extended").any { it in c } -> CabSize.Extended
                else -> CabSize.Regular
            }
        }

    /** Doors to draw. Uses NHTSA's number when it has one, otherwise a sensible default. */
    val doorCount: Int
        get() = doors?.coerceIn(0, 5)?.let { if (it == 5 || it == 3) it - 1 else it } ?: when (shape) {
            BodyShape.Coupe -> 2
            BodyShape.Motorcycle -> 0
            BodyShape.Pickup -> if (cab == CabSize.Crew) 4 else 2
            else -> 4
        }

    fun toJson(): String = JSONObject()
        .put("year", year).put("make", make).put("model", model).put("trim", trim).put("series", series)
        .put("bodyClass", bodyClass).put("doors", doors ?: -1).put("driveType", driveType).put("engine", engine)
        .put("fuel", fuel).put("vehicleType", vehicleType).put("cabType", cabType).toString()

    companion object {
        fun fromJson(s: String): VehicleInfo? = try {
            val o = JSONObject(s)
            VehicleInfo(
                o.optString("year"), o.optString("make"), o.optString("model"), o.optString("trim"),
                o.optString("series"), o.optString("bodyClass"), o.optInt("doors", -1).takeIf { it >= 0 },
                o.optString("driveType"), o.optString("engine"), o.optString("fuel"),
                o.optString("vehicleType"), o.optString("cabType"),
            )
        } catch (_: Exception) { null }
    }
}

/** One safety recall from NHTSA. */
data class Recall(
    val campaign: String,
    val component: String,
    val summary: String,
    val consequence: String,
    val remedy: String,
    val date: String,
    val parkIt: Boolean,
) {
    /** The recall's page on nhtsa.gov. */
    val url: String get() = "https://www.nhtsa.gov/recalls?nhtsaId=$campaign"

    companion object {
        fun listToJson(list: List<Recall>): String = JSONArray(list.map {
            JSONObject().put("campaign", it.campaign).put("component", it.component).put("summary", it.summary)
                .put("consequence", it.consequence).put("remedy", it.remedy).put("date", it.date).put("parkIt", it.parkIt)
        }).toString()

        fun listFromJson(s: String): List<Recall> = try {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Recall(o.optString("campaign"), o.optString("component"), o.optString("summary"),
                    o.optString("consequence"), o.optString("remedy"), o.optString("date"), o.optBoolean("parkIt"))
            }
        } catch (_: Exception) { emptyList() }
    }
}

object Nhtsa {

    /** Downloads a web address and reads the reply as JSON. Throws IOException on any failure. */
    private fun getJson(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "DanielsCarMechanicScanner/2.2.2")
        try {
            if (c.responseCode !in 200..299) throw IOException("NHTSA answered with error ${c.responseCode}")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return try { JSONObject(text) } catch (e: Exception) { throw IOException("NHTSA sent an unreadable reply", e) }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Turns a VIN into vehicle details. */
    fun decodeVin(vin: String): VehicleInfo {
        val json = getJson("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/${enc(vin)}?format=json")
        val r = json.optJSONArray("Results")?.optJSONObject(0) ?: throw IOException("NHTSA returned no details for this VIN")
        fun f(key: String) = r.optString(key).trim().let { if (it == "null" || it == "Not Applicable") "" else it }
        if (f("Make").isBlank()) throw IOException("NHTSA couldn't decode this VIN")
        val liters = f("DisplacementL").toDoubleOrNull()
        val cyl = f("EngineCylinders")
        val engine = listOfNotNull(
            liters?.let { "%.1fL".format(it) },
            cyl.takeIf { it.isNotBlank() }?.let { "$it-cylinder" },
        ).joinToString(" ")
        return VehicleInfo(
            year = f("ModelYear"), make = f("Make"), model = f("Model"), trim = f("Trim"), series = f("Series"),
            bodyClass = f("BodyClass"), doors = f("Doors").toIntOrNull(), driveType = f("DriveType"),
            engine = engine, fuel = f("FuelTypePrimary"), vehicleType = f("VehicleType"), cabType = f("BodyCabType"),
        )
    }

    /** Lists safety recalls for the car's year, make and model, newest first. */
    fun recalls(info: VehicleInfo): List<Recall> {
        if (info.make.isBlank() || info.model.isBlank() || info.year.isBlank()) return emptyList()
        val json = getJson(
            "https://api.nhtsa.gov/recalls/recallsByVehicle?make=${enc(info.make)}&model=${enc(info.model)}&modelYear=${enc(info.year)}"
        )
        val a = json.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            Recall(
                campaign = o.optString("NHTSACampaignNumber"),
                component = o.optString("Component"),
                summary = o.optString("Summary"),
                consequence = o.optString("Consequence"),
                remedy = o.optString("Remedy"),
                date = o.optString("ReportReceivedDate"),
                parkIt = o.optBoolean("parkIt", false),
            )
        }.distinctBy { it.campaign }
    }
}
