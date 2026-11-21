package com.daniel.carscanner.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/*
 * WHAT THIS FILE DOES
 * Controls the phone's flashlight, for looking around the engine bay or
 * under the dash. Android lets apps switch the torch without camera
 * permission. The app turns it off automatically when you leave the app.
 */
class Torch(context: Context) {
    private val cameras = context.getSystemService(CameraManager::class.java)

    // The first camera that has a flash (usually the back camera).
    private val cameraId: String? = try {
        val cm = cameras
        if (cm == null) null
        else cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (_: Exception) { null }

    /** False on phones with no flash. The button hides itself then. */
    val available: Boolean get() = cameraId != null

    /** Whether the light is on right now. The button's icon follows this. */
    var isOn by mutableStateOf(false)
        private set

    // Keeps isOn correct even if you switch the torch from quick settings.
    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == cameraId) isOn = enabled
        }
    }

    init {
        if (cameraId != null) {
            try { cameras?.registerTorchCallback(callback, Handler(Looper.getMainLooper())) } catch (_: Exception) { }
        }
    }

    fun toggle() = set(!isOn)

    fun set(on: Boolean) {
        val id = cameraId ?: return
        try { cameras?.setTorchMode(id, on); isOn = on } catch (_: Exception) { }
    }

    /** Stop listening for torch changes (when the app closes). */
    fun release() {
        try { cameras?.unregisterTorchCallback(callback) } catch (_: Exception) { }
    }
}

/** Lets any screen reach the flashlight without passing it around. */
val LocalTorch = staticCompositionLocalOf<Torch?> { null }
