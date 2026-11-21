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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.obd.DtcDecoder
import java.net.URLEncoder

/*
 * WHAT THIS FILE DOES
 * Shows the check engine light and the three kinds of trouble codes:
 *   Stored    - confirmed faults (these turn the light on)
 *   Pending   - seen once, not confirmed yet
 *   Permanent - only the car can erase these, after it re-tests the repair
 * "Look up" searches the web for that code. "Clear codes" asks first.
 */

@Composable
fun CodesScreen(vm: ScannerViewModel) {
    val mil by vm.milOn.collectAsStateWithLifecycle()
    val stored by vm.storedCodes.collectAsStateWithLifecycle()
    val pending by vm.pendingCodes.collectAsStateWithLifecycle()
    val permanent by vm.permanentCodes.collectAsStateWithLifecycle()
    val read by vm.codesRead.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

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
                SecondaryButton("Clear codes", enabled = busy == null, modifier = Modifier.weight(1f)) { confirmClear = true }
            }
        }
        codeSection("Stored", "Confirmed faults. These turn on the check engine light.", stored, read)
        codeSection("Pending", "Seen once. They become stored if they happen again.", pending, read)
        codeSection("Permanent", "Only the car can erase these, after it confirms the repair while driving.", permanent, read)
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
private fun LazyListScope.codeSection(title: String, hint: String, codes: List<String>, read: Boolean) {
    item {
        Column(Modifier.padding(top = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (read && codes.isEmpty()) item { Text("None", color = Ui.Sub) }
    items(codes, key = { title + it }) { code -> CodeCard(code) }
}

/** One code with its description and a web lookup button. */
@Composable
private fun CodeCard(code: String) {
    val uri = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Card).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(code, style = MaterialTheme.typography.titleLarge, color = Ui.Watch)
            Text(DtcDecoder.describe(code), color = Ui.Sub)
        }
        TextButton(onClick = {
            uri.openUri("https://www.google.com/search?q=" + URLEncoder.encode("$code OBD2 code", "UTF-8"))
        }) { Text("Look up", color = Ui.Accent) }
    }
}
