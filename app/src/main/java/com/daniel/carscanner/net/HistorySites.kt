package com.daniel.carscanner.net

/*
 * WHAT THIS FILE DOES
 * Websites for a car's history (accidents, title, theft, service). None of them
 * let apps pull reports directly, so the app copies your VIN and opens the site;
 * you paste the VIN there. Paid sites charge per report.
 */
data class HistorySite(val name: String, val about: String, val url: String)

object HistorySites {
    val all = listOf(
        HistorySite("Carfax", "Full history report (paid). Owners can use myCARFAX free for service history.", "https://www.carfax.com"),
        HistorySite("AutoCheck", "Experian's history report (paid).", "https://www.autocheck.com"),
        HistorySite("NICB VINCheck", "Free check for theft and insurance total-loss records.", "https://www.nicb.org/vincheck"),
        HistorySite("NMVTIS title history", "Government title, odometer and salvage records from approved sellers (low cost).",
            "https://vehiclehistory.bja.ojp.gov/nmvtis_vehiclehistory"),
        HistorySite("NHTSA recalls by VIN", "Which recalls apply to your exact car, and whether they've been fixed (free).", "https://www.nhtsa.gov/recalls"),
    )
}
