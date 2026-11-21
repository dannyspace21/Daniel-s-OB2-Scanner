package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.data.Reports

/*
 * WHAT THIS FILE DOES
 * Trips and fuel economy. Tap Start before you drive and Stop when you arrive.
 * While driving it shows MPG right now (big number), plus distance, time,
 * average MPG, fuel used and cost. Finished trips are saved per car.
 * Keep the app open on screen during the trip (the screen stays on).
 */
@Composable
fun TripScreen(vm: ScannerViewModel, onBack: () -> Unit) {
    val trip by vm.tripLive.collectAsStateWithLifecycle()
    // Reading the tick makes this screen redraw on every new reading
    @Suppress("UNUSED_VARIABLE") val tick = vm.tripTick.collectAsStateWithLifecycle().value
    val trips by vm.trips.collectAsStateWithLifecycle()
    val price by vm.fuelPrice.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    var priceInput by remember(price) { mutableStateOf(price?.let { "%.2f".format(it) } ?: "") }

    Page("Trips & MPG", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val t = trip
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ui.Card).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (t == null) {
                        Text("Ready", style = MaterialTheme.typography.displayMedium)
                        Text("Tap Start before you drive.", color = Ui.Sub)
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("Start trip", enabled = conn is Conn.Connected, modifier = Modifier.fillMaxWidth()) { vm.startTrip() }
                        if (conn !is Conn.Connected) Text("Connect your adapter first.", color = Ui.Sub, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        Text(t.instantMpg?.let { "%.1f".format(it) } ?: "–", style = MaterialTheme.typography.displayMedium)
                        Text(if (t.instantMpg == null) "MPG now (idling)" else "MPG now", color = Ui.Sub)
                        Spacer(Modifier.height(16.dp))
                        StatRow("Distance", "%.2f mi".format(t.miles), "Time", duration(t.seconds.toLong()))
                        StatRow("Average", t.averageMpg?.let { "%.1f MPG".format(it) } ?: "–", "Speed", "%.0f mph".format(t.mph))
                        StatRow("Fuel used", if (t.gallons > 0) "%.2f gal".format(t.gallons) else "–",
                            "Cost", price?.let { p -> if (t.gallons > 0) "$%.2f".format(t.gallons * p) else "–" } ?: "Set price below")
                        Spacer(Modifier.height(16.dp))
                        SecondaryButton("Stop trip", color = Ui.Bad, modifier = Modifier.fillMaxWidth()) { vm.stopTrip() }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(priceInput, { priceInput = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                        label = { Text("Fuel price per gallon") }, prefix = { Text("$") }, singleLine = true,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton("Save") { vm.setFuelPrice(priceInput) }
                }
                Text("MPG is calculated from your car's sensors and is usually within about 10% of a fill-up calculation.",
                    color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            if (trips.isNotEmpty()) item { Text("Past trips", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(trips, key = { it.startedAt }) { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Card).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("%.1f mi".format(r.miles) + (r.mpg?.let { ", %.1f MPG".format(it) } ?: ""), style = MaterialTheme.typography.bodyLarge)
                        Text("${Reports.date(r.startedAt)}, ${duration(r.seconds)}" +
                            (price?.let { p -> r.gallons?.let { g -> ", $%.2f".format(g * p) } } ?: ""),
                            color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { vm.deleteTrip(r) }) { Text("Delete", color = Ui.Sub) }
                }
            }
        }
    }
}

/** Two label/value pairs side by side. */
@Composable
private fun StatRow(l1: String, v1: String, l2: String, v2: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) { Text(l1, color = Ui.Sub, style = MaterialTheme.typography.bodySmall); Text(v1, style = MaterialTheme.typography.titleMedium) }
        Column(Modifier.weight(1f)) { Text(l2, color = Ui.Sub, style = MaterialTheme.typography.bodySmall); Text(v2, style = MaterialTheme.typography.titleMedium) }
    }
}

/** "1 h 05 min" or "12 min". */
private fun duration(seconds: Long): String {
    val m = seconds / 60
    return if (m >= 60) "%d h %02d min".format(m / 60, m % 60) else "$m min"
}
