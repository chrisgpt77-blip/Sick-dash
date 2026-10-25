package com.sickdash.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.sickdash.data.DashConfig
import com.sickdash.data.VehicleData

/** Phone-screen host for [DashRenderer]; redraws every vsync while attached. */
class DashView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    val renderer = DashRenderer()
    private val area = RectF()

    override fun onDraw(canvas: Canvas) {
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        renderer.draw(canvas, area, VehicleData.snapshot(), DashConfig.get(context))
        postInvalidateOnAnimation()
    }
}
