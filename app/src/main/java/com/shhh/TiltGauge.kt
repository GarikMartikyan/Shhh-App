package com.shhh

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The one thing this screen is remembered by.
 *
 * You never actually see this app do its job -- the screen is face down when it fires. So the gauge
 * exists to answer "will it work when I am not looking": it is driven by the real accelerometer, so
 * tilting the phone moves it, and the notch marks the exact angle at which it will engage.
 *
 * Arc sweeps 240 degrees with the gap at the bottom, where a thumb would cover it anyway.
 */
class TiltGauge @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private companion object {
        const val START_ANGLE = 150f
        const val SWEEP = 240f

        /** Past 90 degrees from face down the phone is face up; nothing there is worth showing. */
        const val VISIBLE_RANGE_DEG = 90f
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val heldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.14f
    }

    private val arcBounds = RectF()

    private var colorTrack = 0
    private var colorAwake = 0
    private var colorQuiet = 0
    private var colorInk = 0
    private var colorDim = 0

    /** Degrees away from perfectly face down. 0 = face down, 180 = face up. */
    private var targetTilt = 180f
    private var shownTilt = 180f
    private var heldFraction = 0f
    private var engaged = false
    private var thresholdTilt = 23.4f

    private val density = resources.displayMetrics.density

    init {
        colorTrack = context.getColor(R.color.track)
        colorAwake = context.getColor(R.color.awake)
        colorQuiet = context.getColor(R.color.quiet)
        colorInk = context.getColor(R.color.ink)
        colorDim = context.getColor(R.color.ink_dim)

        trackPaint.color = colorTrack
        notchPaint.color = colorDim
        labelPaint.color = colorDim

        trackPaint.strokeWidth = 14f * density
        fillPaint.strokeWidth = 14f * density
        heldPaint.strokeWidth = 4f * density
        notchPaint.strokeWidth = 2f * density
        valuePaint.textSize = 58f * density
        labelPaint.textSize = 11f * density
    }

    /**
     * @param tiltDeg degrees away from face down
     * @param heldFraction how far through the hold-still debounce, 0..1
     * @param thresholdDeg the angle at which engagement happens, drawn as the notch
     */
    fun setState(tiltDeg: Float, heldFraction: Float, engaged: Boolean, thresholdDeg: Float) {
        targetTilt = tiltDeg
        this.heldFraction = heldFraction
        this.engaged = engaged
        this.thresholdTilt = thresholdDeg
        postInvalidateOnAnimation()
    }

    private fun fractionFor(tilt: Float): Float =
        (1f - (tilt / VISIBLE_RANGE_DEG)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        // Ease toward the reading so a 10 Hz sensor does not look like a 10 Hz gauge.
        val delta = targetTilt - shownTilt
        if (kotlin.math.abs(delta) > 0.05f) {
            shownTilt += delta * 0.25f
            postInvalidateOnAnimation()
        } else {
            shownTilt = targetTilt
        }

        val inset = 16f * density + trackPaint.strokeWidth / 2f
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f - inset
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawArc(arcBounds, START_ANGLE, SWEEP, false, trackPaint)

        val fraction = if (engaged) 1f else fractionFor(shownTilt)
        fillPaint.color = if (engaged) colorQuiet else colorAwake
        if (fraction > 0.001f) {
            canvas.drawArc(arcBounds, START_ANGLE, SWEEP * fraction, false, fillPaint)
        }

        // Notch: the exact angle at which it will engage.
        val notchAt = START_ANGLE + SWEEP * fractionFor(thresholdTilt)
        val rad = Math.toRadians(notchAt.toDouble())
        val inner = radius - trackPaint.strokeWidth / 2f - 3f * density
        val outer = radius + trackPaint.strokeWidth / 2f + 3f * density
        canvas.drawLine(
            cx + (cos(rad) * inner).toFloat(), cy + (sin(rad) * inner).toFloat(),
            cx + (cos(rad) * outer).toFloat(), cy + (sin(rad) * outer).toFloat(),
            notchPaint,
        )

        // Inner ring counts out the hold-still debounce -- the moment of tension before it commits.
        if (heldFraction > 0f && !engaged) {
            heldPaint.color = colorQuiet
            val r2 = radius - trackPaint.strokeWidth - 8f * density
            arcBounds.set(cx - r2, cy - r2, cx + r2, cy + r2)
            canvas.drawArc(arcBounds, START_ANGLE, SWEEP * heldFraction.coerceIn(0f, 1f), false, heldPaint)
        }

        if (engaged) {
            valuePaint.color = colorQuiet
            valuePaint.textSize = 34f * density
            canvas.drawText("Silenced", cx, cy + 10f * density, valuePaint)
            valuePaint.textSize = 58f * density
        } else {
            valuePaint.color = colorInk
            val shown = max(0f, min(180f, shownTilt))
            canvas.drawText("${shown.toInt()}°", cx, cy + 12f * density, valuePaint)
            canvas.drawText("FROM FACE DOWN", cx, cy + 40f * density, labelPaint)
        }
    }
}
