package com.sickdash.auto

import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.sickdash.data.DashConfig
import com.sickdash.data.VehicleData
import com.sickdash.ui.DashRenderer

/** Draws the dashboard onto the Android Auto "map" surface at ~30 fps. */
class SurfaceRenderer(private val carContext: CarContext, lifecycle: Lifecycle) : DefaultLifecycleObserver {
    val dash = DashRenderer()

    private val lock = Any()
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var visible: Rect? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val frame = object : Runnable {
        override fun run() {
            renderFrame()
            handler?.postDelayed(this, 33)
        }
    }

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            synchronized(lock) {
                surface = container.surface
                width = container.width
                height = container.height
            }
            startLoop()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            synchronized(lock) { visible = Rect(visibleArea) }
        }

        override fun onStableAreaChanged(stableArea: Rect) = Unit

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            stopLoop()
            synchronized(lock) { surface = null }
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopLoop()
    }

    private fun startLoop() {
        if (thread != null) return
        val t = HandlerThread("dash-render").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { it.post(frame) }
    }

    private fun stopLoop() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun renderFrame() {
        synchronized(lock) {
            val s = surface ?: return
            if (!s.isValid || width == 0) return
            val canvas = try { s.lockHardwareCanvas() } catch (e: Exception) { return }
            try {
                canvas.drawColor(android.graphics.Color.rgb(6, 7, 10))
                val v = visible
                val area = if (v != null && v.width() > width / 2 && v.height() > height / 2) RectF(v)
                else RectF(0f, 0f, width.toFloat(), height.toFloat())
                dash.draw(canvas, area, VehicleData.snapshot(), DashConfig.get(carContext))
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
        }
    }
}
