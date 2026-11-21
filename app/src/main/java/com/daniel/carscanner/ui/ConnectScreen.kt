package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/*
 * WHAT THIS FILE DOES
 * Finds your OBD adapter and connects to it.
 * - Classic adapters show up if they're already paired in Android settings.
 * - BLE adapters show up after tapping "Scan".
 * Likely OBD adapters (names with OBD, ELM, Vgate, Veepeak...) are listed first.
 */

@Composable
fun ConnectScreen(vm: ScannerViewModel, hasPermission: Boolean, requestPermission: () -> Unit, onBack: () -> Unit) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val vin by vm.vin.collectAsStateWithLifecycle()

    Page("Adapter", onBack) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Permission prompt
            if (!hasPermission) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Card).padding(20.dp)) {
                    Text("Allow Bluetooth", style = MaterialTheme.typography.titleMedium)
                    Text("Needed to find and talk to your adapter.", color = Ui.Sub, modifier = Modifier.padding(vertical = 8.dp))
                    PrimaryButton("Allow Bluetooth", onClick = requestPermission)
                }
            }

            // Current status
            item {
                when (val c = conn) {
                    is Conn.Connected -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.Card).padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(Ui.Good); Spacer(Modifier.width(10.dp))
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                        }
                        Detail("Adapter", c.adapterVersion)
                        Detail("Protocol", c.protocol)
                        Detail("VIN", vin ?: "Not reported")
                        Spacer(Modifier.height(16.dp))
                        SecondaryButton("Disconnect", onClick = vm::disconnect)
                    }
                    is Conn.Connecting -> Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Ui.Accent)
                        Spacer(Modifier.width(14.dp))
                        Text("Connecting to ${c.name}. Up to 15 seconds while it finds your car's protocol.", color = Ui.Sub)
                    }
                    is Conn.Failed -> Column(Modifier.padding(vertical = 4.dp)) {
                        Text("Couldn't connect", color = Ui.Bad, style = MaterialTheme.typography.titleMedium)
                        Text(c.reason, color = Ui.Sub)
                    }
                    Conn.Disconnected -> Text(
                        "Plug the adapter in under the dash and turn the ignition on. Classic adapters must be paired in " +
                            "Android Bluetooth settings first (PIN is usually 1234 or 0000). BLE adapters don't need pairing.",
                        color = Ui.Sub,
                    )
                }
            }

            if (conn !is Conn.Connected) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        PrimaryButton(if (scanning) "Stop scan" else "Scan for BLE", enabled = hasPermission, modifier = Modifier.weight(1f)) {
                            if (scanning) vm.stopBleScan() else vm.startBleScan()
                        }
                        SecondaryButton("Refresh", enabled = hasPermission, modifier = Modifier.weight(1f), onClick = vm::refreshDevices)
                    }
                    if (hasPermission && !vm.bluetoothOn) Text("Bluetooth is off. Turn it on in quick settings.", color = Ui.Bad)
                }
                if (devices.isEmpty()) item { Text("No adapters found yet.", color = Ui.Sub) }
                items(devices, key = { it.address + it.ble }) { d ->
                    NavRow(
                        icon = Icons.Filled.Bluetooth,
                        title = d.name,
                        subtitle = (if (d.ble) "BLE" else if (d.paired) "Paired" else "Classic") + ", " + d.address,
                        dot = if (d.looksLikeObd) Ui.Accent else null,
                    ) { if (conn !is Conn.Connecting) vm.connect(d) }
                }
            }
        }
    }
}

/** A label/value line inside the connected card. */
@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.padding(top = 8.dp)) {
        Text(label, color = Ui.Sub, modifier = Modifier.width(84.dp))
        Text(value)
    }
}
