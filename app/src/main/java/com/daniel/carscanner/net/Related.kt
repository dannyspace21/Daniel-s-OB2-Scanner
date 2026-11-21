package com.daniel.carscanner.net

import java.net.URLEncoder

/*
 * WHAT THIS FILE DOES
 * Builds the "Related for your car" list you see when you tap a red or amber
 * result or a trouble code:
 *   - web search, repair videos, owner forums and service bulletins, each
 *     searching your exact year, make and model plus the problem
 *   - recalls for your car that touch the same system
 */

/** One tappable link in the Related list. */
data class RelatedLink(val kind: Kind, val title: String, val subtitle: String, val url: String) {
    enum class Kind { Web, Video, Forum, Bulletin }
}

object Related {

    // The biggest owner forum for each brand. Other brands search forums in general.
    private val forums = mapOf(
        "lexus" to "clublexus.com", "toyota" to "toyotanation.com", "kia" to "kia-forums.com",
        "hyundai" to "hyundai-forums.com", "ducati" to "ducati.ms", "bmw" to "bimmerforums.com",
        "tesla" to "teslamotorsclub.com", "volkswagen" to "vwvortex.com", "audi" to "audizine.com",
        "acura" to "acurazine.com", "mercedes-benz" to "mbworld.org", "subaru" to "nasioc.com",
    )

    private fun q(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
    private fun google(s: String) = "https://www.google.com/search?q=${q(s)}"

    /** The car's name for searches, e.g. "2017 Lexus RC". */
    fun carName(info: VehicleInfo?, fallback: String?): String = info?.displayName ?: fallback.orEmpty()

    /** Builds the search links for a problem on this car. */
    fun links(car: String, make: String?, topic: String): List<RelatedLink> {
        val query = "$car $topic".trim()
        val forum = forums[make?.lowercase()?.trim()]
        return listOf(
            RelatedLink(RelatedLink.Kind.Web, "Search the web", query, google(query)),
            RelatedLink(RelatedLink.Kind.Video, "Repair videos", "YouTube: $query",
                "https://www.youtube.com/results?search_query=${q("$query repair")}"),
            if (forum != null) RelatedLink(RelatedLink.Kind.Forum, "Owner forums", forum, google("site:$forum $query"))
            else RelatedLink(RelatedLink.Kind.Forum, "Owner forums", "Forum discussions", google("$query forum")),
            RelatedLink(RelatedLink.Kind.Bulletin, "Service bulletins", "Known fixes from the manufacturer", google("$query TSB technical service bulletin")),
        )
    }

    /** Recalls whose component matches any of the keywords. */
    fun matchingRecalls(recalls: List<Recall>, keywords: List<String>): List<Recall> =
        if (keywords.isEmpty()) emptyList()
        else recalls.filter { r -> keywords.any { k -> r.component.uppercase().contains(k) } }

    /** Recall component keywords for each health-check system. */
    fun keywordsForSystem(system: String): List<String> = when {
        system.startsWith("Battery") -> listOf("ELECTRICAL")
        system == "Cooling" -> listOf("COOLING")
        system == "Fuel system" -> listOf("FUEL", "ENGINE")
        system.startsWith("Oxygen") -> listOf("FUEL", "ENGINE", "EXHAUST")
        system == "Engine" -> listOf("ENGINE", "POWER TRAIN")
        else -> listOf("ENGINE", "ELECTRICAL")
    }

    /** Recall component keywords for a trouble code, based on its first letter. */
    fun keywordsForCode(code: String): List<String> = when (code.firstOrNull()) {
        'P' -> if (code.length >= 3 && code[1] == '0' && code[2] in '7'..'9') listOf("POWER TRAIN")
            else listOf("ENGINE", "FUEL", "POWER TRAIN")
        'C' -> listOf("BRAKES", "STEERING", "SUSPENSION", "STABILITY", "WHEELS", "TIRES")
        'B' -> listOf("AIR BAG", "SEAT BELT", "ELECTRICAL", "LIGHTING", "VISIBILITY", "LATCHES", "SEATS")
        'U' -> listOf("ELECTRICAL")
        else -> emptyList()
    }

    /** Recall component keywords for a live sensor. */
    fun keywordsForPid(code: Int): List<String> = when (code) {
        0x42 -> listOf("ELECTRICAL")
        0x05, 0x5C -> listOf("COOLING", "ENGINE")
        0x06, 0x07, 0x08, 0x09, 0x10, 0x14, 0x15, 0x18, 0x19, 0x24, 0x28, 0x34, 0x38 -> listOf("FUEL", "ENGINE")
        else -> listOf("ENGINE")
    }
}
