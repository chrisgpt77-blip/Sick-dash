package com.sickdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.sickdash.data.Channel
import com.sickdash.data.DashConfig
import com.sickdash.data.LinkState
import com.sickdash.data.VehicleData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders every page off-screen to PNGs in app/build/screenshots so the look can be reviewed without a car. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DashScreenshotTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun cfg(theme: String? = null, stock: Boolean = false): DashConfig {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit().putString("theme", theme)
            .putString("wheels", if (stock) "stock" else "5spoke")
            .putBoolean("body_kit", !stock)
            .commit()
        return DashConfig.load(prefs)
    }

    private var clock = System.currentTimeMillis()

    /** A short drive: revs climbing through [gear], a shift every ~2.5 s, values stamped on one clock. */
    private fun feed(rpm: Float, speedKph: Float, gear: Int, i: Int) {
        val now = clock
        fun set(c: Channel, v: Float) = VehicleData.set(c, v, now)
        val phase = (i % 50) / 50f
        val g = if (i < 150) 2 + (i / 50) % 3 else gear
        set(Channel.RPM, if (i < 150) 3200f + 3600f * phase else rpm + 40f * (i % 7))
        set(Channel.SPEED, speedKph)
        set(Channel.GEAR, (if (i < 150 && phase < 0.06f) 0 else g).toFloat())
        VehicleData.countFrame(now)
        set(Channel.THROTTLE, 64f)
        set(Channel.BRAKE, 0f)
        set(Channel.CLUTCH, 0f)
        set(Channel.STEERING, -14f)
        set(Channel.COOLANT, 91f)
        set(Channel.OIL_TEMP, 102f)
        set(Channel.INTAKE_TEMP, 26f)
        set(Channel.BATTERY, 14.2f)
        set(Channel.FUEL, 58f)
        set(Channel.LAT_G, 0.55f * kotlin.math.sin(i / 6f))
        set(Channel.LON_G, -0.3f)
        set(Channel.TIRE_FL, 36f * 6.8948f)
        set(Channel.TIRE_FR, 36f * 6.8948f)
        set(Channel.TIRE_RL, 34f * 6.8948f)
        set(Channel.TIRE_RR, 35f * 6.8948f)
        VehicleData.setStatus(LinkState.CONNECTED, "OBDLink MX+ · raw CAN · Bluetooth")
    }

    private fun shot(
        name: String, w: Int, h: Int, page: DashRenderer.Page, rpm: Float, speed: Float, gear: Int,
        theme: String? = null, cam: DashRenderer.Cam = DashRenderer.Cam.QUARTER,
        yaw: Float? = null, stock: Boolean = false,
    ) {
        val r = DashRenderer(ctx.assets).also { it.page = page; it.cam = cam }
        // ORBIT holds a hand-set angle: orbit() moves the target by -dx * 0.008 from 0.7 rad.
        if (yaw != null) { r.cam = DashRenderer.Cam.ORBIT; r.orbit((0.7f - yaw) / 0.008f) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cfg = cfg(theme, stock)
        repeat(220) { i ->
            clock += 50
            feed(rpm, speed, gear, i)
            r.draw(c, RectF(0f, 0f, w.toFloat(), h.toFloat()), VehicleData.snapshot(clock), cfg)
        }
        val dir = File("build/screenshots").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun renderScreens() {
        shot("drive_1280x720", 1280, 720, DashRenderer.Page.DRIVE, 4300f, 88f, 3)
        shot("drive_800x480", 800, 480, DashRenderer.Page.DRIVE, 7150f, 121f, 3)
        shot("drive_wide_1920x720", 1920, 720, DashRenderer.Page.DRIVE, 3100f, 64f, 4, "wrblue")
        shot("drive_side_1280x720", 1280, 720, DashRenderer.Page.DRIVE, 3100f, 64f, 4, cam = DashRenderer.Cam.SIDE)
        shot("drive_rear34_1280x720", 1280, 720, DashRenderer.Page.DRIVE, 3100f, 64f, 4, yaw = (Math.PI - 0.7).toFloat())
        shot("drive_front_1280x720", 1280, 720, DashRenderer.Page.DRIVE, 3100f, 64f, 4, yaw = 0.25f)
        shot("drive_stock_1280x720", 1280, 720, DashRenderer.Page.DRIVE, 3100f, 64f, 4, stock = true)
        shot("shift_1280x720", 1280, 720, DashRenderer.Page.SHIFT, 5200f, 96f, 3)
        shot("engine_1280x720", 1280, 720, DashRenderer.Page.ENGINE, 5200f, 96f, 3, "cyberpunk")
        shot("handling_1280x720", 1280, 720, DashRenderer.Page.HANDLING, 5200f, 96f, 3)
        shot("style_1280x720", 1280, 720, DashRenderer.Page.STYLE, 2500f, 40f, 2, "matrix")
        shot("phone_portrait_1080x2340", 1080, 2340, DashRenderer.Page.DRIVE, 3800f, 72f, 3)
    }
}
