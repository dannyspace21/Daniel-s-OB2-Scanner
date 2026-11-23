package com.daniel.carscanner.ui

import androidx.compose.foundation.background
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
import com.daniel.carscanner.obd.MonitorTest
import kotlin.math.abs

/*
 * WHAT THIS FILE DOES
 * The "Monitor tests" screen (mode 06): every emissions self-test the engine
 * computer has run, grouped by monitor (oxygen sensors, catalytic converter,
 * EVAP, misfire per cylinder...). Each row shows the measured value, the
 * allowed range, and pass / fail. Amber = passed, but within 10% of a limit, so
 * worth watching before it turns into a check engine light.
 */
@Composable
fun MonitorTestsScreen(vm: ScannerViewModel) {
    val tests by vm.monitorTests.collectAsStateWithLifecycle()
    val progress by vm.monitorProgress.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    // Read automatically the first time the screen opens.
    LaunchedEffect(Unit) { if (tests == null) vm.readMonitorTests() }

    val list = tests.orEmpty()
    val failed = list.count { !it.passed }
    val close = list.count { it.nearLimit }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "The engine computer tests its own emissions parts while you drive and keeps the last result of each test. " +
                    "A part close to its limit shows up here before the check engine light comes on.",
                color = Ui.Sub,
            )
            Spacer(Modifier.height(14.dp))
            if (progress != null) {
                Text("Reading test results…", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Ui.Accent, trackColor = Ui.Card,
                )
            } else PrimaryButton(if (tests == null) "Read test results" else "Read again", enabled = busy == null,
                modifier = Modifier.fillMaxWidth(), onClick = vm::readMonitorTests)
        }

        // Summary
        if (list.isNotEmpty() && progress == null) item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Count(failed, "failed", Ui.Bad)
                Count(close, "near a limit", Ui.Watch)
                Count(list.size - failed - close, "passed", Ui.Good)
            }
        }

        // One card per monitor, failed ones first
        val groups = list.groupBy { it.mid }.values.sortedWith(
            compareByDescending<List<MonitorTest>> { g -> g.count { !it.passed } }.thenBy { it.first().mid }
        )
        items(groups, key = { it.first().mid }) { group ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Card).padding(16.dp)) {
                Text(group.first().monitor, style = MaterialTheme.typography.titleMedium)
                group.forEach { t -> TestRow(t) }
            }
        }

        if (list.isNotEmpty()) item {
            Text(
                "Limits come from the car itself. Tests marked \"Manufacturer test\" are specific to your car's maker; " +
                    "their pass or fail is still correct even when the units aren't known.",
                color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Count(n: Int, label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (n > 0) color else Ui.Line, 10)
        Spacer(Modifier.width(6.dp))
        Text("$n $label", style = MaterialTheme.typography.labelLarge, color = if (n > 0) Ui.Text else Ui.Sub)
    }
}

/** One test: name, value, allowed range, and a colored status. */
@Composable
private fun TestRow(t: MonitorTest) {
    val near = t.nearLimit
    val color = when { !t.passed -> Ui.Bad; near -> Ui.Watch; else -> Ui.Good }
    fun f(x: Double) = when {
        !t.known -> x.toLong().toString()
        x == 0.0 -> "0"
        abs(x) >= 100 -> "%.0f".format(x)
        abs(x) >= 1 -> "%.2f".format(x)
        else -> "%.3f".format(x)
    }
    val unit = if (t.unit.isBlank()) "" else " ${t.unit}"
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.test, style = MaterialTheme.typography.bodyMedium)
            Text("Allowed ${f(t.min)} to ${f(t.max)}$unit", color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(f(t.value) + unit, style = MaterialTheme.typography.bodyMedium)
            Text(when { !t.passed -> "Failed"; near -> "Near limit"; else -> "Passed" }, color = color,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}
