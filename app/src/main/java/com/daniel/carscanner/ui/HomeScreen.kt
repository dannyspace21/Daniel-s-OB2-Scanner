package com.daniel.carscanner.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.scale
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
 * The main screen, laid out like a workshop scan tool. From top to bottom:
 *   - Vehicle name with a switcher (tap it to open your Garage), status line,
 *     Connect button, flashlight
 *   - Update card when a newer build is on GitHub
 *   - The car drawing (tap for Vehicle details), Live view switch and chips
 *   - Health card: green / amber / red ring with a one-line summary
 *   - "Full system scan": one tap reads every module in the car
 *   - Function tiles in sections: Diagnose, Bidirectional, Service,
 *     Reports & settings. Tiles show badges for codes and recalls.
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

    // For the function tiles below
    val stored by vm.storedCodes.collectAsStateWithLifecycle()
    val pending by vm.pendingCodes.collectAsStateWithLifecycle()
    val modulesWithCodes = modules.count { it.codes.isNotEmpty() }
    val mySignals = signals.count { it.vin == vin }
    val needConnect: (Screen) -> Unit = { target -> go(if (connected) target else Screen.Connect) }

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

        // A newer build is on GitHub
        item { UpdateCard(vm) }

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
            }
            // Below the drawing, not on top of it
            if (info == null) Text(
                if (connected) "Reading your VIN…" else "Connect your adapter, or tap the name above to add a car",
                color = Ui.Sub, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center,
            )
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
                "Live view shows engine running for now. Tap here to set up doors, brake lights and more automatically.",
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

        // Full system scan: the main action, like a workshop scan tool
        item {
            val scanning by vm.moduleScanStatus.collectAsStateWithLifecycle()
            Spacer(Modifier.height(14.dp))
            HeroAction(
                icon = Icons.Filled.ManageSearch,
                title = "Full system scan",
                subtitle = scanning ?: if (connected) "Every module: engine, ABS, airbags, body and more" else "Connect your adapter to start",
                enabled = connected,
            ) {
                if (connected) { vm.scanModules(deep = info?.make?.uppercase() in setOf("TOYOTA", "LEXUS", "SCION")); go(Screen.Modules) }
                else go(Screen.Connect)
            }
        }

        // Function tiles, grouped like a scan tool's main menu

        item { SectionHeader("Diagnose") }
        item {
            TileRow(
                Tile(Icons.Filled.ReportProblem, "Trouble codes", when {
                    stored.isNotEmpty() -> "${stored.size} stored"
                    pending.isNotEmpty() -> "${pending.size} pending"
                    else -> "Read and clear"
                }, badge = (stored.size + pending.size).takeIf { it > 0 }, badgeColor = if (stored.isNotEmpty()) Ui.Bad else Ui.Watch) { needConnect(Screen.Codes) },
                Tile(Icons.Filled.Memory, "All systems", if (modules.isEmpty()) "Every module" else "${modules.size} found",
                    badge = modulesWithCodes.takeIf { it > 0 }, badgeColor = Ui.Bad) { needConnect(Screen.Modules) },
            )
        }
        item {
            TileRow(
                Tile(Icons.Filled.HealthAndSafety, "Health check", if (findings.isEmpty()) "20-second check" else "Last result ready") { needConnect(Screen.Health) },
                Tile(Icons.Filled.Speed, "Live data", "Gauges and graphs") { needConnect(Screen.Live) },
            )
        }
        item {
            TileRow(
                Tile(Icons.Filled.Checklist, "Monitor tests", "Self-test results (mode 06)") { needConnect(Screen.Monitors) },
                Tile(Icons.Filled.Garage, "Garage", if (garage.isEmpty()) "Your cars" else "${garage.size} car${if (garage.size > 1) "s" else ""}") { go(Screen.Garage) },
            )
        }

        item { SectionHeader("Bidirectional") }
        item {
            TileRow(
                Tile(Icons.Filled.Build, "Active tests", "Switch parts on to test them") { go(Screen.Tests) },
                Tile(Icons.Filled.Sensors, "Body live view", if (mySignals == 0) "Set up automatically" else "$mySignals signals") { go(Screen.BodyData) },
            )
        }
        item {
            TileRow(
                Tile(Icons.Filled.DirectionsCar, "Vehicle & recalls", if (recalls.isEmpty()) "Details and controls" else "${recalls.size} recalls",
                    badge = recalls.size.takeIf { it > 0 }, badgeColor = Ui.Watch) { go(Screen.Vehicle) },
                Tile(Icons.Filled.Code, "Console", "Raw commands") { go(Screen.Console) },
            )
        }

        item { SectionHeader("Service") }
        item {
            TileRow(
                Tile(Icons.Filled.EventNote, "Maintenance", "Oil, filters, fluids") { go(Screen.Maintenance) },
                Tile(Icons.Filled.LocalGasStation, "Trips & MPG", if (trip != null) "Trip running" else "Fuel economy") { go(Screen.Trips) },
            )
        }

        item { SectionHeader("Reports & settings") }
        item {
            TileRow(
                Tile(Icons.Filled.History, "Reports", "Scan history to share") { go(Screen.History) },
                Tile(Icons.Filled.Bluetooth, "Adapter", (conn as? Conn.Connected)?.name ?: "Not connected",
                    badgeColor = Ui.Good, dot = connected) { go(Screen.Connect) },
            )
        }
        item {
            TileRow(
                Tile(Icons.Filled.Settings, "Settings", "Updates, appearance, about") { go(Screen.About) },
                null,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
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

/** One function tile on Home. badge = a count shown in the corner (codes, recalls). */
private class Tile(
    val icon: ImageVector, val title: String, val subtitle: String,
    val badge: Int? = null, val badgeColor: Color = Ui.Accent, val dot: Boolean = false,
    val onClick: () -> Unit,
)

/** Two tiles side by side. */
@Composable
private fun TileRow(a: Tile, b: Tile?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TileCard(a, Modifier.weight(1f))
        if (b != null) TileCard(b, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
    }
}

/** A square-ish card: icon in a colored circle, title, one-line status, optional badge. */
@Composable
private fun TileCard(t: Tile, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val tap = rememberTapFeedback()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "tile")
    Box(
        modifier.height(118.dp).scale(scale).clip(RoundedCornerShape(18.dp)).background(Ui.Card)
            .clickable(interactionSource = source, indication = LocalIndication.current) { tap(); t.onClick() }
            .padding(14.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Ui.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(t.icon, contentDescription = null, tint = Ui.Accent, modifier = Modifier.size(22.dp))
            }
            Column {
                Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = Ui.Sub, maxLines = 1)
            }
        }
        when {
            t.badge != null -> Text(
                if (t.badge > 99) "99+" else t.badge.toString(), color = Color.White, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(10.dp)).background(t.badgeColor)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            t.dot -> Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { StatusDot(t.badgeColor, 10) }
        }
    }
}

/** The big full-width action at the top of the menu. */
@Composable
private fun HeroAction(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val tap = rememberTapFeedback()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "hero")
    Row(
        Modifier.fillMaxWidth().scale(scale).clip(RoundedCornerShape(20.dp))
            .background(if (enabled) Ui.Accent else Ui.Card)
            .clickable(interactionSource = source, indication = LocalIndication.current) { tap(); onClick() }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) Color.White else Ui.Accent, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = if (enabled) Color.White else Ui.Text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) Color.White.copy(alpha = 0.85f) else Ui.Sub, maxLines = 2)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = if (enabled) Color.White else Ui.Sub)
    }
}
