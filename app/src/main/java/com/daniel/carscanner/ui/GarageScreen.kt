@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.data.GarageCar
import com.daniel.carscanner.data.Reports
import com.daniel.carscanner.net.BodyShape
import com.daniel.carscanner.net.CabSize
import com.daniel.carscanner.obd.CarState

/*
 * WHAT THIS FILE DOES
 * Your garage: every car you've connected to or added by VIN, each drawn in
 * its own shape and color. The current car is outlined in blue.
 *  - Tap a car to switch to it (Home, codes, tests and maintenance follow).
 *  - Tap "Edit" to give it a nickname or remove it.
 *  - Add a car by plugging in, or by typing its VIN.
 */
@Composable
fun GarageScreen(vm: ScannerViewModel, onBack: () -> Unit, goConnect: () -> Unit) {
    val garage by vm.garage.collectAsStateWithLifecycle()
    val current by vm.vin.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<GarageCar?>(null) }
    var vinInput by remember { mutableStateOf("") }

    Page("Garage", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (garage.isEmpty()) item {
                Text("No cars yet. Connect your adapter to a car, or add one by VIN below.", color = Ui.Sub)
            }
            items(garage.sortedByDescending { it.lastSeen }, key = { it.vin }) { car ->
                val info = vm.infoFor(car.vin)
                val selected = car.vin == current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ui.Card)
                        .border(if (selected) 2.dp else 0.dp, if (selected) Ui.Accent else Color.Transparent, RoundedCornerShape(20.dp))
                        .clickable { vm.switchCar(car.vin); onBack() }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VehicleView(
                        shape = info?.shape ?: BodyShape.Sedan, doors = info?.doorCount ?: 4, cab = info?.cab ?: CabSize.Regular,
                        paint = Color(vm.paintFor(car.vin)), state = CarState(), modifier = Modifier.size(width = 90.dp, height = 110.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(vm.nameFor(car.vin), style = MaterialTheme.typography.titleMedium)
                        if (car.nickname.isNotBlank() && info != null) Text(info.displayName, color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                        Text(car.vin, color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (car.lastSeen > 0) "Last connected ${Reports.date(car.lastSeen)}" else "Added by VIN",
                            color = Ui.Sub, style = MaterialTheme.typography.bodySmall,
                        )
                        car.odometerMiles?.let { Text("%,d mi".format(it), color = Ui.Sub, style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = { editing = car }) { Text("Edit", color = Ui.Accent) }
                }
            }

            item {
                Text("Add a car", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text("Plug in and connect, and the car adds itself. Or type its VIN (on the dash at the base of the windshield).",
                    color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    OutlinedTextField(
                        vinInput, { vinInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(17) },
                        singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        placeholder = { Text("17-character VIN") }, supportingText = { Text("${vinInput.length}/17") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    )
                    Spacer(Modifier.width(10.dp))
                    PrimaryButton("Add", enabled = vinInput.length == 17) { vm.setVinManually(vinInput); vinInput = "" }
                }
                SecondaryButton("Connect adapter", modifier = Modifier.fillMaxWidth().padding(top = 4.dp), onClick = goConnect)
            }
        }
    }

    editing?.let { car ->
        var name by remember(car.vin) { mutableStateOf(car.nickname) }
        var confirmRemove by remember(car.vin) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Ui.Card,
            title = { Text(vm.nameFor(car.vin)) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Nickname, e.g. \"Daily driver\"") }, singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    if (!confirmRemove) TextButton(onClick = { confirmRemove = true }) { Text("Remove from garage", color = Ui.Bad) }
                    else Text("Tap Remove again to confirm. Its saved data stays on the phone in case you add it back.", color = Ui.Sub)
                }
            },
            confirmButton = {
                if (confirmRemove) TextButton(onClick = { vm.removeCar(car.vin); editing = null }) { Text("Remove", color = Ui.Bad) }
                else TextButton(onClick = { vm.renameCar(car.vin, name); editing = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
