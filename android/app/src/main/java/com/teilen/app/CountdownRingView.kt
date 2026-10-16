package com.teilen.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Circular TTL countdown ring representing remaining item lifespan.
 * Progress is normalized between 1.0f (full) and 0.0f (expired).
 * Automatically shifts color from Electric Sky Blue / Emerald Green to Amber (<20%) to Crimson (<5%).
 */
class CountdownRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val oval = RectF()

    var progress: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                updateColors()
                invalidate()
            }
        }

    init {
        updateStroke()
        updateColors()
    }

    private fun updateStroke() {
        val density = resources.displayMetrics.density
        val stroke = 2.5f * density
        trackPaint.strokeWidth = stroke
        fillPaint.strokeWidth = stroke
    }

    private fun updateColors() {
        trackPaint.color = context.getColor(R.color.surface_border)
        fillPaint.color = when {
            progress <= 0.05f -> context.getColor(R.color.danger)
            progress <= 0.20f -> context.getColor(R.color.warning)
            else -> context.getColor(R.color.accent)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val stroke = fillPaint.strokeWidth
        val padding = stroke / 2f + 1f
        oval.set(padding, padding, w.toFloat() - padding, h.toFloat() - padding)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (oval.width() <= 0f || oval.height() <= 0f) return

        // Background track circle
        canvas.drawOval(oval, trackPaint)

        // Sweeping progress arc starting from top (-90 degrees)
        if (progress > 0f) {
            val sweep = 360f * progress
            canvas.drawArc(oval, -90f, sweep, false, fillPaint)
        }
    }
}
