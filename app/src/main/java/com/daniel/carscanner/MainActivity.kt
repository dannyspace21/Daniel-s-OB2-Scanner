package com.daniel.carscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import android.content.res.Configuration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daniel.carscanner.ui.LocalTorch
import com.daniel.carscanner.ui.ThemeMode
import com.daniel.carscanner.ui.Torch
import com.daniel.carscanner.ui.Ui
import android.content.Intent
import com.daniel.carscanner.alerts.Notifier
import com.daniel.carscanner.alerts.RecallWorker
import com.daniel.carscanner.ui.AppRoot
import com.daniel.carscanner.ui.CarScannerTheme
import com.daniel.carscanner.ui.ScannerViewModel

/*
 * WHAT THIS FILE DOES
 * The app's entry point. Android starts here when you tap the icon. It:
 *  1. keeps the screen on (handy in the garage)
 *  2. asks for Bluetooth permission if needed
 *  3. sets up day/night mode and the flashlight
 *  4. shows the app's screens (AppRoot)
 *  5. turns the flashlight off when you leave the app
 *  6. starts the daily recall check, and asks once for notification permission
 *  7. opens the right car and screen when you tap a notification
 */
class MainActivity : ComponentActivity() {

    private val vm: ScannerViewModel by viewModels()
    private val hasPermission = mutableStateOf(false)
    private lateinit var torch: Torch

    private val perms: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    // Notifications are asked for alongside Bluetooth, but only Bluetooth decides "hasPermission".
    private val notifyPerms: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasPermission.value = perms.all { res[it] == true || checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (hasPermission.value) vm.refreshDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hasPermission.value = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val missingNotify = notifyPerms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (hasPermission.value) {
            vm.refreshDevices()
            if (missingNotify.isNotEmpty()) launcher.launch(missingNotify.toTypedArray())
        } else launcher.launch(perms + missingNotify)

        // Daily recall check (runs in the background even when the app is closed)
        Notifier.ensureChannels(this)
        RecallWorker.schedule(this)
        handleOpen(intent)
        torch = Torch(this)

        // Pick the right colors before the first frame, so there's no flash of the wrong theme.
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        Ui.use(when (vm.themeMode.value) { ThemeMode.System -> systemDark; ThemeMode.Light -> false; ThemeMode.Dark -> true })

        setContent {
            val mode by vm.themeMode.collectAsStateWithLifecycle()
            CarScannerTheme(mode) {
                CompositionLocalProvider(LocalTorch provides torch) {
                    AppRoot(vm, hasPermission.value) { launcher.launch(perms) }
                }
            }
        }
    }

    /** A notification was tapped while the app was already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** Reads "open this car on this screen" from a notification. */
    private fun handleOpen(intent: Intent?) {
        val screen = intent?.getStringExtra(EXTRA_SCREEN) ?: return
        vm.openRequest.value = intent.getStringExtra(EXTRA_VIN) to screen
        intent.removeExtra(EXTRA_SCREEN)
    }

    companion object {
        const val EXTRA_VIN = "open_vin"
        const val EXTRA_SCREEN = "open_screen"
    }

    /** Leaving the app (home button, switching apps): turn the flashlight off. */
    override fun onStop() {
        super.onStop()
        if (torch.isOn) torch.set(false)
    }

    override fun onDestroy() {
        torch.release()
        super.onDestroy()
    }
}
