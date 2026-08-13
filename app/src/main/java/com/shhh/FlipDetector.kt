package com.shhh

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.SystemClock
import kotlin.math.atan2
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
 * Flat and still are not enough on their own, because a hand holding the phone face down satisfies
 * both: gripping something steadily produces very little linear acceleration. What a hand cannot do
 * is hold an *angle*. So a third condition asks that the phone lean no more than
 * [Sensitivity.maxDriftDeg] away from where it was when the countdown began; any more and the
 * countdown restarts from the new angle. A table holds to a fraction of a degree, a wrist does not.
 *
 * How fussy "flat, still and steady" is comes from [sensitivity], which the user chooses.
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
        /** Degrees between the current gravity direction and where the countdown started. */
        val driftDeg: Float,
        /** How much drift the current sensitivity tolerates. */
        val driftMaxDeg: Float,
        /** False on the sample where drift blew past the limit and restarted the countdown. */
        val steady: Boolean,
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

    /** Gravity direction at the moment the countdown started; drift is measured against this. */
    private val anchor = FloatArray(3)
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
        var drift = 0f
        var steady = true

        if (!engaged) {
            if (faceDown && still) {
                if (candidateSince == 0L) {
                    candidateSince = now
                    anchorHere()
                } else {
                    drift = driftFromAnchor()
                    if (drift > s.maxDriftDeg) {
                        // Something is still moving the phone, so nothing has been put down yet.
                        // Start the countdown again from wherever it has ended up: a hand that
                        // genuinely settles can still pass, it just has to settle first.
                        steady = false
                        candidateSince = now
                        anchorHere()
                    }
                }
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
                driftDeg = drift,
                driftMaxDeg = s.maxDriftDeg,
                steady = steady,
                heldMs = if (candidateSince == 0L) 0L else now - candidateSince,
                holdTargetMs = s.holdMs,
                engaged = engaged,
                changed = changed,
                proximity = proximity,
                proximityNear = proximityNear,
            )
        )
    }

    private fun anchorHere() {
        anchor[0] = gravity[0]; anchor[1] = gravity[1]; anchor[2] = gravity[2]
    }

    /**
     * Angle between the anchored gravity direction and the current one, in degrees.
     *
     * atan2(|a x b|, a.b) rather than acos of the normalised dot product: the dot product alone
     * loses all its precision exactly where this is used, on angles of a degree or two, and it
     * would also have to divide by a magnitude that a face-down phone makes tiny. This form needs
     * no normalisation -- the magnitudes cancel -- and stays accurate down to a fraction of a
     * degree. It also catches a lean in any direction, not just a change in how flat the phone is,
     * so a wrist rocking north then south does not average itself out into looking motionless.
     */
    private fun driftFromAnchor(): Float {
        val a = anchor
        val b = gravity
        val cx = a[1] * b[2] - a[2] * b[1]
        val cy = a[2] * b[0] - a[0] * b[2]
        val cz = a[0] * b[1] - a[1] * b[0]
        val cross = sqrt(cx * cx + cy * cy + cz * cz)
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        return Math.toDegrees(atan2(cross, dot).toDouble()).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
