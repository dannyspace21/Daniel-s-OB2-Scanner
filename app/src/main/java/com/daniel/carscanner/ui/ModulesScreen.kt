@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.obd.DtcDecoder
import com.daniel.carscanner.obd.Level
import com.daniel.carscanner.obd.Module
import com.daniel.carscanner.obd.ModuleAddress
import com.daniel.carscanner.obd.ModuleCode
import com.daniel.carscanner.net.Related

/*
 * WHAT THIS FILE DOES
 * The "All modules" screen: scans every computer in the car and lists them,
 * each with a status dot (green = no codes, amber = pending or history,
 * red = active or confirmed, grey = found but couldn't read).
 * Tap a module to see its codes in detail, rename it, or clear its codes.
 */

@Composable
fun ModulesScreen(vm: ScannerViewModel) {
    val modules by vm.modules.collectAsStateWithLifecycle()
    val status by vm.moduleScanStatus.collectAsStateWithLifecycle()
    val progress by vm.moduleProgress.collectAsStateWithLifecycle()
    val scanned by vm.modulesScanned.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var deep by remember { mutableStateOf(true) }
    var openAddr by remember { mutableStateOf<ModuleAddress?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    // Leaving the screen stops a scan in progress.
    DisposableEffect(Unit) { onDispose { vm.stopModuleScan() } }

    val scanning = status != null
    val withCodes = modules.filter { it.codes.isNotEmpty() }
    // After a scan, modules with problems come first. During a scan, keep the found order so rows don't jump.
    val listed = if (scanning) modules else modules.sortedByDescending { it.level.severity }
    // The body-network option only applies to Toyota-family cars (shown too when the make isn't known yet).
    val info by vm.vehicle.collectAsStateWithLifecycle()
    val toyotaFamily = info?.make?.uppercase()?.let { it in setOf("TOYOTA", "LEXUS", "SCION") } ?: true

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                "Finds every computer in the car, like ABS, airbags, transmission, body and climate, and reads its codes. " +
                    "Ignition on, engine off. A full scan takes 1 to 3 minutes.",
                color = Ui.Sub,
            )
            Spacer(Modifier.height(16.dp))
            if (scanning) {
                Text(status ?: "", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Ui.Accent, trackColor = Ui.Card,
                )
                Spacer(Modifier.height(12.dp))
                SecondaryButton("Stop scan", modifier = Modifier.fillMaxWidth(), onClick = vm::stopModuleScan)
            } else {
                PrimaryButton(if (scanned) "Scan again" else "Scan all modules", enabled = busy == null,
                    modifier = Modifier.fillMaxWidth()) { vm.scanModules(deep && toyotaFamily) }
                if (toyotaFamily) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { deep = !deep }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(deep, { deep = it }, colors = CheckboxDefaults.colors(checkedColor = Ui.Accent))
                    Column {
                        Text("Include Toyota/Lexus body network")
                        Text("Finds more body modules. Adds about a minute.", color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Summary line after a scan
        if (scanned && !scanning && modules.isNotEmpty()) item {
            Text(
                "${modules.size} modules found, " +
                    if (withCodes.isEmpty()) "no codes anywhere." else "${withCodes.size} with codes.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
            )
        }

        items(listed, key = { it.address.label }) { m ->
            NavRow(
                icon = Icons.Filled.Memory,
                title = m.name,
                subtitle = m.address.label + ", " + when {
                    !m.readOk && m.protocol == null && !scanned -> "reading…"
                    !m.readOk -> "couldn't read codes"
                    m.codes.isEmpty() -> "no codes"
                    else -> "${m.codes.size} code${if (m.codes.size > 1) "s" else ""}"
                },
                dot = m.level.color,
            ) { openAddr = m.address }
        }

        if (withCodes.isNotEmpty() && !scanning) item {
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Clear codes in all modules", enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                confirmClearAll = true
            }
        }
    }

    // Detail sheet for the module you tapped (looked up fresh so it updates after clearing)
    val open = openAddr?.let { a -> modules.firstOrNull { it.address == a } }
    if (open != null) ModuleSheet(open, busy == null, vm) { openAddr = null }

    if (confirmClearAll) AlertDialog(
        onDismissRequest = { confirmClearAll = false },
        containerColor = Ui.Card,
        title = { Text("Clear codes in ${withCodes.size} modules?") },
        text = {
            Text(
                "This erases the records, it doesn't fix anything. Codes for faults that are still present will come back. " +
                    (if (withCodes.any { it.isAirbag }) "This includes the airbag module. If that light returns, the airbags may not deploy in a crash; get it repaired. " else "") +
                    "Write the codes down first. Engine off, ignition on."
            )
        },
        confirmButton = { TextButton(onClick = { confirmClearAll = false; vm.clearAllModules() }) { Text("Clear all", color = Ui.Bad) } },
        dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") } },
    )
}

/** Bottom sheet with one module's details, codes, rename and clear. */
@Composable
private fun ModuleSheet(m: Module, idle: Boolean, vm: ScannerViewModel, onClose: () -> Unit) {
    var rename by remember(m.address) { mutableStateOf(m.name) }
    var confirmClear by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf<ModuleCode?>(null) }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = Ui.Card) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
        ) {
            Text(m.name, style = MaterialTheme.typography.titleLarge)
            Text(
                "Address ${m.address.label}" + (m.protocol?.let { ", speaks $it" } ?: ""),
                color = Ui.Sub, style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(16.dp))
            when {
                !m.readOk -> Text("This module answered but wouldn't share its codes. It may need a manufacturer-specific request.", color = Ui.Sub)
                m.codes.isEmpty() -> Text("No codes stored.", color = Ui.Good)
                else -> m.codes.forEach { c -> CodeRow(c) { lookup = c }; Spacer(Modifier.height(8.dp)) }
            }

            if (m.isAirbag && m.codes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Airbag faults can mean the airbags won't deploy in a crash. Clearing won't fix that; get it repaired.",
                    color = Ui.Watch, style = MaterialTheme.typography.bodySmall)
            }

            if (m.codes.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SecondaryButton("Clear this module's codes", enabled = idle, modifier = Modifier.fillMaxWidth()) { confirmClear = true }
            }

            // Rename: handy once you know what an unnamed module does
            Spacer(Modifier.height(24.dp))
            Text("Name", color = Ui.Sub, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(rename, { rename = it }, singleLine = true, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.width(10.dp))
                PrimaryButton("Save") { vm.renameModule(m, rename) }
            }
        }
    }

    // Related for your car: the code plus the module's name, e.g. "C1241 ABS / brakes"
    lookup?.let { c ->
        RelatedSheet(vm, topic = "${c.code} ${m.name.removeSuffix(" (likely)")}", keywords = Related.keywordsForCode(c.code)) { lookup = null }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        containerColor = Ui.Card,
        title = { Text("Clear ${m.name} codes?") },
        text = { Text("This erases the record but doesn't fix the cause. Write the codes down first.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearModule(m) }) { Text("Clear codes", color = Ui.Bad) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

/** One code inside a module: code, description, how it failed, and its status. */
@Composable
private fun CodeRow(c: ModuleCode, onLookup: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ui.Raised).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(c.level.color)
                Spacer(Modifier.width(8.dp))
                Text(c.display, style = MaterialTheme.typography.titleMedium, color = if (c.level == Level.Problem) Ui.Bad else Ui.Watch)
            }
            Text(DtcDecoder.describe(c.code))
            c.failureText?.let { Text("Failure: $it", color = Ui.Sub, style = MaterialTheme.typography.bodySmall) }
            Text(c.stateText, color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onLookup) { Text("Look up", color = Ui.Accent) }
    }
}
