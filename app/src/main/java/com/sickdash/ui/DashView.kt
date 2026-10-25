package com.sickdash.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.preference.PreferenceManager
import com.sickdash.data.DashConfig
import com.sickdash.data.Recorder
import com.sickdash.data.VehicleData

/** Phone-screen host for [DashRenderer]; redraws every vsync, taps hit the HUD's tabs, drags orbit the car. */
class DashView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    val renderer = DashRenderer(context.assets).apply {
        onSetting = { key, value ->
            PreferenceManager.getDefaultSharedPreferences(context).edit().apply {
                if (value == null) remove(key) else putString(key, value)
            }.apply()
        }
    }

    init {
        Recorder.init(context)
    }
    private val area = RectF()

    /** Called for taps that don't land on anything in the HUD. */
    var onEmptyTap: (() -> Unit)? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!renderer.handleTap(e.x, e.y)) onEmptyTap?.invoke()
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            renderer.orbit(dx)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        renderer.draw(canvas, area, VehicleData.snapshot(), DashConfig.get(context))
        postInvalidateOnAnimation()
    }
}
