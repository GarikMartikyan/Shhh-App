package com.shhh.torch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the flashlight lit for as long as it is on.
 *
 * The torch is scoped to the process that asked for it: when that process dies the camera service
 * drops the light. [TorchActivity] finishes in milliseconds, which would leave an empty process —
 * the first thing a low-memory kill reclaims — as the only thing holding the beam. Verified on
 * device: killing the process turns the flashlight off on its own. Running as a foreground service
 * keeps the process at a priority that is not casually reclaimed, and buys a shade entry to switch
 * the light off without picking the phone up.
 */
class TorchService : Service() {

    companion object {
        const val ACTION_ON = "com.shhh.torch.ON"

        private const val CHANNEL_ID = "torch"
        private const val NOTIFICATION_ID = 1

        /** How long to wait for confirmation that the light is actually on before giving up. */
        private const val CONFIRM_TIMEOUT_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var watching = false

    /** Stopping by start id means a fresh tap that re-lit the torch cancels a stale stop. */
    private var lastStartId = 0

    /** Set once we have seen the light actually come on, so an early "off" is not misread. */
    private var lit = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id != cameraId) return
            if (enabled) {
                lit = true
            } else if (lit) {
                // Someone else — quick panel, camera app — put it out. Stop rather than fight.
                stopSelf(lastStartId)
            }
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id == cameraId) stopSelf(lastStartId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(CameraManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen within 5s of startForegroundService, so it comes before anything fallible.
        startForeground(NOTIFICATION_ID, buildNotification())
        lastStartId = startId

        cameraId = intent?.getStringExtra(EXTRA_CAMERA_ID)
        if (cameraId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // The activity has already lit the torch; this only watches it. A repeat start (two taps
        // racing) must not register twice, or the second unregister would leave a live callback.
        if (!watching) {
            watching = true
            cameraManager.registerTorchCallback(torchCallback, handler)
            // If the light never reports on, there is nothing to hold: do not sit in the shade
            // showing "Flashlight on" over a dark flash.
            handler.postDelayed({ if (!lit) stopSelf(lastStartId) }, CONFIRM_TIMEOUT_MS)
        }

        return START_NOT_STICKY
    }

    /**
     * Deliberately does not put the light out.
     *
     * Teardown is asynchronous, so a tap that turns the torch off and a tap that turns it straight
     * back on can overlap: this instance's onDestroy would land after the second tap and snuff out
     * the beam it had just lit. Every path that ends this service has already handled the light —
     * the activity turns it off itself, an external switch-off is what triggered the stop, and if
     * the process is killed the camera service drops the torch on its own. Writing torch state from
     * exactly one place, [TorchActivity], is what keeps the two in step.
     */
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { cameraManager.unregisterTorchCallback(torchCallback) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Flashlight", NotificationManager.IMPORTANCE_LOW)
            )
        }

        // Re-entering the activity toggles, which from the lit state means off.
        val off = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TorchActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_torch_status)
            .setContentTitle("Flashlight on")
            .setContentText("Tap to turn off")
            .setContentIntent(off)
            .addAction(Notification.Action.Builder(null, "Turn off", off).build())
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}

/** Which camera's flash to drive, resolved by the activity so the service does not re-scan. */
const val EXTRA_CAMERA_ID = "camera_id"
