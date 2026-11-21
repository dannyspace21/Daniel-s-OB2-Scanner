package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.net.BodyShape
import com.daniel.carscanner.net.CabSize
import com.daniel.carscanner.obd.Element
import com.daniel.carscanner.obd.Visuals
import com.daniel.carscanner.net.HistorySites
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/*
 * WHAT THIS FILE DOES
 * Everything about the car itself:
 *  - the big drawing, plus a control button for each part of the car. Each
 *    button runs that part's active test, sending the real command; the drawing
 *    animates as the car responds. Parts without a command yet show "+" and
 *    open a pre-filled form to add one. (Commands differ by car, so the app
 *    never guesses them.)
 *  - paint color picker (the VIN doesn't record color, so you choose once)
 *  - details decoded from the VIN by NHTSA
 *  - a box to type a VIN, for cars that don't report it
 *  - open safety recalls, each linking to its nhtsa.gov page
 *  - vehicle history sites (Carfax, AutoCheck, NICB, NMVTIS): copies your VIN
 *    and opens the site, since none of them let apps pull reports directly
 */
@Composable
fun VehicleScreen(vm: ScannerViewModel, onBack: () -> Unit, onSetup: (String) -> Unit) {
    val info by vm.vehicle.collectAsStateWithLifecycle()
    val vin by vm.vin.collectAsStateWithLifecycle()
    val recalls by vm.recalls.collectAsStateWithLifecycle()
    val status by vm.vehicleStatus.collectAsStateWithLifecycle()
    val paint by vm.paint.collectAsStateWithLifecycle()
    val car by vm.carState.collectAsStateWithLifecycle()
    val tests by vm.tests.collectAsStateWithLifecycle()
    val running by vm.runningTest.collectAsStateWithLifecycle()
    val testStatus by vm.testStatus.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    val connected = conn is Conn.Connected
    var confirm by remember { mutableStateOf<com.daniel.carscanner.obd.ActiveTest?>(null) }

    // Leaving this screen stops anything that's running (its stop commands are always sent)
    DisposableEffect(Unit) { onDispose { vm.stopTest() } }
    var vinInput by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    Page("Vehicle", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
            // The car
            item {
                VehicleView(
                    shape = info?.shape ?: BodyShape.Sedan, doors = info?.doorCount ?: 4, cab = info?.cab ?: CabSize.Regular,
                    paint = Color(paint), state = car,
                    modifier = Modifier.fillMaxWidth().height(320.dp), dim = info == null,
                )
                Text("Control your car", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text(
                    if (connected) "Each button sends its command to the car. Buttons marked + don't have a command for this car yet; tap one to add it."
                    else "Connect your adapter to send commands. You can still set up buttons marked + now.",
                    color = Ui.Sub, style = MaterialTheme.typography.bodySmall,
                )
                if (testStatus.isNotEmpty()) Text(
                    "Last result: $testStatus",
                    color = if (testStatus.startsWith("Rejected") || testStatus.startsWith("Error") || testStatus.startsWith("No reply")) Ui.Bad else Ui.Good,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                )
            }

            // One control per part of the car, grouped: Lights, Doors, Windows, Other.
            // "Engine running" is status only; the engine can't be started through the diagnostic port.
            Element.entries.filter { it != Element.Engine }.groupBy { it.group }.forEach { (group, elements) ->
                item {
                    Text(group, color = Ui.Sub, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    elements.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { e ->
                                val test = vm.testFor(e, tests)
                                when {
                                    test == null -> SecondaryButton("+ ${e.label}", color = Ui.Card, modifier = Modifier.weight(1f)) { onSetup(e.key) }
                                    running == test.id -> SecondaryButton("Stop", color = Ui.Bad, modifier = Modifier.weight(1f), onClick = vm::stopTest)
                                    else -> PrimaryButton(e.label, enabled = connected && running == null, modifier = Modifier.weight(1f)) { confirm = test }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // Paint color
            item {
                Text("Paint color", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(PaintColors) { (name, value) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(Color(value))
                                    .border(if (value == paint) 3.dp else 1.dp, if (value == paint) Ui.Accent else Ui.Line, CircleShape)
                                    .clickable { vm.setPaint(value) },
                            )
                            Text(name, style = MaterialTheme.typography.bodySmall, color = Ui.Sub, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            // Details from the VIN
            item {
                Text("Details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 6.dp))
                if (status != null) Text(status ?: "", color = Ui.Sub)
                val i = info
                if (i != null) {
                    listOf(
                        "Year" to i.year, "Make" to i.makeName, "Model" to i.model, "Trim" to listOf(i.series, i.trim).filter { it.isNotBlank() }.joinToString(" "),
                        "Body" to i.bodyClass, "Doors" to (i.doors?.toString() ?: ""), "Cab" to i.cabType, "Engine" to i.engine,
                        "Drive" to i.driveType, "Fuel" to i.fuel,
                    ).filter { it.second.isNotBlank() }.forEach { (k, v) -> Detail(k, v) }
                }
                Detail("VIN", vin ?: "Not known yet")
            }

            // Type a VIN
            item {
                Text("Enter a VIN", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 6.dp))
                Text("For cars that don't report their VIN, or to look up a different car. It's on the dash at the bottom of the windshield and on the driver's door jamb.",
                    color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    OutlinedTextField(
                        vinInput, { vinInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(17) },
                        singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        placeholder = { Text("17 characters") },
                        supportingText = { Text("${vinInput.length}/17") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    )
                    Spacer(Modifier.width(10.dp))
                    PrimaryButton("Look up", enabled = vinInput.length == 17) { vm.setVinManually(vinInput); vinInput = "" }
                }
            }

            // Vehicle history: copy the VIN, open the site
            item {
                Text("Vehicle history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
                Text("Tap a site: your VIN is copied, then paste it there. Your own scans and service log are in Scan history and Maintenance.",
                    color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                HistorySites.all.forEach { site ->
                    NavRow(Icons.Filled.ManageSearch, site.name, site.about) {
                        vin?.let { clipboard.setText(AnnotatedString(it)); vm.message.value = "VIN copied. Paste it on ${site.name}." }
                        uri.openUri(site.url)
                    }
                }
            }

            // Recalls
            item {
                Text(
                    when {
                        info == null -> "Recalls"
                        recalls.isEmpty() -> "Recalls: none found"
                        else -> "Recalls: ${recalls.size}"
                    },
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 6.dp),
                )
                if (info != null && recalls.isNotEmpty()) Text(
                    "These are all recalls for this year, make and model. Your dealer can tell you which apply to your VIN and whether they've been done.",
                    color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(recalls, key = { it.campaign }) { r ->
                var open by remember { mutableStateOf(false) }
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Ui.Card)
                        .clickable { open = !open }.padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (r.parkIt) Ui.Bad else Ui.Watch)
                        Spacer(Modifier.width(8.dp))
                        Text(r.component, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    }
                    if (r.parkIt) Text("NHTSA says: don't drive until fixed", color = Ui.Bad, style = MaterialTheme.typography.labelMedium)
                    Text(r.summary, maxLines = if (open) Int.MAX_VALUE else 3, modifier = Modifier.padding(top = 6.dp))
                    if (open) {
                        if (r.consequence.isNotBlank()) { Label("Risk"); Text(r.consequence) }
                        if (r.remedy.isNotBlank()) { Label("Fix"); Text(r.remedy) }
                        TextButton(onClick = { uri.openUri(r.url) }) { Text("Open on nhtsa.gov", color = Ui.Accent) }
                    }
                    Text("Campaign ${r.campaign}${if (r.date.isNotBlank()) ", ${r.date}" else ""}", color = Ui.Sub,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }

    // Safety check before anything moves
    confirm?.let { t ->
        val moving = Visuals.elements(t.visual).any { it.group == "Doors" || it.group == "Windows" }
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Ui.Card,
            title = { Text("Send \"${t.name}\"?") },
            text = {
                Text(
                    "Car in Park with the parking brake set." +
                        (if (moving) " Make sure no one's hands or fingers are near the doors, windows or trunk." else "") +
                        " It stops by itself after ${t.maxSeconds} seconds, or tap Stop."
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; vm.runTest(t) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** Small grey heading inside a card. */
@Composable
private fun Label(text: String) {
    Text(text, color = Ui.Sub, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

/** A label/value line. */
@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Text(label, color = Ui.Sub, modifier = Modifier.width(84.dp))
        Text(value)
    }
}
