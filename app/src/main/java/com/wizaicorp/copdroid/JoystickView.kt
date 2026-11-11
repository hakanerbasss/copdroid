package com.wizaicorp.copdroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Dokunmatik joystick. Çağırana x,y ∈ [-1,1] verir (merkez 0,0). Bırakınca merkeze döner. */
class JoystickView(context: Context) : View(context) {
    var listener: ((x: Float, y: Float) -> Unit)? = null

    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D0D0D0") }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3F51B5") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9FA8DA"); style = Paint.Style.STROKE; strokeWidth = 6f
    }

    private var cx = 0f; private var cy = 0f
    private var kx = 0f; private var ky = 0f
    private var radius = 0f; private var knobR = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f
        radius = min(w, h) / 2f - 12f
        knobR = radius * 0.35f
        kx = cx; ky = cy
    }

    override fun onDraw(c: Canvas) {
        c.drawCircle(cx, cy, radius, base)
        c.drawCircle(cx, cy, radius, ring)
        c.drawCircle(kx, ky, knobR, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = e.x - cx; var dy = e.y - cy
                val d = hypot(dx, dy)
                if (d > radius) { dx = dx / d * radius; dy = dy / d * radius }
                kx = cx + dx; ky = cy + dy
                listener?.invoke(dx / radius, -dy / radius) // yukarı = +ileri
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                kx = cx; ky = cy
                listener?.invoke(0f, 0f)
                invalidate()
            }
        }
        return true
    }
}
