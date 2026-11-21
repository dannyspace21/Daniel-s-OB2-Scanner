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
import com.daniel.carscanner.data.MaintItem
import com.daniel.carscanner.data.Maintenance
import com.daniel.carscanner.obd.Level

/*
 * WHAT THIS FILE DOES
 * Service tracker for the current car. Each item shows green (fine),
 * amber (due soon) or red (overdue), by miles or months, whichever comes first.
 *  - Mileage comes from the car on newer models; otherwise type it in.
 *  - "Done" records today and the current mileage.
 *  - Tap an item to change its interval to match your owner's manual.
 */
@Composable
fun MaintenanceScreen(vm: ScannerViewModel, onBack: () -> Unit) {
    val items by vm.maintenance.collectAsStateWithLifecycle()
    val garage by vm.garage.collectAsStateWithLifecycle()
    val vin by vm.vin.collectAsStateWithLifecycle()
    val car = garage.firstOrNull { it.vin == vin }
    var milesInput by remember(car?.odometerMiles) { mutableStateOf(car?.odometerMiles?.toString() ?: "") }
    var editing by remember { mutableStateOf<MaintItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    val today = vm.todayDay()

    Page("Maintenance", onBack) {
        if (vin == null) {
            Text("Add a car to your garage first.", color = Ui.Sub, modifier = Modifier.padding(24.dp))
            return@Page
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Current mileage", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (car?.odometerFromCar == true) "Read from your car." else "Type it from your dash. Newer cars fill this in automatically.",
                    color = Ui.Sub, style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(milesInput, { milesInput = it.filter(Char::isDigit).take(7) }, singleLine = true,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), suffix = { Text("mi") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Spacer(Modifier.width(10.dp))
                    PrimaryButton("Save", enabled = milesInput.isNotEmpty()) { milesInput.toIntOrNull()?.let { vm.setOdometer(it) } }
                }
                Text("Intervals are typical values. Check your owner's manual and tap an item to adjust it.",
                    color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
            items(items.sortedByDescending { Maintenance.status(it, today, car?.odometerMiles).level.severity }, key = { it.id }) { item ->
                val status = Maintenance.status(item, today, car?.odometerMiles)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Card).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(status.level.color, 10)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        TextButton(onClick = { editing = item }, contentPadding = PaddingValues(0.dp)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge, color = Ui.Text)
                        }
                        Text(status.text, color = if (status.level == Level.Problem) Ui.Bad else Ui.Sub, style = MaterialTheme.typography.bodySmall)
                        Text(intervalText(item), color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                    }
                    SecondaryButton("Done") { vm.markServiceDone(item) }
                }
            }
            item { SecondaryButton("Add an item", modifier = Modifier.fillMaxWidth()) { adding = true } }
        }
    }

    if (editing != null || adding) {
        val start = editing
        var name by remember(start) { mutableStateOf(start?.name ?: "") }
        var miles by remember(start) { mutableStateOf(start?.everyMiles?.takeIf { it > 0 }?.toString() ?: "") }
        var months by remember(start) { mutableStateOf(start?.everyMonths?.takeIf { it > 0 }?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null; adding = false },
            containerColor = Ui.Card,
            title = { Text(if (start == null) "Add an item" else "Edit ${start.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(miles, { miles = it.filter(Char::isDigit).take(6) }, label = { Text("Every how many miles (blank = none)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(months, { months = it.filter(Char::isDigit).take(3) }, label = { Text("Every how many months (blank = none)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (start != null) TextButton(onClick = { vm.deleteServiceItem(start); editing = null }) { Text("Delete item", color = Ui.Bad) }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && (miles.isNotEmpty() || months.isNotEmpty()), onClick = {
                    val base = start ?: MaintItem(name = name, everyMiles = 0, everyMonths = 0)
                    vm.saveServiceItem(base.copy(name = name.trim(), everyMiles = miles.toIntOrNull() ?: 0, everyMonths = months.toIntOrNull() ?: 0))
                    editing = null; adding = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null; adding = false }) { Text("Cancel") } },
        )
    }
}

/** "Every 5,000 mi or 6 months". */
private fun intervalText(i: MaintItem): String = "Every " + listOfNotNull(
    i.everyMiles.takeIf { it > 0 }?.let { "%,d mi".format(it) },
    i.everyMonths.takeIf { it > 0 }?.let { "$it month${if (it > 1) "s" else ""}" },
).joinToString(" or ")
