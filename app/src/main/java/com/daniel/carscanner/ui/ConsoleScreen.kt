package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.obd.Risk
import com.daniel.carscanner.obd.Safety

/*
 * WHAT THIS FILE DOES
 * A terminal for talking to the car directly. Type a command, see the raw reply.
 * Your commands show in blue, warnings in red. Useful for experimenting and
 * for discovering commands to turn into active tests.
 *  - Quick buttons send common commands.
 *  - "Copy log" copies everything so you can paste it to someone for help.
 *  - Expert mode unlocks commands that can reset or reprogram modules.
 */

/** Quick buttons: command to send -> label on the button. */
private val quick = listOf(
    "ATRV" to "Battery voltage",
    "ATDP" to "Protocol",
    "0101" to "Monitor status",
    "03" to "Stored codes",
    "07" to "Pending codes",
    "0902" to "VIN",
    "ATH1" to "Headers on",
    "ATH0" to "Headers off",
)

@Composable
fun ConsoleScreen(vm: ScannerViewModel) {
    val log by vm.log.collectAsStateWithLifecycle()
    val expert by vm.expertMode.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // Pause live view while the Console is open so your commands aren't interrupted.
    DisposableEffect(Unit) {
        vm.liveViewPaused.value = true
        onDispose { vm.liveViewPaused.value = false }
    }

    // Always scroll to the newest line.
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }

    /** Checks a command's risk, then sends it or asks for confirmation. */
    fun submit(cmd: String) {
        val c = cmd.trim().uppercase()
        if (c.isEmpty()) return
        if (Safety.classify(c) == Risk.Actuator) confirm = c else vm.sendRaw(c)
        input = ""
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Expert mode")
                Text("Allows commands that can reset, unlock or reprogram a module", color = Ui.Sub,
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(expert, { vm.expertMode.value = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Ui.Bad))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quick) { (cmd, label) ->
                SecondaryButton(label) { submit(cmd) }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)
                .clip(RoundedCornerShape(16.dp)).background(Ui.Card),
        ) {
            LazyColumn(state = listState, contentPadding = PaddingValues(14.dp)) {
                items(log.size) { i ->
                    val line = log[i]
                    Text(
                        line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        color = when {
                            line.startsWith(">") -> Ui.Accent
                            line.startsWith("!") -> Ui.Bad
                            else -> Ui.Text
                        },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("e.g. 0105 or ATRV") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit(input) }),
            )
            Spacer(Modifier.width(10.dp))
            PrimaryButton("Send") { submit(input) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) { Text("Copy log") }
            TextButton(onClick = vm::clearLog) { Text("Clear log", color = Ui.Sub) }
        }
    }

    // Confirmation for commands that move parts.
    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Ui.Card,
            title = { Text("Send this command?") },
            text = {
                val erases = c.replace(" ", "").let { it.startsWith("04") || it.startsWith("14") }
                Text(
                    if (erases) "\"$c\" erases trouble codes, freeze frame and emissions readiness. The car will need a drive cycle before it can pass an inspection."
                    else "\"$c\" can switch a real part on. Make sure the car is in Park and everyone is clear of the engine bay."
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; vm.sendRaw(c) }) { Text("Send command") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
