package com.shhh.torch

import android.app.Activity
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Invisible activity that flips the flashlight and exits.
 *
 * RegiStar's back-tap action list is fixed and has no flashlight entry, but it can open any app, so
 * this exists as a launcher entry solely to be that app.
 *
 * The torch state is read from [CameraManager.TorchCallback] rather than remembered locally:
 * registering the callback reports the current state immediately, and it is the only way to notice
 * a torch that something else — the quick panel, the camera app — turned on behind our back.
 * Toggling off a stale local flag would otherwise leave the light stuck on.
 */
class TorchActivity : Activity() {

    companion object {
        /** Give up if the framework never reports a torch state. Never observed; avoids a hang. */
        private const val RESPONSE_TIMEOUT_MS = 1500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cameraManager: CameraManager
    private var flashCameraId: String? = null
    private var done = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (done || cameraId != flashCameraId) return
            toggle(to = !enabled)
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (done || cameraId != flashCameraId) return
            finishWith("Flashlight busy — close the camera")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraManager = getSystemService(CameraManager::class.java)
        flashCameraId = findFlashCamera()

        if (flashCameraId == null) {
            finishWith("No flashlight found")
            return
        }

        cameraManager.registerTorchCallback(torchCallback, handler)
        handler.postDelayed({ finishWith("Flashlight did not respond") }, RESPONSE_TIMEOUT_MS)
    }

    /** Prefers the rear flash; falls back to any camera that has one. */
    private fun findFlashCamera(): String? = try {
        val withFlash = cameraManager.cameraIdList.filter { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        withFlash.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: withFlash.firstOrNull()
    } catch (e: CameraAccessException) {
        null
    }

    private fun toggle(to: Boolean) {
        // Flip the light here rather than from the service so the beam answers the tap immediately
        // instead of trailing service startup by a few hundred milliseconds. Off is done here for a
        // second reason: the torch may have been lit by something else, leaving no service to stop.
        try {
            cameraManager.setTorchMode(flashCameraId!!, to)
        } catch (e: CameraAccessException) {
            finishWith("Flashlight busy — close the camera")
            return
        } catch (e: IllegalArgumentException) {
            finishWith("Flashlight unavailable")
            return
        }

        // The service exists only to keep this process alive; the light dies with it. See TorchService.
        val service = Intent(this, TorchService::class.java)
        if (to) {
            startForegroundService(
                service.setAction(TorchService.ACTION_ON).putExtra(EXTRA_CAMERA_ID, flashCameraId)
            )
        } else {
            stopService(service)
        }
        finishWith(null)
    }

    private fun finishWith(message: String?) {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        runCatching { cameraManager.unregisterTorchCallback(torchCallback) }
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
