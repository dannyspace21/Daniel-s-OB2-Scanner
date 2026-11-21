package com.daniel.carscanner.ui

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.data.HistoryEntry
import com.daniel.carscanner.data.Reports

/*
 * WHAT THIS FILE DOES
 * Every code read, health check and module scan for the current car, newest
 * first. Tap an entry to see details. "Share report" opens Android's share
 * menu so you can text, email or post a clean summary for a mechanic.
 */
@Composable
fun HistoryScreen(vm: ScannerViewModel, onBack: () -> Unit) {
    val all by vm.history.collectAsStateWithLifecycle()
    val vin by vm.vin.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val entries = all.filter { it.vin == (vin ?: "unknown") }.sortedByDescending { it.time }

    /** Opens the share sheet with a text report. */
    fun share(list: List<HistoryEntry>) {
        val text = Reports.build(vin?.let { vm.nameFor(it) } ?: "Vehicle", vin ?: "unknown", list)
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        context.startActivity(Intent.createChooser(send, "Share report"))
    }

    Page("Scan history", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                if (entries.isEmpty()) Text("Nothing yet. Reading codes, running a health check or scanning modules adds an entry here automatically.", color = Ui.Sub)
                else PrimaryButton("Share full report", modifier = Modifier.fillMaxWidth()) { share(entries) }
            }
            items(entries, key = { it.time.toString() + it.kind }) { e ->
                var open by remember { mutableStateOf(false) }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Card)
                        .clickable { open = !open }.animateContentSize().padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(e.level.color)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${e.kind}: ${e.title}", style = MaterialTheme.typography.bodyLarge)
                            Text(Reports.date(e.time), color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (open) {
                        Spacer(Modifier.height(8.dp))
                        if (e.lines.isEmpty()) Text("No details.", color = Ui.Sub)
                        e.lines.forEach { Text("•  $it", modifier = Modifier.padding(vertical = 2.dp)) }
                        Row(Modifier.padding(top = 8.dp)) {
                            TextButton(onClick = { share(listOf(e)) }) { Text("Share", color = Ui.Accent) }
                            TextButton(onClick = { vm.deleteHistory(e) }) { Text("Delete", color = Ui.Sub) }
                        }
                    }
                }
            }
        }
    }
}
