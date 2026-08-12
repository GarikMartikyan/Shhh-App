package com.shhh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class ShhhService : Service() {

    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private var accelerometer: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var detector: FlipDetector? = null

    private var eventCount = 0
    private var lastHeartbeat = 0L
    private var engagedAtElapsed = 0L
    private var engagedAtWall = 0L
    private var rateWindowStart = 0L

    /** Sequences the second heavy click; predefined effects cannot be composed. */
    private val haptics = Handler(Looper.getMainLooper())

    /**
     * Picking the phone up almost always turns the screen on, which wakes us even if the CPU had
     * suspended and we missed accelerometer samples. That makes SCREEN_ON the backstop that stops
     * the phone from getting stuck in Do Not Disturb.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            val d = detector ?: return
            if (d.engaged) {
                Log.i(TAG, "screen on while engaged - releasing")
                d.forceDisengage()
                if (engagedAtElapsed > 0L) {
                    History.add(
                        this@ShhhService,
                        engagedAtWall,
                        SystemClock.elapsedRealtime() - engagedAtElapsed,
                    )
                    engagedAtElapsed = 0L
                    engagedAtWall = 0L
                }
                vibrate()
                pushNotification()
                ShhhState.update { it.copy(lastChangeText = "released (screen on)") }
            }
            reconcile()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RELOAD) {
            val prefs = Prefs(this)
            detector?.sensitivity = prefs.sensitivity
            pushNotification()
            Log.i(TAG, "reloaded: sensitivity=${prefs.sensitivity.name} notif=${prefs.showNotification}")
            return START_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(engaged = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        if (detector == null) start()
        return START_STICKY
    }

    private fun start() {
        DndController.ensureRule(this)

        // A wake-up accelerometer keeps delivering while the CPU is suspended. Samsung flagships
        // usually expose one; if this device does not, the SCREEN_ON backstop carries the release.
        val wake = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        val normal = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer = wake ?: normal

        val sensor = accelerometer
        if (sensor == null) {
            Log.e(TAG, "no accelerometer")
            stopSelf()
            return
        }

        val d = FlipDetector(Prefs(this).sensitivity, ::onSample)
        detector = d
        sensorManager.registerListener(d, sensor, SAMPLE_PERIOD_US)

        // Observed only for now. If it turns out to report near against a desk it is a wake-up,
        // on-change sensor, which would be both a better pocket guard and a free screen-off path.
        val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximity != null) {
            d.proximityMaxRange = proximity.maximumRange
            sensorManager.registerListener(d, proximity, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "proximity: ${proximity.name} maxRange=${proximity.maximumRange} wakeUp=${proximity.isWakeUpSensor}")
        } else {
            Log.i(TAG, "proximity: none exposed")
        }

        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))

        // Start from a known-clean state: a fresh detector is never engaged, so neither is the rule.
        reconcile()

        rateWindowStart = SystemClock.elapsedRealtime()
        ShhhState.update {
            it.copy(
                running = true,
                wakeUpSensor = wake != null,
                sensorName = sensor.name,
                lastChangeText = "waiting",
            )
        }
        Log.i(TAG, "started on ${sensor.name} (wakeUp=${wake != null})")
    }

    private fun onSample(s: FlipDetector.Snapshot) {
        // Hold the CPU just long enough for a candidate to finish its debounce, so putting the
        // phone down right as the device idles still registers. Times out on its own.
        if (!s.engaged && s.heldMs > 0) acquireBriefWakeLock()

        if (s.changed) {
            vibrate()
            DndController.setEngaged(this, s.engaged)
            pushNotification()
            releaseWakeLock()
            Log.i(
                TAG,
                "${if (s.engaged) "ENGAGE" else "RELEASE"} gz=${s.gravityZ} motion=${s.motion} " +
                    "prox=${s.proximity} near=${s.proximityNear}",
            )
        }

        eventCount++
        val now = SystemClock.elapsedRealtime()

        // Heartbeat: the only way to know whether a non-wake-up sensor keeps delivering once the
        // screen is off and the device idles is to watch it do so.
        if (now - lastHeartbeat > HEARTBEAT_MS) {
            val since = if (lastHeartbeat == 0L) 0 else now - lastHeartbeat
            Log.i(TAG, "heartbeat gz=${s.gravityZ} prox=${s.proximity} engaged=${s.engaged} gapMs=$since")
            lastHeartbeat = now
        }

        val elapsed = now - rateWindowStart
        val rate = if (elapsed > 0) eventCount * 1000f / elapsed else 0f
        if (elapsed > 5_000) {
            eventCount = 0
            rateWindowStart = now
        }

        ShhhState.update {
            it.copy(
                sample = s,
                eventsPerSec = rate,
                lastChangeText = if (s.changed) {
                    if (s.engaged) "silenced" else "released"
                } else it.lastChangeText,
                lastSilencedAtMs = if (s.changed && s.engaged) {
                    System.currentTimeMillis()
                } else it.lastSilencedAtMs,
                lastHeldMs = if (s.changed && !s.engaged && engagedAtElapsed > 0L) {
                    now - engagedAtElapsed
                } else it.lastHeldMs,
            )
        }

        if (s.changed && !s.engaged && engagedAtElapsed > 0L) {
            History.add(this, engagedAtWall, now - engagedAtElapsed)
        }
        if (s.changed) {
            engagedAtElapsed = if (s.engaged) now else 0L
            engagedAtWall = if (s.engaged) System.currentTimeMillis() else 0L
        }
    }

    /**
     * Drives the zen rule back to whatever the detector currently believes.
     *
     * Without this, any drift between the two -- a process kill mid-engage, a crash, a stale rule
     * left over from a previous install -- strands the phone in Do Not Disturb with nothing to ever
     * clear it. Run on every service start and every screen-on, which bounds how long any such drift
     * can survive to "until you next look at your phone".
     */
    private fun reconcile() {
        val shouldBeEngaged = detector?.engaged == true
        DndController.setEngaged(this, shouldBeEngaged)
    }

    private fun acquireBriefWakeLock() {
        val existing = wakeLock
        if (existing != null && existing.isHeld) return
        val wl = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shhh:debounce")
        wl.setReferenceCounted(false)
        wl.acquire(WAKELOCK_TIMEOUT_MS)
        wakeLock = wl
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * The same two quick ticks for both transitions. Which one happened is unambiguous from what
     * you just did -- you either set the phone down or picked it up -- so the buzz only needs to
     * confirm that Shhh noticed, not encode which way it went.
     */
    private fun vibrate() {
        val vibrator =
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        if (!vibrator.hasVibrator()) return

        // Do Not Disturb is switching in the same breath, and a notification-usage vibration would
        // be swallowed by it. The zen policy allows alarms, so alarm usage is what reaches your hand.
        val attrs = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_ALARM)
            .build()

        val heavySupported = vibrator
            .areEffectsSupported(VibrationEffect.EFFECT_HEAVY_CLICK)
            .firstOrNull() == Vibrator.VIBRATION_EFFECT_SUPPORT_YES

        if (heavySupported) {
            // VibrationEffect.Composition only accepts primitives, never predefined effects, so the
            // second click has to be posted rather than sequenced. The gap is comfortably longer
            // than one click, so the two never collide.
            val heavy = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            vibrator.vibrate(heavy, attrs)
            haptics.postDelayed({ vibrator.vibrate(heavy, attrs) }, HEAVY_CLICK_GAP_MS)
        } else {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    CONFIRM_PATTERN_MS,
                    intArrayOf(0, MAX_AMPLITUDE, 0, MAX_AMPLITUDE),
                    -1,
                ),
                attrs,
            )
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Shhh is watching",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Required by Android while Shhh watches for the phone being placed face down."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Android requires a notification for every foreground service, so this can never be absent.
     * What it can be is unobtrusive: dismissible by swipe, deferred out of the way for the first
     * ten seconds, and stripped of text when the user has turned it off in the app.
     */
    private fun buildNotification(engaged: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val show = Prefs(this).showNotification
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shhh)
            .setContentTitle(if (show) (if (engaged) "Silenced" else "Shhh") else "Shhh")
            .setContentText(
                when {
                    !show -> null
                    engaged -> "Face down \u2014 Do Not Disturb is on"
                    else -> "Watching for the phone to go face down"
                }
            )
            .setContentIntent(open)
            // Not ongoing: on Android 13+ this is what lets you swipe it away.
            .setOngoing(false)
            .setShowWhen(false)
            .setForegroundServiceBehavior(
                if (show) Notification.FOREGROUND_SERVICE_DEFAULT
                else Notification.FOREGROUND_SERVICE_DEFERRED
            )
            .build()
    }

    private fun pushNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(detector?.engaged == true))
    }

    override fun onDestroy() {
        detector?.let {
            sensorManager.unregisterListener(it)
            if (it.engaged) DndController.setEngaged(this, false)
        }
        detector = null
        haptics.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        releaseWakeLock()
        ShhhState.update { ShhhState.Info() }
        Log.i(TAG, "stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Shhh"
        const val CHANNEL_ID = "shhh_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.shhh.STOP"
        const val ACTION_RELOAD = "com.shhh.RELOAD"

        /** ~10 Hz. Fast enough for a 1.5 s debounce, slow enough to be cheap. */
        private const val SAMPLE_PERIOD_US = 100_000

        private const val WAKELOCK_TIMEOUT_MS = 4_000L

        /** Full-scale amplitude; this is the only confirmation you get with the screen face down. */
        private const val MAX_AMPLITUDE = 255

        /** Two quick ticks: wait, buzz, gap, buzz. Used for both silencing and releasing. */
        private val CONFIRM_PATTERN_MS = longArrayOf(0, 55, 70, 55)

        /** Longer than one heavy click, so the pair reads as two distinct hits. */
        private const val HEAVY_CLICK_GAP_MS = 150L
        private const val HEARTBEAT_MS = 15_000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ShhhService::class.java))
        }

        /** Applies a settings change to an already-running service. */
        fun reload(ctx: Context) {
            if (!Prefs(ctx).enabled) return
            ctx.startForegroundService(
                Intent(ctx, ShhhService::class.java).setAction(ACTION_RELOAD)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ShhhService::class.java).setAction(ACTION_STOP))
        }
    }
}
