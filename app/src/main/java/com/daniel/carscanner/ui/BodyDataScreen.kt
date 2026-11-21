@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.obd.BodySignal
import com.daniel.carscanner.obd.Element
import com.daniel.carscanner.obd.Safety

/*
 * WHAT THIS FILE DOES
 * Sets up "live view": teaching the app to read doors, lights, windows and more
 * from your car's body computer, so the drawing on Home mirrors the real car.
 *
 * Each signal is: which module to ask, what request to send, and which byte
 * and bit of the reply holds the value. To find them:
 *   1. Get the module address and request from Techstream (or a forum).
 *   2. Tap Watch. The reply's bytes show live.
 *   3. Do the thing (open the driver door). The byte that changes lights up
 *      amber; tap it, then tap the bit that flipped. Done.
 */
@Composable
fun BodyDataScreen(vm: ScannerViewModel, onBack: () -> Unit) {
    val all by vm.allSignals.collectAsStateWithLifecycle()
    val vin by vm.vin.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    val live by vm.liveView.collectAsStateWithLifecycle()
    val car by vm.carState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BodySignal?>(null) }
    var creating by remember { mutableStateOf(false) }
    val mine = all.filter { it.vin == vin }
    // Nothing set up yet and connected: open automatic setup straight away.
    var learning by remember { mutableStateOf(mine.isEmpty() && conn is Conn.Connected) }

    Page("Body data", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "Teach the app to read your car's doors, lights, windows and more, so the drawing on Home mirrors the real car " +
                        "and turn signals blink at your car's own rate.",
                    color = Ui.Sub,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Set up automatically", enabled = vin != null, modifier = Modifier.fillMaxWidth()) { learning = true }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Add manually", enabled = vin != null, modifier = Modifier.weight(1f)) { creating = true }
                    if (conn is Conn.Connected) SecondaryButton(if (live) "Stop live view" else "Start live view", modifier = Modifier.weight(1f)) { vm.setLiveView(!live) }
                }
                if (vin == null) Text("Add a car to your garage first.", color = Ui.Sub, modifier = Modifier.padding(top = 8.dp))
            }
            if (mine.isEmpty() && vin != null) item {
                Text("Tap Set up automatically: the app finds your doors, brake lights and more while you open, press and switch things. No codes needed.",
                    modifier = Modifier.padding(top = 8.dp))
                Text("Or by hand", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "1. Get the module address and request from Techstream's data list for your car, or from an owner forum.\n" +
                        "2. Tap Add signal, enter them, and tap Watch.\n" +
                        "3. Open the door or switch on the light. The byte that changes lights up; tap it, then tap the bit that flipped.\n" +
                        "4. Pick which part of the car it is, and save.",
                    color = Ui.Sub,
                )
            }
            items(mine, key = { it.id }) { s ->
                val value = car[s.element]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Card).clickable { editing = s }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(
                        when {
                            s.failure && s.element in car.failed -> Ui.Bad
                            !live -> Ui.Sub
                            value > 0.5f -> Ui.Accent
                            else -> Ui.Line
                        }, 10,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text((if (s.failure) "Bulb check: " else "") + s.element.label)
                        Text(s.where, color = Ui.Sub, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    if (live && !s.failure) Text(
                        if (s.element.kind == Element.Kind.Position) "${(value * 100).toInt()}%" else if (value > 0.5f) "On" else "Off",
                        color = Ui.Sub,
                    )
                }
            }
        }
    }

    if (learning) LearnSheet(vm) { learning = false }

    if (creating || editing != null) {
        SignalEditor(vm, start = editing, vin = vin ?: "") { creating = false; editing = null }
    }
}

/** The add/edit sheet, with the Watch tool for finding the right byte and bit. */
@Composable
private fun SignalEditor(vm: ScannerViewModel, start: BodySignal?, vin: String, onClose: () -> Unit) {
    var element by remember { mutableStateOf(start?.element ?: Element.DoorFL) }
    var header by remember { mutableStateOf(start?.header ?: "750") }
    var command by remember { mutableStateOf(start?.command ?: "") }
    var byteIndex by remember { mutableStateOf(start?.byteIndex ?: 2) }
    var bit by remember { mutableStateOf(start?.bit ?: 0) }
    var invert by remember { mutableStateOf(start?.invert ?: false) }
    var failure by remember { mutableStateOf(start?.failure ?: false) }
    var menu by remember { mutableStateOf(false) }
    var watching by remember { mutableStateOf(false) }
    val bytes by vm.watchBytes.collectAsStateWithLifecycle()
    val changed by vm.watchChanged.collectAsStateWithLifecycle()
    val bits by vm.watchBits.collectAsStateWithLifecycle()
    val watchError by vm.watchError.collectAsStateWithLifecycle()

    // Stop watching when the sheet closes
    DisposableEffect(Unit) { onDispose { vm.stopWatch() } }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = Ui.Card) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
            Text(if (start == null) "Add signal" else "Edit signal", style = MaterialTheme.typography.titleLarge)

            // Which part of the car
            Text("Part of the car", color = Ui.Sub, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            Box {
                SecondaryButton(element.label, modifier = Modifier.fillMaxWidth()) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Element.entries.forEach { e -> DropdownMenuItem(text = { Text("${e.group}: ${e.label}") }, onClick = { element = e; menu = false }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("This bit means the bulb is burned out", modifier = Modifier.weight(1f))
                Switch(failure, { failure = it })
            }

            // Where to read it
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(header, { header = it.uppercase().filter { c -> c.isLetterOrDigit() || c == ' ' }.take(12) },
                    label = { Text("Module") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                OutlinedTextField(command, { command = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(16) },
                    label = { Text("Request") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            }

            // Checked as you type: these requests repeat several times a second, so they may only read.
            val requestProblem = if (command.isBlank()) null else Safety.readRequestProblem(header.trim(), command)
            requestProblem?.let { Text(it, color = Ui.Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }

            // Watch tool
            Spacer(Modifier.height(12.dp))
            if (!watching) PrimaryButton("Watch", enabled = command.isNotBlank() && requestProblem == null, modifier = Modifier.fillMaxWidth()) {
                watching = true; vm.startWatch(header, command)
            } else SecondaryButton("Stop watching", modifier = Modifier.fillMaxWidth()) { watching = false; vm.stopWatch() }
            watchError?.let { Text(it, color = Ui.Watch, modifier = Modifier.padding(top = 6.dp)) }
            if (watching && bytes.isNotEmpty()) {
                Text("Reply bytes. Amber = just changed. Tap one.", color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                bytes.chunked(6).forEachIndexed { row, chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                        chunk.forEachIndexed { col, b ->
                            val i = row * 6 + col
                            val hot = i in changed
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                    .background(if (hot) Ui.Watch.copy(alpha = 0.35f) else Ui.Raised)
                                    .border(if (i == byteIndex) 2.dp else 0.dp, if (i == byteIndex) Ui.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { byteIndex = i }.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("%02X".format(b), fontFamily = FontFamily.Monospace)
                                Text("#$i", color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        repeat(6 - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            // Byte and bit
            Text("Byte #$byteIndex, " + if (bit in 0..7) "bit $bit" else "whole byte (for positions like windows)",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                SecondaryButton("Byte −", modifier = Modifier.weight(1f)) { byteIndex = (byteIndex - 1).coerceAtLeast(0) }
                SecondaryButton("Byte +", modifier = Modifier.weight(1f)) { byteIndex = (byteIndex + 1).coerceAtMost(63) }
            }
            val current = bytes.getOrNull(byteIndex)
            val hotBits = bits[byteIndex] ?: emptySet()
            Text("Bits (7 on the left). Amber = just changed. Tap one.", color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (b in 7 downTo 0) {
                    val value = current?.let { (it shr b) and 1 }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (b in hotBits) Ui.Watch.copy(alpha = 0.35f) else Ui.Raised)
                            .border(if (b == bit) 2.dp else 0.dp, if (b == bit) Ui.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { bit = b }.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(value?.toString() ?: "·", fontFamily = FontFamily.Monospace)
                        Text("$b", color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TextButton(onClick = { bit = -1 }) { Text("Use the whole byte instead (windows, sunroof)", color = Ui.Accent) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Flip it (car reports 1 for closed / off)", modifier = Modifier.weight(1f))
                Switch(invert, { invert = it })
            }

            // Save / delete
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Save signal", enabled = command.isNotBlank() && vin.isNotBlank() && requestProblem == null, modifier = Modifier.fillMaxWidth()) {
                val isTurn = element == Element.TurnL || element == Element.TurnR || element == Element.Hazard
                vm.saveSignal(
                    (start ?: BodySignal(vin = vin, element = element, header = header, command = command, byteIndex = byteIndex, bit = bit))
                        .copy(element = element, header = header.trim(), command = command, byteIndex = byteIndex, bit = bit,
                            invert = invert, failure = failure, fast = isTurn),
                )
                onClose()
            }
            if (start != null) TextButton(onClick = { vm.deleteSignal(start.id); onClose() }) { Text("Delete signal", color = Ui.Bad) }
        }
    }
}
