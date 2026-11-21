package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.obd.Learn

/*
 * WHAT THIS FILE DOES
 * The "Set up automatically" sheet on the Body data screen. No codes needed:
 *   1. "Find my car's data": the app reads every module once (a few minutes).
 *   2. Pick a part (Driver door, Brake lights...). The app tells you what to do:
 *      "Close the driver door" -> Next -> "Open the driver door" -> Next ->
 *      "Close the driver door again" -> Next. It finds the matching signal and saves it.
 * Learned parts show a green check. Then Live view on Home mirrors the car.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnSheet(vm: ScannerViewModel, onClose: () -> Unit) {
    val sources by vm.learnSources.collectAsStateWithLifecycle()
    val status by vm.learnStatus.collectAsStateWithLifecycle()
    val progress by vm.learnProgress.collectAsStateWithLifecycle()
    val active by vm.learnActive.collectAsStateWithLifecycle()
    val result by vm.learnResult.collectAsStateWithLifecycle()
    val signals by vm.allSignals.collectAsStateWithLifecycle()
    val vin by vm.vin.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    val connected = conn is Conn.Connected
    val learned = signals.filter { it.vin == vin }.map { it.element }.toSet()

    ModalBottomSheet(onDismissRequest = { vm.cancelLearning(); onClose() }, containerColor = Ui.Card) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
            Text("Set up automatically", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))

            when {
                !connected -> Text("Connect your adapter first, with the ignition on.", color = Ui.Sub)

                // Step 1: find the data (once per car)
                sources.isEmpty() -> {
                    Text(
                        "First the app finds the data your car's computers share. Ignition on, engine off, doors closed. " +
                            "It only reads, nothing is changed. Takes a few minutes, once per car.",
                        color = Ui.Sub,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (status != null) Progress(status!!, progress)
                    else PrimaryButton("Find my car's data", modifier = Modifier.fillMaxWidth(), onClick = vm::findBodyData)
                }

                // Step 2a: guiding one part
                active != null -> {
                    val (step, phase) = active!!
                    Text(step.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Text("Step ${phase + 1} of 3", color = Ui.Sub, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Raised).padding(20.dp)) {
                        Text(
                            when (phase) { 0 -> step.offText; 1 -> step.onText; else -> step.offText + " again" } + ".",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                    Text(
                        if (phase == 1 && step.blinks) "Leave it blinking, then tap Next." else "Then tap Next.",
                        color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    if (status != null) Progress(status!!, null)
                    else PrimaryButton(if (phase < 2) "Next" else "Finish", modifier = Modifier.fillMaxWidth(), onClick = vm::learnNext)
                    TextButton(onClick = vm::cancelLearning) { Text("Cancel", color = Ui.Sub) }
                }

                // Step 2b: list of parts to learn
                else -> {
                    Text("Found ${sources.size} data blocks. Tap a part and follow the steps.", color = Ui.Sub)
                    Spacer(Modifier.height(10.dp))
                    Learn.steps.forEach { step ->
                        val done = step.elements.all { it in learned }
                        val note = result?.takeIf { it.first == step.id }?.second
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Ui.Raised)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(step.title)
                                if (note != null) Text(note, color = if (done) Ui.Good else Ui.Watch, style = MaterialTheme.typography.bodySmall)
                            }
                            if (done) Icon(Icons.Filled.CheckCircle, contentDescription = "Learned", tint = Ui.Good)
                            TextButton(onClick = { vm.startLearning(step) }) { Text(if (done) "Redo" else "Learn", color = Ui.Accent) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (status != null) Progress(status!!, progress)
                    else TextButton(onClick = vm::findBodyData) { Text("Search the car again", color = Ui.Sub) }
                }
            }
        }
    }
}

/** Status text with a bar (or a spinner when there's no percentage). */
@Composable
private fun Progress(text: String, fraction: Float?) {
    Column(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        if (fraction != null) LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Ui.Accent, trackColor = Ui.Raised,
        ) else LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Ui.Accent, trackColor = Ui.Raised,
        )
    }
}
