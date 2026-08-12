package com.shhh

import kotlin.math.acos

/**
 * How fussy the detector is about calling something "face down on a flat surface".
 *
 * The numbers are not invented. Real placements measured on this phone landed at gravity Z between
 * -9.53 and -9.73 with motion between 0.002 and 0.072, so Balanced keeps roughly 3x headroom over
 * the worst of those while still leaving room for an imperfect table. Strict rejects the shallow
 * -9.08 kind of placement seen on a surface that was not properly flat; Relaxed accepts it.
 */
enum class Sensitivity(
    val label: String,
    /** Gravity Z at or below which the phone counts as face down. */
    val engageGz: Float,
    /** Hysteresis: only past this does it count as picked up. */
    val releaseGz: Float,
    /** Non-gravity acceleration, m/s^2, below which it counts as settled. */
    val stillMax: Float,
    /** How long both conditions must hold before it silences. */
    val holdMs: Long,
    val blurb: String,
) {
    STRICT(
        label = "Strict",
        engageGz = -9.5f,
        releaseGz = -7.5f,
        stillMax = 0.12f,
        holdMs = 2_000L,
        blurb = "Wants a properly flat, properly still surface. Fewest pocket false alarms, and the most likely to ignore a soft or sloped one.",
    ),
    BALANCED(
        label = "Balanced",
        engageGz = -9.0f,
        releaseGz = -7.0f,
        stillMax = 0.25f,
        holdMs = 1_500L,
        blurb = "Tuned against the placements actually measured on this phone. Start here.",
    ),
    RELAXED(
        label = "Relaxed",
        engageGz = -8.3f,
        releaseGz = -6.3f,
        stillMax = 0.45f,
        holdMs = 1_000L,
        blurb = "Triggers on a slope, a cushion, or a quick set-down. Silences more readily, and is the most likely to fire in a pocket.",
    );

    /** The engage angle expressed as degrees away from perfectly face down, for the gauge notch. */
    val thresholdDeg: Float
        get() = Math.toDegrees(acos((-engageGz / 9.81f).coerceIn(-1f, 1f).toDouble())).toFloat()

    companion object {
        val DEFAULT = BALANCED

        fun fromName(name: String?): Sensitivity =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
