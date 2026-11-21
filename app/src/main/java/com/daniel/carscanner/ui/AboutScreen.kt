package com.daniel.carscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.BuildConfig
import com.daniel.carscanner.net.BodyShape
import com.daniel.carscanner.net.CabSize
import com.daniel.carscanner.obd.CarState
import com.daniel.carscanner.obd.DtcLibrary
import com.daniel.carscanner.obd.Element

/*
 * WHAT THIS FILE DOES
 * The About & settings screen:
 *  - app version and build date, so you know which APK you're running
 *  - Appearance: System / Light / Dark
 *  - Alerts: daily recall check on/off (asks for notification permission)
 *  - what the status colors mean
 *  - how the health check works, privacy, safety
 *  - where the data comes from, and open-source credits
 */
@Composable
fun AboutScreen(vm: ScannerViewModel, onBack: () -> Unit) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    val alerts by vm.recallAlerts.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (!granted) vm.message.value = "Notifications are off, so alerts can't show. You can allow them in Android settings." }

    Page("About & settings", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
            // Header
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    VehicleView(BodyShape.Coupe, 2, CabSize.Regular, Color(0xFF2F5BD3),
                        CarState().with(Element.HeadlightL, 1f).with(Element.HeadlightR, 1f), modifier = Modifier.size(140.dp))
                    Text("Daniel's Car Mechanic Scanner", style = MaterialTheme.typography.titleLarge)
                    Text("Version ${BuildConfig.VERSION_NAME}, built ${BuildConfig.BUILD_DATE}", color = Ui.Sub)
                }
            }

            // Appearance
            item {
                Section("Appearance")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        if (m == mode) PrimaryButton(m.name, modifier = Modifier.weight(1f)) { }
                        else SecondaryButton(m.name, modifier = Modifier.weight(1f)) { vm.setTheme(m) }
                    }
                }
                Text("System follows your phone's dark mode setting.", color = Ui.Sub, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
            }

            // Alerts
            item {
                Section("Alerts")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Recall alerts")
                        Text("Checks NHTSA once a day for new recalls on every car in your garage.",
                            color = Ui.Sub, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(alerts, { on ->
                        vm.setRecallAlerts(on)
                        if (on && android.os.Build.VERSION.SDK_INT >= 33 && !com.daniel.carscanner.alerts.Notifier.allowed(context)) {
                            askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    })
                }
                Text("You'll also get an alert if a trouble code you cleared comes back.",
                    color = Ui.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }

            // Color legend
            item {
                Section("What the colors mean")
                Legend(Ui.Good, "Green", "Normal.")
                Legend(Ui.Watch, "Amber", "Worth keeping an eye on, or a pending code.")
                Legend(Ui.Bad, "Red", "A problem, or a confirmed code. Look here first.")
                Legend(Ui.Sub, "Grey", "Can't judge right now, for example before the engine is warm.")
                Legend(Ui.Accent, "Blue", "Buttons, and gauges with no normal range.")
            }

            item {
                Section("How the health check works")
                Para("It reads your sensors for about 20 seconds at idle, then compares them with typical ranges for gasoline engines. " +
                    "Each amber or red result explains what it means and lists likely causes, most common first. " +
                    "These are rules of thumb, not factory specs for your exact car, so treat them as \"look here first.\"")
            }

            item {
                Section("Privacy")
                Para("The app uses the internet only to decode your VIN and look up recalls with NHTSA (the U.S. government), " +
                    "and to open links you tap. There are no accounts, ads or tracking. Your scans, tests and settings stay on this phone.")
            }

            item {
                Section("Safety")
                Para("Run active tests only with the car in Park and the parking brake on, and keep hands and clothing clear of fans and belts. " +
                    "Clearing codes erases the record, it doesn't fix the cause. An airbag light means the airbags may not deploy; get it repaired. " +
                    "Leave Expert mode off unless you know exactly what a command does. " +
                    "Unplug the adapter when you're not using it, since cheap adapters let anyone nearby connect.")
            }

            item {
                Section("Data and credits")
                Para("Vehicle details and recalls: NHTSA vPIC and Recalls APIs (U.S. government, public domain).")
                Para("Standard trouble-code definitions (${DtcLibrary.size} codes): the python-OBD project, GPL-2.0.")
                Para("Built with Jetpack Compose, AndroidX and Kotlin coroutines (Apache License 2.0).")
                Spacer(Modifier.height(16.dp))
                Text("Made for Daniel.", color = Ui.Sub)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
}

@Composable
private fun Para(text: String) {
    Text(text, color = Ui.Text, modifier = Modifier.padding(bottom = 8.dp))
}

/** One color explanation row. */
@Composable
private fun Legend(color: Color, name: String, meaning: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Ui.Card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color, 12)
        Spacer(Modifier.width(12.dp))
        Text(name, modifier = Modifier.width(56.dp))
        Text(meaning, color = Ui.Sub)
    }
}
