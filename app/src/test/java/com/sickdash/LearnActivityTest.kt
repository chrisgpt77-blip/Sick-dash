package com.sickdash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.sickdash.data.VehicleData
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LearnActivityTest {
    @Test
    fun walksIntoTheFirstStepAndOpensTheFilters() {
        val controller = Robolectric.buildActivity(LearnActivity::class.java).setup()
        val a = controller.get()
        assertTrue("filters stay open while learning", VehicleData.listenAll)
        shot(a, "learn_intro")
        a.findViewById<Button>(R.id.btn_main).performClick()
        assertEquals("Close all the doors", a.findViewById<TextView>(R.id.prompt).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.step).text.startsWith("STEP 1 OF 12 · DRIVER'S DOOR · 1 / 6"))
        assertEquals(android.view.View.VISIBLE, a.findViewById<Button>(R.id.btn_skip).visibility)
        shot(a, "learn_step")
        controller.pause().stop()
        assertEquals(false, VehicleData.listenAll)
        assertEquals(null, VehicleData.frameTap)
        controller.destroy()
    }

    private fun shot(a: LearnActivity, name: String) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val v = a.window.decorView
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(v.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(v.height, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, v.width, v.height)
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
