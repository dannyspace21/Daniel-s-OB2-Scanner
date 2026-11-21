package com.daniel.carscanner.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.obd.HealthCheck
import com.daniel.carscanner.obd.Level
import com.daniel.carscanner.obd.Vin

/*
 * WHAT THIS FILE DOES
 * The main screen you land on. From top to bottom:
 *   - Vehicle name (from the VIN) and connection status
 *   - The health ring: green / amber / red summary of the last health check
 *   - Round quick actions: Health check, Codes, All modules, Live data
 *   - One row per car system with its status dot, plus tools and adapter rows
 */

@Composable
fun HomeScreen(vm: ScannerViewModel, go: (Screen) -> Unit) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val vin by vm.vin.collectAsStateWithLifecycle()
    val findings by vm.healthFindings.collectAsStateWithLifecycle()
    val progress by vm.healthProgress.collectAsStateWithLifecycle()
    val volts by vm.voltage.collectAsStateWithLifecycle()
    val modules by vm.modules.collectAsStateWithLifecycle()
    val connected = conn is Conn.Connected

    val vehicle = vin?.let { Vin.describe(it) } ?: if (connected) "Your vehicle" else "No vehicle connected"
    val status = when (val c = conn) {
        is Conn.Connected -> "Connected" + (volts?.let { ", battery %.1f V".format(it) } ?: "")
        is Conn.Connecting -> "Connecting to ${c.name}…"
        is Conn.Failed -> c.reason
        Conn.Disconnected -> "Plug in your adapter and turn the ignition on"
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp),
    ) {
        // Vehicle name and status
        item {
            Text(vehicle, style = MaterialTheme.typography.headlineMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = Ui.Sub, modifier = Modifier.padding(top = 4.dp))
        }

        // Health ring
        item {
            HealthHero(
                connected = connected,
                progress = progress,
                problems = findings.count { it.level == Level.Problem },
                watches = findings.count { it.level == Level.Watch },
                checked = findings.isNotEmpty(),
                onRun = { vm.runHealthCheck(); go(Screen.Health) },
                onConnect = { go(Screen.Connect) },
            )
        }

        // Quick actions
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                RoundAction(Icons.Filled.HealthAndSafety, "Health", connected) { go(Screen.Health) }
                RoundAction(Icons.Filled.ReportProblem, "Codes", connected) { go(Screen.Codes) }
                RoundAction(Icons.Filled.Memory, "Modules", connected) { go(Screen.Modules) }
                RoundAction(Icons.Filled.Speed, "Live", connected) { go(Screen.Live) }
            }
            Spacer(Modifier.height(24.dp))
        }

        // One row per system, showing its worst finding
        items(HealthCheck.systems) { system ->
            val mine = findings.filter { it.system == system }
            val worst = mine.maxByOrNull { it.level.severity }
            NavRow(
                icon = systemIcon(system),
                title = system,
                subtitle = worst?.title ?: "Not checked yet",
                dot = worst?.level?.color,
            ) { go(if (system == HealthCheck.CODES) Screen.Codes else Screen.Health) }
        }

        // Every module in the car (ABS, airbags, body...)
        item {
            val withCodes = modules.count { it.codes.isNotEmpty() }
            NavRow(
                Icons.Filled.Memory, "All modules",
                when {
                    modules.isEmpty() -> "ABS, airbags, transmission, body and more"
                    withCodes == 0 -> "${modules.size} modules, no codes"
                    else -> "${modules.size} modules, $withCodes with codes"
                },
                dot = modules.takeIf { it.isNotEmpty() }?.maxBy { it.level.severity }?.level?.color,
            ) { go(Screen.Modules) }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Ui.Line) }
        item { NavRow(Icons.Filled.Speed, "Live data", "Gauges with normal ranges") { go(Screen.Live) } }
        item { NavRow(Icons.Filled.Build, "Active tests", "Switch parts on to prove they work") { go(Screen.Tests) } }
        item { NavRow(Icons.Filled.Code, "Console", "Send raw commands") { go(Screen.Console) } }
        item {
            NavRow(Icons.Filled.Bluetooth, "Adapter", if (connected) (conn as Conn.Connected).name else "Not connected",
                dot = if (connected) Ui.Good else null) { go(Screen.Connect) }
        }
    }
}

/** Icon for each system row. */
private fun systemIcon(system: String): ImageVector = when (system) {
    HealthCheck.CHARGING -> Icons.Filled.BatteryChargingFull
    HealthCheck.COOLING -> Icons.Filled.Thermostat
    HealthCheck.FUEL -> Icons.Filled.LocalGasStation
    HealthCheck.O2 -> Icons.Filled.Air
    HealthCheck.CODES -> Icons.Filled.ReportProblem
    else -> Icons.Filled.Settings
}

/**
 * The big ring. While a check runs it fills up like a progress bar; afterwards
 * it turns green, amber or red with a one-line summary.
 */
@Composable
private fun HealthHero(
    connected: Boolean, progress: Float?, problems: Int, watches: Int, checked: Boolean,
    onRun: () -> Unit, onConnect: () -> Unit,
) {
    val color = when {
        progress != null -> Ui.Accent
        problems > 0 -> Ui.Bad
        watches > 0 -> Ui.Watch
        checked -> Ui.Good
        else -> Ui.Line
    }
    val fill by animateFloatAsState(progress ?: if (checked) 1f else 0f, tween(600), label = "ring")
    val (headline, detail) = when {
        !connected -> "Not connected" to "Connect your adapter to check the car."
        progress != null -> "Checking…" to "Keep the engine idling in Park."
        !checked -> "Not checked yet" to "For best results, let the engine idle until fully warm."
        problems > 0 -> "Needs attention" to "$problems problem${if (problems > 1) "s" else ""}" +
            (if (watches > 0) ", $watches to watch" else "") + " found."
        watches > 0 -> "Mostly healthy" to "$watches thing${if (watches > 1) "s" else ""} to keep an eye on."
        else -> "Looks healthy" to "Everything checked is in the normal range."
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 10.dp.toPx()
                val stroke = Stroke(w, cap = StrokeCap.Round)
                val inset = Offset(w / 2, w / 2)
                val box = Size(size.width - w, size.height - w)
                drawArc(Ui.Card, 0f, 360f, false, inset, box, style = stroke)
                if (fill > 0f) drawArc(color, -90f, 360f * fill, false, inset, box, style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (progress != null) "${(progress * 100).toInt()}%" else headline,
                    style = if (progress != null) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(detail, color = Ui.Sub, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        when {
            !connected -> PrimaryButton("Connect adapter", onClick = onConnect)
            progress == null -> PrimaryButton(if (checked) "Run again" else "Run health check", onClick = onRun)
        }
    }
}
