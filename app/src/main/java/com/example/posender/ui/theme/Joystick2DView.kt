package com.example.posender.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class Joystick2DView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onChanged(x: Float, y: Float, touching: Boolean)
    }

    var listener: Listener? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33445566")
        style = Paint.Style.FILL
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.FILL
    }
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var normX = 0f
    private var normY = 0f
    private var touching = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) * 0.45f
        resetKnob()
    }

    override fun onDraw(canvas: Canvas) {
        // base
        canvas.drawCircle(cx, cy, radius, basePaint)
        // knob
        canvas.drawCircle(cx + knobX, cy + knobY, radius * 0.28f, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                val dx = event.x - cx
                val dy = event.y - cy
                // clamp to circle
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val clamped = min(dist, radius)
                val angle = atan2(dy, dx)
                knobX = clamped * kotlin.math.cos(angle)
                knobY = clamped * kotlin.math.sin(angle)
                // normalize to [-1, 1]
                normX = (knobX / radius).coerceIn(-1f, 1f)
                // 屏幕向下为正，按惯例反向为上推正
                normY = (-knobY / radius).coerceIn(-1f, 1f)
                listener?.onChanged(normX, normY, true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                resetKnob()
                listener?.onChanged(0f, 0f, false)
                invalidate()
            }
        }
        return true
    }

    private fun resetKnob() {
        knobX = 0f
        knobY = 0f
        normX = 0f
        normY = 0f
    }

    fun getNorm(): Pair<Float, Float> = normX to normY
}