package com.daniel.carscanner.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.obd.Finding
import com.daniel.carscanner.obd.HealthCheck
import com.daniel.carscanner.obd.Level

/*
 * WHAT THIS FILE DOES
 * Runs the health check and shows the results grouped by system.
 * Each finding is a card with a colored bar (green / amber / red).
 * Tap a card to see what it means and the most likely causes, most common first.
 */

@Composable
fun HealthScreen(vm: ScannerViewModel, onBack: () -> Unit, openCodes: () -> Unit) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val findings by vm.healthFindings.collectAsStateWithLifecycle()
    val progress by vm.healthProgress.collectAsStateWithLifecycle()

    Page("Health check", onBack) {
        if (conn !is Conn.Connected && findings.isEmpty()) {
            Text("Connect your adapter first.", color = Ui.Sub, modifier = Modifier.padding(24.dp))
            return@Page
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                if (progress != null) {
                    Text("Checking your car…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Ui.Accent, trackColor = Ui.Card,
                    )
                    Text("Keep the engine idling in Park with the A/C off. About 20 seconds.",
                        color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                } else {
                    PrimaryButton(if (findings.isEmpty()) "Run health check" else "Run again",
                        enabled = conn is Conn.Connected, modifier = Modifier.fillMaxWidth()) { vm.runHealthCheck() }
                    if (findings.isEmpty()) Text(
                        "Best results: engine fully warm, idling in Park, A/C off. Takes about 20 seconds.",
                        color = Ui.Sub, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            // Results grouped by system, in the same order as Home
            for (system in HealthCheck.systems) {
                val group = findings.filter { it.system == system }
                if (group.isEmpty()) continue
                item {
                    Text(system, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
                }
                items(group) { f -> FindingCard(f, if (system == HealthCheck.CODES) openCodes else null) }
            }

            if (findings.isNotEmpty()) item {
                Text(
                    "Ranges are typical for gasoline engines, not factory specs for your exact car. " +
                        "Amber and red mean \"look here first\", not a final diagnosis.",
                    color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
    }
}

/** One result. Tap to expand the explanation and likely causes. */
@Composable
private fun FindingCard(f: Finding, onOpen: (() -> Unit)?) {
    var open by remember { mutableStateOf(f.level == Level.Problem) }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(16.dp)).background(Ui.Card)
            .clickable { if (onOpen != null) onOpen() else open = !open }
            .animateContentSize(),
    ) {
        // Colored status bar on the left edge
        Box(Modifier.width(5.dp).fillMaxHeight().background(f.level.color))
        Column(Modifier.padding(16.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    when (f.level) { Level.Good -> "Normal"; Level.Watch -> "Watch"; Level.Problem -> "Problem"; Level.Unknown -> "Info" },
                    color = f.level.color, style = MaterialTheme.typography.labelMedium,
                )
            }
            if (f.reading.isNotBlank()) Text(f.reading, color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
            if (open) {
                Spacer(Modifier.height(10.dp))
                Text(f.meaning, style = MaterialTheme.typography.bodyMedium)
                if (f.causes.isNotEmpty()) {
                    Text("Likely causes, most common first", color = Ui.Sub, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    f.causes.forEachIndexed { i, c ->
                        Text("${i + 1}.  $c", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
    }
}
