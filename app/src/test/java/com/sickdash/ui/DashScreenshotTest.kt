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

/** Renders the dashboard off-screen to PNGs in app/build/screenshots so the look can be reviewed without a car. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DashScreenshotTest {
    private fun cfg(): DashConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return DashConfig.load(PreferenceManager.getDefaultSharedPreferences(ctx))
    }

    private fun feed(rpm: Float, speedKph: Float) {
        VehicleData.set(Channel.RPM, rpm)
        VehicleData.set(Channel.SPEED, speedKph)
        VehicleData.set(Channel.THROTTLE, 87f)
        VehicleData.set(Channel.COOLANT, 92f)
        VehicleData.set(Channel.OIL_TEMP, 118f)
        VehicleData.set(Channel.INTAKE_TEMP, 34f)
        VehicleData.set(Channel.BATTERY, 14.2f)
        VehicleData.set(Channel.LAT_G, -0.62f)
        VehicleData.set(Channel.LON_G, -0.35f)
        VehicleData.setStatus(LinkState.CONNECTED, "OBDLink MX+ · OBD-II · Bluetooth")
    }

    private fun shot(name: String, w: Int, h: Int, page: DashRenderer.Page, rpm: Float, speed: Float) {
        feed(rpm, speed)
        val r = DashRenderer().also { it.page = page }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cfg = cfg()
        repeat(40) { r.draw(c, RectF(0f, 0f, w.toFloat(), h.toFloat()), VehicleData.snapshot(), cfg) }
        val dir = File("build/screenshots").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun renderScreens() {
        shot("car_dash_800x480", 800, 480, DashRenderer.Page.DASH, 5450f, 97f)
        shot("car_dash_wide_1920x720", 1920, 720, DashRenderer.Page.DASH, 6650f, 142f)
        shot("car_race_1280x720", 1280, 720, DashRenderer.Page.RACE, 7120f, 158f)
        shot("phone_portrait_1080x2340", 1080, 2340, DashRenderer.Page.DASH, 3200f, 64f)
    }
}
