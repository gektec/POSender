package com.example.posender.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class Joystick1DView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Orientation { HORIZONTAL, VERTICAL }
    var orientation: Orientation = Orientation.VERTICAL

    interface Listener {
        fun onChanged(v: Float, touching: Boolean)
    }
    var listener: Listener? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33445566")
        style = Paint.Style.FILL
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3F51B5")
        style = Paint.Style.FILL
    }

    private var norm = 0f // [-1, 1]; 对于竖直：上推为正；水平：右推为正
    private var touching = false

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // 轨道
        if (orientation == Orientation.VERTICAL) {
            val cx = w / 2f
            canvas.drawRoundRect(cx - w * 0.15f, h * 0.05f, cx + w * 0.15f, h * 0.95f, 12f, 12f, trackPaint)
            // knob
            val y = h / 2f - norm * (h * 0.45f)
            canvas.drawCircle(cx, y, min(w, h) * 0.18f, knobPaint)
        } else {
            val cy = h / 2f
            canvas.drawRoundRect(w * 0.05f, cy - h * 0.15f, w * 0.95f, cy + h * 0.15f, 12f, 12f, trackPaint)
            val x = w / 2f + norm * (w * 0.45f)
            canvas.drawCircle(x, cy, min(w, h) * 0.18f, knobPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                norm = if (orientation == Orientation.VERTICAL) {
                    // y 向下为正，反向得到上推为正
                    val t = ((h / 2f) - event.y) / (h * 0.45f)
                    t.coerceIn(-1f, 1f)
                } else {
                    val t = (event.x - (w / 2f)) / (w * 0.45f)
                    t.coerceIn(-1f, 1f)
                }
                listener?.onChanged(norm, true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                norm = 0f
                listener?.onChanged(0f, false)
                invalidate()
            }
        }
        return true
    }

    fun getNorm(): Float = norm
}