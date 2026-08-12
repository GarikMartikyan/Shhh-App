package com.shhh

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Decides when the phone is lying face down on a flat surface.
 *
 * This deliberately watches for a sustained *state* rather than a flip *event*. Samsung does not
 * expose the real proximity sensor to third-party apps -- and the one it does expose, the palm
 * sensor, was measured reading both near and far in the same face-down position -- so "flat and
 * still" is the only available way to tell a desk from a pocket: a worn pocket is essentially never
 * both within a few degrees of horizontal and motionless for over a second.
 *
 * How fussy "flat and still" is comes from [sensitivity], which the user chooses.
 */
class FlipDetector(
    @Volatile var sensitivity: Sensitivity,
    private val onSample: (Snapshot) -> Unit,
) : SensorEventListener {

    data class Snapshot(
        val gravityZ: Float,
        val motion: Float,
        val faceDown: Boolean,
        val still: Boolean,
        /** How long the engage condition has held, in ms. 0 when not a candidate. */
        val heldMs: Long,
        /** How long the hold must last under the current sensitivity. */
        val holdTargetMs: Long,
        val engaged: Boolean,
        /** True on the sample where [engaged] flipped. */
        val changed: Boolean,
        /** Raw android.sensor.proximity reading, or null if the device exposes none. */
        val proximity: Float? = null,
        val proximityNear: Boolean? = null,
    )

    companion object {
        /** Low-pass coefficient for gravity extraction at ~10 Hz. */
        private const val ALPHA = 0.7f

        const val RELEASE_HOLD_MS = 300L
    }

    private val gravity = FloatArray(3)
    private var primed = false
    private var candidateSince = 0L
    private var releaseSince = 0L

    @Volatile
    private var proximity: Float? = null

    @Volatile
    private var proximityNear: Boolean? = null

    var proximityMaxRange = 0f

    @Volatile
    var engaged = false
        private set

    fun reset() {
        primed = false
        candidateSince = 0L
        releaseSince = 0L
    }

    /** Clears engagement without emitting, e.g. when the screen-on backstop takes over. */
    fun forceDisengage() {
        engaged = false
        reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val v = event.values[0]
            val threshold = if (proximityMaxRange > 0f) proximityMaxRange else 5f
            proximity = v
            proximityNear = v < threshold
            return
        }
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val s = sensitivity
        val now = SystemClock.elapsedRealtime()
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!primed) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            primed = true
        } else {
            gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * x
            gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * y
            gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * z
        }

        val dx = x - gravity[0]
        val dy = y - gravity[1]
        val dz = z - gravity[2]
        val motion = sqrt(dx * dx + dy * dy + dz * dz)

        val faceDown = gravity[2] <= s.engageGz
        val pickedUp = gravity[2] > s.releaseGz
        val still = motion < s.stillMax

        var changed = false

        if (!engaged) {
            if (faceDown && still) {
                if (candidateSince == 0L) candidateSince = now
                if (now - candidateSince >= s.holdMs) {
                    engaged = true
                    changed = true
                    candidateSince = 0L
                    releaseSince = 0L
                }
            } else {
                candidateSince = 0L
            }
        } else {
            if (pickedUp) {
                if (releaseSince == 0L) releaseSince = now
                if (now - releaseSince >= RELEASE_HOLD_MS) {
                    engaged = false
                    changed = true
                    releaseSince = 0L
                }
            } else {
                releaseSince = 0L
            }
        }

        onSample(
            Snapshot(
                gravityZ = gravity[2],
                motion = motion,
                faceDown = faceDown,
                still = still,
                heldMs = if (candidateSince == 0L) 0L else now - candidateSince,
                holdTargetMs = s.holdMs,
                engaged = engaged,
                changed = changed,
                proximity = proximity,
                proximityNear = proximityNear,
            )
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
