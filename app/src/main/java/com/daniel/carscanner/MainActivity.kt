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
import androidx.compose.runtime.mutableStateOf
import com.daniel.carscanner.ui.AppRoot
import com.daniel.carscanner.ui.CarScannerTheme
import com.daniel.carscanner.ui.ScannerViewModel

/*
 * WHAT THIS FILE DOES
 * The app's entry point. Android starts here when you tap the icon. It:
 *  1. keeps the screen on (handy in the garage)
 *  2. asks for Bluetooth permission if needed
 *  3. shows the app's screens (AppRoot)
 */
class MainActivity : ComponentActivity() {

    private val vm: ScannerViewModel by viewModels()
    private val hasPermission = mutableStateOf(false)

    private val perms: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasPermission.value = res.values.all { it }
        if (hasPermission.value) vm.refreshDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hasPermission.value = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (hasPermission.value) vm.refreshDevices() else launcher.launch(perms)
        setContent {
            CarScannerTheme {
                AppRoot(vm, hasPermission.value) { launcher.launch(perms) }
            }
        }
    }
}
