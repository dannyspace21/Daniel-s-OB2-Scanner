package com.daniel.carscanner.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.net.BodyShape
import com.daniel.carscanner.net.CabSize
import com.daniel.carscanner.obd.HealthCheck
import com.daniel.carscanner.obd.Level
import com.daniel.carscanner.obd.Vin

/*
 * WHAT THIS FILE DOES
 * The main screen, laid out like the Tesla app. From top to bottom:
 *   - Vehicle name with a switcher (tap it to open your Garage), status line,
 *     flashlight button
 *   - The car drawing: right body style, doors and your paint color, animating
 *     lights, doors, windows, trunk and more. Tap it for Vehicle details.
 *   - Live view switch and status chips ("Door open", "Engine running"...)
 *   - Health card: green / amber / red ring with a one-line summary
 *   - Round quick actions: Health check, Codes, All modules, Live data
 *   - One row per car system with its status dot, then tools and settings
 */
@Composable
fun HomeScreen(vm: ScannerViewModel, go: (Screen) -> Unit) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val vin by vm.vin.collectAsStateWithLifecycle()
    val info by vm.vehicle.collectAsStateWithLifecycle()
    val paint by vm.paint.collectAsStateWithLifecycle()
    val car by vm.carState.collectAsStateWithLifecycle()
    val live by vm.liveView.collectAsStateWithLifecycle()
    val garage by vm.garage.collectAsStateWithLifecycle()
    val signals by vm.allSignals.collectAsStateWithLifecycle()
    val returned by vm.returnedCodes.collectAsStateWithLifecycle()
    val trip by vm.tripLive.collectAsStateWithLifecycle()
    val findings by vm.healthFindings.collectAsStateWithLifecycle()
    val progress by vm.healthProgress.collectAsStateWithLifecycle()
    val volts by vm.voltage.collectAsStateWithLifecycle()
    val modules by vm.modules.collectAsStateWithLifecycle()
    val recalls by vm.recalls.collectAsStateWithLifecycle()
    val connected = conn is Conn.Connected

    val vehicleName = vin?.let { v -> garage.firstOrNull { it.vin == v }?.nickname?.takeIf { it.isNotBlank() } }
        ?: info?.displayName ?: vin?.let { Vin.describe(it) } ?: if (connected) "Your vehicle" else "Add your vehicle"
    val status = when (val c = conn) {
        is Conn.Connected -> "Connected" + (volts?.let { ", battery %.1f V".format(it) } ?: "")
        is Conn.Connecting -> "Connecting to ${c.name}…"
        is Conn.Failed -> c.reason
        Conn.Disconnected -> "Not connected"
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        // Name, status and flashlight
        item {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    // Tap the name to switch cars, like the Tesla app
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable { go(Screen.Garage) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(vehicleName, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Filled.ExpandMore, contentDescription = "Switch car", tint = Ui.Sub)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        StatusDot(if (connected) Ui.Good else Ui.Sub, 8)
                        Spacer(Modifier.width(8.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = Ui.Sub, modifier = Modifier.weight(1f, fill = false))
                        // One tap to connect, right where you see "Not connected"
                        if (conn is Conn.Disconnected || conn is Conn.Failed) {
                            Spacer(Modifier.width(10.dp))
                            Text("Connect", color = Color.White, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Ui.Accent)
                                    .clickable { go(Screen.Connect) }.padding(horizontal = 12.dp, vertical = 5.dp))
                        }
                    }
                }
                TorchButton()
            }
        }

        // The car. Tap to open Vehicle details.
        item {
            Box(
                Modifier.fillMaxWidth().height(290.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { go(Screen.Vehicle) },
            ) {
                VehicleView(
                    shape = info?.shape ?: BodyShape.Sedan,
                    doors = info?.doorCount ?: 4,
                    cab = info?.cab ?: CabSize.Regular,
                    paint = Color(paint),
                    state = car,
                    modifier = Modifier.fillMaxSize(),
                    dim = info == null,
                )
                if (info == null) Text(
                    if (connected) "Reading your VIN…" else "Connect your adapter, or tap the name above to add a car",
                    color = Ui.Sub, modifier = Modifier.align(Alignment.BottomCenter), textAlign = TextAlign.Center,
                )
            }
        }

        // Live view switch and status chips
        item {
            val chips = car.statusChips()
            val mySignals = signals.count { it.vin == vin }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (connected) {
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(if (live) Ui.Accent else Ui.Raised)
                            .clickable { vm.setLiveView(!live) }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(if (live) Color.White else Ui.Sub, 7)
                        Spacer(Modifier.width(8.dp))
                        Text(if (live) "Live" else "Live view", color = if (live) Color.White else Ui.Text, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(chips) { c ->
                        Text(c, style = MaterialTheme.typography.labelMedium,
                            color = if (c.startsWith("Bulb out")) Ui.Bad else Ui.Text,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Ui.Card).padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
            if (live && mySignals == 0) Text(
                "Live view shows engine running for now. Set up body data to mirror doors, lights and more.",
                color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp)
                    .clickable { go(Screen.BodyData) },
            )
        }

        // Health card
        item {
            HealthCard(
                connected, progress,
                problems = findings.count { it.level == Level.Problem },
                watches = findings.count { it.level == Level.Watch },
                checked = findings.isNotEmpty(),
                onRun = { vm.runHealthCheck(); go(Screen.Health) },
                onOpen = { go(if (connected) Screen.Health else Screen.Connect) },
            )
        }

        // Codes that came back after being cleared
        items(returned, key = { "back" + it.placeKey + it.code }) { r ->
            Spacer(Modifier.height(10.dp))
            ReturnedBanner(r, onOpen = { go(Screen.Codes) }, onDismiss = { vm.dismissReturned(r) })
        }

        // Quick actions
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                // Greyed out until connected; tapping one then takes you to Connect instead of doing nothing.
                val toConnect = { go(Screen.Connect) }
                RoundAction(Icons.Filled.HealthAndSafety, "Health", connected, toConnect) { go(Screen.Health) }
                RoundAction(Icons.Filled.ReportProblem, "Codes", connected, toConnect) { go(Screen.Codes) }
                RoundAction(Icons.Filled.Memory, "Modules", connected, toConnect) { go(Screen.Modules) }
                RoundAction(Icons.Filled.Speed, "Live", connected, toConnect) { go(Screen.Live) }
            }
        }

        // One row per system, showing its worst finding
        item { SectionHeader("Systems") }
        items(HealthCheck.systems) { system ->
            val worst = findings.filter { it.system == system }.maxByOrNull { it.level.severity }
            NavRow(
                icon = systemIcon(system), title = system,
                subtitle = worst?.title ?: "Not checked yet", dot = worst?.level?.color,
            ) { go(if (system == HealthCheck.CODES) Screen.Codes else Screen.Health) }
        }

        // Every module in the car
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

        item { SectionHeader("Your car") }
        item { NavRow(Icons.Filled.Garage, "Garage", if (garage.isEmpty()) "Your cars appear here" else "${garage.size} car${if (garage.size > 1) "s" else ""}") { go(Screen.Garage) } }
        item {
            NavRow(Icons.Filled.DirectionsCar, "Vehicle & recalls",
                if (info == null) "Details, paint color and recalls" else if (recalls.isEmpty()) "No recalls found" else "${recalls.size} recalls on record",
                dot = if (recalls.isNotEmpty()) Ui.Watch else null) { go(Screen.Vehicle) }
        }
        item {
            NavRow(Icons.Filled.LocalGasStation, "Trips & MPG", if (trip != null) "Trip in progress" else "Fuel economy and trip log",
                dot = if (trip != null) Ui.Accent else null) { go(Screen.Trips) }
        }
        item { NavRow(Icons.Filled.EventNote, "Maintenance", "Oil changes, filters and fluids") { go(Screen.Maintenance) } }
        item { NavRow(Icons.Filled.History, "Scan history", "Past results and shareable reports") { go(Screen.History) } }
        item { SectionHeader("Tools") }
        item { NavRow(Icons.Filled.Speed, "Live data", "Gauges with normal ranges") { go(Screen.Live) } }
        item { NavRow(Icons.Filled.Sensors, "Body data", "Set up live doors, lights and windows") { go(Screen.BodyData) } }
        item { NavRow(Icons.Filled.Build, "Active tests", "Switch parts on to prove they work") { go(Screen.Tests) } }
        item { NavRow(Icons.Filled.Code, "Console", "Send raw commands") { go(Screen.Console) } }
        item {
            NavRow(Icons.Filled.Bluetooth, "Adapter", (conn as? Conn.Connected)?.name ?: "Not connected",
                dot = if (connected) Ui.Good else null) { go(Screen.Connect) }
        }
        item { SectionHeader("Settings") }
        item { NavRow(Icons.Filled.Info, "About & settings", "Version, appearance, privacy") { go(Screen.About) } }
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
 * Compact health card: a ring on the left (fills while checking, then turns
 * green, amber or red), a summary, and the Run button.
 */
@Composable
private fun HealthCard(
    connected: Boolean, progress: Float?, problems: Int, watches: Int, checked: Boolean,
    onRun: () -> Unit, onOpen: () -> Unit,
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
        !checked -> "Not checked yet" to "Best with the engine warm and idling."
        problems > 0 -> "Needs attention" to "$problems problem${if (problems > 1) "s" else ""}" +
            (if (watches > 0) ", $watches to watch" else "") + "."
        watches > 0 -> "Mostly healthy" to "$watches thing${if (watches > 1) "s" else ""} to keep an eye on."
        else -> "Looks healthy" to "Everything checked is normal."
    }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ui.Card).clickable(onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 6.dp.toPx()
                val inset = Offset(w / 2, w / 2)
                val box = Size(size.width - w, size.height - w)
                drawArc(Ui.Raised, 0f, 360f, false, inset, box, style = Stroke(w))
                if (fill > 0f) drawArc(color, -90f, 360f * fill, false, inset, box, style = Stroke(w, cap = StrokeCap.Round))
            }
            if (progress != null) Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = Ui.Sub, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start)
        }
        if (connected && progress == null) {
            Spacer(Modifier.width(8.dp))
            PrimaryButton(if (checked) "Again" else "Run", onClick = onRun)
        }
    }
}
