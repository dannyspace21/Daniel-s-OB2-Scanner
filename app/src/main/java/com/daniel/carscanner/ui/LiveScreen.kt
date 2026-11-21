@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.daniel.carscanner.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.obd.EngineState
import com.daniel.carscanner.obd.HealthCheck
import com.daniel.carscanner.obd.Level
import com.daniel.carscanner.obd.Pid
import com.daniel.carscanner.obd.Pids
import com.daniel.carscanner.net.Related
import kotlin.math.abs

/*
 * WHAT THIS FILE DOES
 * Live gauges. Each gauge's arc turns green, amber or red based on the
 * normal ranges in Health.kt (blue = we can't judge it right now, for example
 * fuel trims before the engine is warm). Tap a gauge to see what it measures
 * and what's normal; if it's amber or red, the sheet also offers
 * "Related for your car".
 */

@Composable
fun LiveScreen(vm: ScannerViewModel) {
    val selected by vm.selectedPids.collectAsStateWithLifecycle()
    val supported by vm.supported.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val polling by vm.polling.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Pid?>(null) }
    var related by remember { mutableStateOf<Pid?>(null) }

    // Stop reading when you leave this screen.
    DisposableEffect(Unit) { onDispose { vm.stopPolling() } }

    // What the engine is doing right now decides which ranges apply.
    val state = EngineState.from(live)
    val shown = Pids.all.filter { it.code in selected }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(if (polling) "Pause" else "Start", modifier = Modifier.weight(1f)) {
                    if (polling) vm.stopPolling() else vm.startPolling()
                }
                SecondaryButton(if (editing) "Done" else "Choose gauges", modifier = Modifier.weight(1f)) { editing = !editing }
            }
        }

        if (editing) {
            // Gauge picker. Sensors your car doesn't have are greyed out.
            items(Pids.all, span = { GridItemSpan(2) }) { pid ->
                val available = supported.isEmpty() || pid.code in supported
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = available) { vm.togglePid(pid.code) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(pid.code in selected, { vm.togglePid(pid.code) }, enabled = available,
                        colors = CheckboxDefaults.colors(checkedColor = Ui.Accent))
                    Text(pid.name, color = if (available) Ui.Text else Ui.Sub)
                    if (!available) Text("  not on this car", color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            items(shown, key = { it.code }) { pid ->
                val v = live[pid.code]
                val level = if (v == null) Level.Unknown else HealthCheck.levelFor(pid.code, v, state)
                Gauge(pid, v, supported.isEmpty() || pid.code in supported, level) { info = pid }
            }
            if (!polling && live.isEmpty()) item(span = { GridItemSpan(2) }) {
                Text("Tap Start to begin reading.", color = Ui.Sub)
            }
        }
    }

    // "What is this?" sheet
    info?.let { pid ->
        ModalBottomSheet(onDismissRequest = { info = null }, containerColor = Ui.Card) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 40.dp)) {
                Text(pid.name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                Text(pid.about)
                Spacer(Modifier.height(16.dp))
                Text("Normal", color = Ui.Sub, style = MaterialTheme.typography.labelMedium)
                Text(pid.normal)
                val v = live[pid.code]
                val level = if (v == null) Level.Unknown else HealthCheck.levelFor(pid.code, v, state)
                if (level == Level.Watch || level == Level.Problem) {
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Related for your car", modifier = Modifier.fillMaxWidth()) { info = null; related = pid }
                }
            }
        }
    }

    related?.let { pid ->
        val v = live[pid.code]
        val word = if (v != null && HealthCheck.levelFor(pid.code, v, state) == Level.Problem) "problem" else "out of range"
        RelatedSheet(vm, topic = "${pid.name.lowercase()} $word", keywords = Related.keywordsForPid(pid.code)) { related = null }
    }
}

/** One gauge tile: colored arc, big number, unit, name. */
@Composable
private fun Gauge(pid: Pid, value: Double?, available: Boolean, level: Level, onTap: () -> Unit) {
    val target = value?.let { ((it - pid.min) / (pid.max - pid.min)).coerceIn(0.0, 1.0).toFloat() } ?: 0f
    val frac by animateFloatAsState(target, label = "arc")   // smooth needle movement
    val arcColor by animateColorAsState(if (level == Level.Unknown) Ui.Accent else level.color, label = "color")

    Column(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Ui.Card).clickable(onClick = onTap).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.35f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 7.dp.toPx()
                val d = minOf(size.width, size.height * 1.3f) - w
                val topLeft = Offset((size.width - d) / 2, w / 2)
                val stroke = Stroke(w, cap = StrokeCap.Round)
                drawArc(Ui.Line, 150f, 240f, false, topLeft, Size(d, d), style = stroke)
                if (value != null) drawArc(arcColor, 150f, 240f * frac, false, topLeft, Size(d, d), style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        !available -> "n/a"
                        value == null -> "–"
                        pid.unit == "λ" -> "%.2f".format(value)
                        abs(value) >= 100 -> "%.0f".format(value)
                        else -> "%.1f".format(value)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(pid.unit, color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (level != Level.Unknown) { StatusDot(level.color, 7); Spacer(Modifier.width(6.dp)) }
            Text(pid.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}
