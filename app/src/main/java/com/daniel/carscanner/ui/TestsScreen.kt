package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.obd.ActiveTest
import com.daniel.carscanner.obd.Risk
import com.daniel.carscanner.obd.Safety
import com.daniel.carscanner.obd.Visuals

/*
 * WHAT THIS FILE DOES
 * Your list of active tests (fan on, fuel pump on, etc.).
 *  - "Add test" opens a form where you enter the commands for your car.
 *  - "Run" asks a safety question first, then runs the test with a time limit.
 *  - "Stop now" ends it early. Stop commands are always sent, even if you
 *    leave the screen or the module refuses the start command.
 *  - "Shows on car" picks an animation for the car drawing on Home while the
 *    test runs, e.g. headlights glowing during a headlight test.
 */

@Composable
fun TestsScreen(vm: ScannerViewModel) {
    val allTests by vm.tests.collectAsStateWithLifecycle()
    val vin by vm.vin.collectAsStateWithLifecycle()
    // Show this car's tests plus any older tests that aren't tied to a car
    val tests = allTests.filter { it.vin.isBlank() || it.vin == vin }
    val running by vm.runningTest.collectAsStateWithLifecycle()
    val status by vm.testStatus.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ActiveTest?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmRun by remember { mutableStateOf<ActiveTest?>(null) }

    // Opened from a "+" button on the Vehicle screen: start a pre-filled new test
    val draft by vm.testDraftVisual.collectAsStateWithLifecycle()
    var draftVisual by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(draft) {
        draft?.let { draftVisual = it; creating = true; vm.testDraftVisual.value = null }
    }

    // Leaving the screen stops any running test.
    DisposableEffect(Unit) { onDispose { vm.stopTest() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Active tests switch real parts on so you can prove they work. Commands differ by car and module, " +
                    "so add only ones confirmed for your vehicle.",
                color = Ui.Sub,
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Add test", modifier = Modifier.fillMaxWidth()) { creating = true }
        }
        if (status.isNotEmpty()) item {
            val bad = status.startsWith("Rejected") || status.startsWith("Error") || status.startsWith("No reply")
            Text("Last result: $status", color = if (bad) Ui.Bad else Ui.Good, modifier = Modifier.padding(top = 6.dp))
        }
        if (tests.isEmpty()) item { Text("No tests yet.", color = Ui.Sub, modifier = Modifier.padding(top = 10.dp)) }
        items(tests, key = { it.id }) { t ->
            val isRunning = running == t.id
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (isRunning) Ui.Raised else Ui.Card).padding(18.dp),
            ) {
                Text(t.name, style = MaterialTheme.typography.titleMedium)
                if (t.notes.isNotBlank()) Text(t.notes, color = Ui.Sub)
                Text(
                    "Module ${t.header.ifBlank { "default" }}, start ${t.startCommands.joinToString(" ")}, " +
                        "stop ${t.stopCommands.joinToString(" ").ifBlank { "none" }}, limit ${t.maxSeconds} s" +
                        (if (t.visual.isNotBlank()) ", shows ${Visuals.label(t.visual).lowercase()}" else ""),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Ui.Sub,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isRunning) {
                        SecondaryButton("Stop now", color = Ui.Bad, modifier = Modifier.weight(1f), onClick = vm::stopTest)
                    } else {
                        PrimaryButton("Run", enabled = running == null, modifier = Modifier.weight(1f)) { confirmRun = t }
                        SecondaryButton("Edit", enabled = running == null, modifier = Modifier.weight(1f)) { editing = t }
                        TextButton(onClick = { vm.deleteTest(t.id) }, enabled = running == null) { Text("Delete", color = Ui.Sub) }
                    }
                }
            }
        }
    }

    // Safety confirmation before anything moves.
    confirmRun?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmRun = null },
            containerColor = Ui.Card,
            title = { Text("Run \"${t.name}\"?") },
            text = {
                Text(
                    "Car in Park with the parking brake set. Hands, hair and tools clear of the fan and belts. " +
                        "Engine off and ignition on, unless the test needs the engine running. " +
                        "It stops by itself after ${t.maxSeconds} seconds."
                )
            },
            confirmButton = { TextButton(onClick = { confirmRun = null; vm.runTest(t) }) { Text("Run test") } },
            dismissButton = { TextButton(onClick = { confirmRun = null }) { Text("Cancel") } },
        )
    }

    if (creating || editing != null) {
        TestEditor(
            initial = editing,
            initialVisual = draftVisual,
            onDismiss = { creating = false; editing = null; draftVisual = null },
            onSave = { vm.saveTest(it); creating = false; editing = null; draftVisual = null },
        )
    }
}

/** The add/edit form. "Save test" is only enabled once a name and start command are filled in. */
@Composable
private fun TestEditor(initial: ActiveTest?, initialVisual: String?, onDismiss: () -> Unit, onSave: (ActiveTest) -> Unit) {
    // A form opened from the Vehicle screen starts with that part's name and animation filled in
    var name by remember { mutableStateOf(initial?.name ?: initialVisual?.let { Visuals.label(it) } ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var header by remember { mutableStateOf(initial?.header ?: "7E0") }
    var start by remember { mutableStateOf(initial?.startCommands?.joinToString("\n") ?: "") }
    var stop by remember { mutableStateOf(initial?.stopCommands?.joinToString("\n") ?: "") }
    var keepAlive by remember { mutableStateOf(initial?.keepAlive ?: "3E00") }
    var seconds by remember { mutableStateOf((initial?.maxSeconds ?: 10).toString()) }
    var visual by remember { mutableStateOf(initial?.visual ?: initialVisual ?: "") }

    fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val allCommands = lines(start) + lines(stop) + listOf(keepAlive.trim()).filter { it.isNotEmpty() }
    // Checked as you type, so problems show up before you tap Save.
    val problem = Safety.testProblem(header.trim(), allCommands, seconds.toIntOrNull() ?: 0)
    val needsExpert = allCommands.any { Safety.classify(it) == Risk.Dangerous }
    val valid = name.isNotBlank() && lines(start).isNotEmpty() && problem == null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui.Card,
        title = { Text(if (initial == null) "Add test" else "Edit test") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Cooling fan on") }, singleLine = true)
                OutlinedTextField(header, { header = it.uppercase() }, label = { Text("Module address (7E0 = engine)") }, singleLine = true)
                OutlinedTextField(start, { start = it.uppercase() }, label = { Text("Start commands, one per line") }, minLines = 2)
                OutlinedTextField(stop, { stop = it.uppercase() }, label = { Text("Stop commands, one per line") }, minLines = 2)
                OutlinedTextField(keepAlive, { keepAlive = it.uppercase() }, label = { Text("Keep-alive (blank for none)") }, singleLine = true)
                OutlinedTextField(
                    seconds, { seconds = it.filter(Char::isDigit) },
                    label = { Text("Time limit, seconds (1–120)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes, e.g. where the command came from") })
                when {
                    problem != null -> Text(problem, color = Ui.Bad, style = MaterialTheme.typography.bodySmall)
                    needsExpert -> Text("Contains a command that can reset or reprogram a module. It will only run with Expert mode on.",
                        color = Ui.Watch, style = MaterialTheme.typography.bodySmall)
                }
                // Pick the animation the car drawing shows while the test runs
                Text("Shows on car", color = Ui.Sub, style = MaterialTheme.typography.labelMedium)
                Box {
                    var open by remember { mutableStateOf(false) }
                    SecondaryButton(Visuals.label(visual), modifier = Modifier.fillMaxWidth()) { open = true }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        Visuals.options.forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { visual = key; open = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val base = initial ?: ActiveTest(name = name, startCommands = emptyList(), stopCommands = emptyList())
                onSave(
                    base.copy(
                        name = name.trim(), notes = notes.trim(), header = header.trim(),
                        startCommands = lines(start), stopCommands = lines(stop),
                        keepAlive = keepAlive.trim(), maxSeconds = seconds.toInt(), visual = visual,
                    )
                )
            }) { Text("Save test") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
