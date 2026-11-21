package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.obd.DtcDecoder
import com.daniel.carscanner.net.Related

/*
 * WHAT THIS FILE DOES
 * Shows the check engine light and the three kinds of trouble codes:
 *   Stored    - confirmed faults (these turn the light on)
 *   Pending   - seen once, not confirmed yet
 *   Permanent - only the car can erase these, after it re-tests the repair
 * Also shows emissions inspection readiness, and the freeze frame: the
 * snapshot of rpm, speed and temperature the car saved when a code was set.
 * "Look up" opens Related for your car: searches for that code on your exact
 * year, make and model, plus any related recalls. "Clear codes" asks first.
 */

@Composable
fun CodesScreen(vm: ScannerViewModel) {
    val mil by vm.milOn.collectAsStateWithLifecycle()
    val stored by vm.storedCodes.collectAsStateWithLifecycle()
    val pending by vm.pendingCodes.collectAsStateWithLifecycle()
    val permanent by vm.permanentCodes.collectAsStateWithLifecycle()
    val read by vm.codesRead.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val readiness by vm.readiness.collectAsStateWithLifecycle()
    val freeze by vm.freezeFrame.collectAsStateWithLifecycle()
    val returned by vm.returnedCodes.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf<String?>(null) }

    val allClear = read && stored.isEmpty() && pending.isEmpty() && permanent.isEmpty() && mil != true
    // Nothing to erase: don't offer a clear that would only reset emissions readiness.
    val canClear = !(read && stored.isEmpty() && pending.isEmpty() && mil != true)

    // Read codes automatically the first time you open this screen.
    LaunchedEffect(Unit) { if (!read) vm.readCodes() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Check engine light", color = Ui.Sub)
                Text(
                    when (mil) { true -> "On"; false -> "Off"; null -> "–" },
                    style = MaterialTheme.typography.displayMedium,
                    color = if (mil == true) Ui.Bad else Ui.Good,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(if (busy != null) "Reading…" else "Read codes", enabled = busy == null, modifier = Modifier.weight(1f), onClick = vm::readCodes)
                SecondaryButton("Clear codes", enabled = busy == null && canClear, modifier = Modifier.weight(1f)) { confirmClear = true }
            }
        }
        // Reading for the first time
        if (!read && busy != null) item {
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Ui.Accent)
                Spacer(Modifier.width(12.dp))
                Text("Reading codes from the car…", color = Ui.Sub)
            }
        }

        // Codes that came back after being cleared
        items(returned, key = { "back" + it.placeKey + it.code }) { r ->
            ReturnedBanner(r, onOpen = { lookup = r.code }, onDismiss = { vm.dismissReturned(r) })
        }

        // Nothing wrong: say so clearly instead of three "None"s
        if (allClear) item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(Ui.Card).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(Ui.Good, 12)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("No trouble codes", style = MaterialTheme.typography.titleMedium)
                    Text("The engine computer has nothing stored, pending or permanent.", color = Ui.Sub,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Snapshot from the moment the first code was set
        freeze?.let { (code, readings) ->
            item {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(16.dp)).background(Ui.Card).padding(16.dp)) {
                    Text("Freeze frame for $code", style = MaterialTheme.typography.titleMedium)
                    Text("What the engine was doing when this code was set. Helps tell a cold-start problem from a highway one.",
                        color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    readings.forEach { (pid, v) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(pid.name, color = Ui.Sub, modifier = Modifier.weight(1f))
                            Text(if (kotlin.math.abs(v) >= 100) "%.0f %s".format(v, pid.unit) else "%.1f %s".format(v, pid.unit))
                        }
                    }
                }
            }
        }
        if (!allClear) {
            codeSection("Stored", "Confirmed faults. These turn on the check engine light.", stored, read, Ui.Bad) { lookup = it }
            codeSection("Pending", "Seen once. They become stored if they happen again.", pending, read, Ui.Watch) { lookup = it }
            codeSection("Permanent", "Only the car can erase these, after it confirms the repair while driving.", permanent, read, Ui.Sub) { lookup = it }
        }

        // Emissions inspection readiness
        readiness?.let { r ->
            item {
                val (level, verdict) = r.verdict
                Column(Modifier.fillMaxWidth().padding(top = 20.dp).clip(RoundedCornerShape(16.dp)).background(Ui.Card).padding(16.dp)) {
                    Text("Emissions inspection", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        StatusDot(level.color, 10); Spacer(Modifier.width(10.dp)); Text(verdict)
                    }
                    r.monitors.forEach { m ->
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, modifier = Modifier.weight(1f), color = Ui.Sub)
                            Text(if (m.complete) "Ready" else "Not ready", color = if (m.complete) Ui.Good else Ui.Watch)
                        }
                    }
                    Text("Monitors reset after clearing codes or disconnecting the battery, and finish with normal driving.",
                        color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }

    lookup?.let { code ->
        RelatedSheet(vm, topic = "$code ${DtcDecoder.describe(code)}", keywords = Related.keywordsForCode(code)) { lookup = null }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        containerColor = Ui.Card,
        title = { Text("Clear all codes?") },
        text = {
            Text(
                "This turns off the check engine light and erases freeze-frame data. It also resets the emissions " +
                    "readiness monitors, so the car needs normal driving before it can pass inspection. " +
                    "Engine off, ignition on. Write the codes down first."
            )
        },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearCodes() }) { Text("Clear codes", color = Ui.Bad) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

/** A heading plus the codes under it. */
private fun LazyListScope.codeSection(title: String, hint: String, codes: List<String>, read: Boolean, color: Color, onLookup: (String) -> Unit) {
    item {
        Column(Modifier.padding(top = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (read && codes.isEmpty()) item { Text("None", color = Ui.Sub) }
    items(codes, key = { title + it }) { code -> CodeCard(code, color) { onLookup(code) } }
}

/** One code with its description and a Look up button. */
@Composable
private fun CodeCard(code: String, color: Color, onLookup: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Card).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(code, style = MaterialTheme.typography.titleLarge, color = color)
            Text(DtcDecoder.describe(code), color = Ui.Sub)
        }
        TextButton(onClick = onLookup) { Text("Look up", color = Ui.Accent) }
    }
}
