package com.daniel.carscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/*
 * WHAT THIS FILE DOES
 * Moves between screens. Like the Tesla app there's no tab bar: Home is the
 * hub, you tap into a screen, and the back arrow (or Android back gesture)
 * returns you. Screens slide in from the right and out to the left.
 */

/** Every screen in the app. */
enum class Screen { Home, Connect, Health, Live, Codes, Modules, Tests, Console, Vehicle, About, Garage, BodyData, History, Maintenance, Trips, Monitors }

@Composable
fun AppRoot(vm: ScannerViewModel, hasPermission: Boolean, requestPermission: () -> Unit) {
    // The "stack" of screens you've opened. The last one is what you see.
    var stack by remember { mutableStateOf(listOf(Screen.Home)) }
    var forward by remember { mutableStateOf(true) }
    val conn by vm.conn.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    fun go(s: Screen) { forward = true; stack = stack + s }
    fun back() { forward = false; stack = stack.dropLast(1) }

    BackHandler(enabled = stack.size > 1) { back() }

    // Show one-off messages ("Codes cleared.") at the bottom.
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.message.value = null }
    }
    // A notification or the widget asked to open a car and screen (e.g. a new recall)
    val openRequest by vm.openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openRequest) {
        openRequest?.let { (v, screenName) ->
            v?.let { vm.switchCar(it) }
            val target = Screen.entries.firstOrNull { it.name == screenName } ?: Screen.Home
            forward = true
            stack = if (target == Screen.Home) listOf(Screen.Home) else listOf(Screen.Home, target)
            vm.openRequest.value = null
        }
    }

    // After a successful connection, jump back Home.
    LaunchedEffect(conn) {
        if (conn is Conn.Connected && stack.last() == Screen.Connect) back()
    }

    Scaffold(containerColor = Ui.Bg, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        AnimatedContent(
            targetState = stack.last(),
            modifier = Modifier.padding(pad).fillMaxSize(),
            transitionSpec = {
                if (forward) (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                else (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
            },
            label = "screens",
        ) { screen ->
            val connected = conn is Conn.Connected
            when (screen) {
                Screen.Home -> HomeScreen(vm, ::go)
                Screen.Connect -> ConnectScreen(vm, hasPermission, requestPermission, ::back)
                Screen.Health -> HealthScreen(vm, ::back) { go(Screen.Codes) }
                Screen.Live -> Page("Live data", ::back) { if (connected) LiveScreen(vm) else NeedsConnection { go(Screen.Connect) } }
                Screen.Monitors -> Page("Monitor test results", ::back) { if (connected) MonitorTestsScreen(vm) else NeedsConnection { go(Screen.Connect) } }
                Screen.Codes -> Page("Trouble codes", ::back) { if (connected) CodesScreen(vm) else NeedsConnection { go(Screen.Connect) } }
                Screen.Vehicle -> VehicleScreen(vm, ::back) { key -> vm.testDraftVisual.value = key; go(Screen.Tests) }
                Screen.About -> AboutScreen(vm, ::back)
                Screen.Garage -> GarageScreen(vm, ::back) { go(Screen.Connect) }
                Screen.BodyData -> BodyDataScreen(vm, ::back)
                Screen.History -> HistoryScreen(vm, ::back)
                Screen.Maintenance -> MaintenanceScreen(vm, ::back)
                Screen.Trips -> TripScreen(vm, ::back)
                Screen.Modules -> Page("All modules", ::back) { if (connected) ModulesScreen(vm) else NeedsConnection { go(Screen.Connect) } }
                Screen.Tests -> Page("Active tests", ::back) { TestsScreen(vm) }   // set up tests even when not connected
                Screen.Console -> Page("Console", ::back) { if (connected) ConsoleScreen(vm) else NeedsConnection { go(Screen.Connect) } }
            }
        }
    }
}
