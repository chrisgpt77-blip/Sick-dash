package com.sickdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.sickdash.can.CanFrame
import com.sickdash.can.CanProfile
import com.sickdash.data.Channel
import com.sickdash.data.DashConfig
import com.sickdash.data.LinkState
import com.sickdash.data.Simulator
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

    private fun cfg(theme: String? = null, stock: Boolean = false, demo: Boolean = false): DashConfig {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit().putString("theme", theme)
            .putString("source", if (demo) "demo" else "ble_elm")
            .putString("wheels", if (stock) "stock" else "5spoke")
            .putBoolean("body_kit", !stock)
            .commit()
        return DashConfig.load(prefs)
    }

    private var clock = System.currentTimeMillis()
    private lateinit var feedCfg: DashConfig
    private val drive = Simulator.Drive()

    /** A short drive: revs climbing through [gear], a shift every ~2.5 s, stamped as the GR86's CAN ids would be. */
    private fun feed(rpm: Float, speedKph: Float, gear: Int, i: Int) {
        val now = clock
        fun set(c: Channel, v: Float, id: Int) = VehicleData.set(c, v, now, VehicleData.SRC_CAN, id)
        val phase = (i % 50) / 50f
        val g = if (i < 150) 2 + (i / 50) % 3 else gear
        val lat = 0.55f * kotlin.math.sin(i / 6f)
        val revs = if (i < 150) 3200f + 3600f * phase else rpm + 40f * (i % 7)
        set(Channel.RPM, revs, 0x40)
        val gearNow = if (i < 150 && phase < 0.06f) 0 else g
        val kph = if (gearNow > 0) revs / 60f / (feedCfg.gearRatios[gearNow - 1] * feedCfg.finalDrive) * feedCfg.tireCircumferenceM * 3.6f else speedKph
        set(Channel.SPEED, kph, 0x139)
        set(Channel.GEAR, (if (i < 150 && phase < 0.06f) 0 else g).toFloat(), 0x241)
        set(Channel.NEUTRAL, if (i < 150 && phase < 0.06f) 1f else 0f, 0x40)
        VehicleData.countFrame(now)
        set(Channel.THROTTLE, 64f, 0x40)
        set(Channel.BRAKE, 0f, 0x139)
        set(Channel.BRAKE_LIGHT, 0f, 0x139)
        set(Channel.CLUTCH, 0f, 0x241)
        set(Channel.REVERSE, 0f, 0x228)
        set(Channel.STEERING, -14f, 0x138)
        if (kph > 3f) set(Channel.YAW_RATE, -lat * 9.81f / (kph / 3.6f) * 57.3f, 0x138)
        set(Channel.COOLANT, 91f, 0x345)
        set(Channel.OIL_TEMP, 102f, 0x345)
        set(Channel.INTAKE_TEMP, 26f, 0x390)
        VehicleData.set(Channel.BATTERY, 14.2f, now, VehicleData.SRC_OBD, 0x42)
        set(Channel.FUEL, 58f, 0x393)
        VehicleData.set(Channel.LAT_G, lat, now, VehicleData.SRC_PHONE)
        VehicleData.set(Channel.LON_G, -0.3f, now, VehicleData.SRC_PHONE)
        set(Channel.TIRE_FL, 36f * 6.8948f, 0x6E2)
        set(Channel.TIRE_FR, 36f * 6.8948f, 0x6E2)
        set(Channel.TIRE_RL, 34f * 6.8948f, 0x6E2)
        set(Channel.TIRE_RR, 35f * 6.8948f, 0x6E2)
        VehicleData.setStatus(LinkState.CONNECTED, "DauntlessOBD · body CAN via ASC · Bluetooth LE")
        // raw traffic for the sniffer and the boot's node check, at roughly the car's rates
        for ((id, hz) in ASC_RATES) {
            val every = 1000L / hz
            fun send(at: Long) = VehicleData.onFrame(CanFrame(id, ByteArray(8) { b -> ((id * 7 + b * 31 + (at / 200).toInt() * (b % 3)) and 0xFF).toByte() }), at)
            if (every >= 50) { if (now / every != (now - 50) / every) send(now) }
            else { var at = now - 50 + every; while (at <= now) { send(at); at += every } }
        }
    }

    private fun shot(
        name: String, w: Int, h: Int, page: DashRenderer.Page, rpm: Float = 0f, speed: Float = 0f, gear: Int = 3,
        theme: String? = null, cam: DashRenderer.Cam = DashRenderer.Cam.QUARTER,
        yaw: Float? = null, stock: Boolean = false,
        /** Run the demo drive for this many frames instead of the fixed feed. */
        sim: Int? = null,
        /** Capture this many ms into the full boot (or, with [short], the system check). */
        boot: Long? = null, short: Boolean = false,
        poweredOff: Boolean = false, switcher: Boolean = false,
        /** Draw as on the Android Auto screen. */
        car: Boolean = false,
    ) {
        val r = DashRenderer(ctx.assets).also { it.page = page; it.cam = cam; it.carScreen = car }
        r.bootPending = false
        // ORBIT holds a hand-set angle: orbit() moves the target by -dx * 0.008 from 0.7 rad.
        if (yaw != null) { r.cam = DashRenderer.Cam.ORBIT; r.orbit((0.7f - yaw) / 0.008f) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val area = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val cfg = cfg(theme, stock, demo = sim != null)
        feedCfg = cfg
        if (sim == null) r.resetSession()
        VehicleData.clearAll()
        VehicleData.sniffer.clear()
        if (sim == null) { com.sickdash.data.Navigation.clearDemo(); com.sickdash.data.NowPlaying.clearDemo() }
        VehicleData.linkMode = if (sim != null) "DEMO" else "CAN"
        VehicleData.nodes = if (sim != null) emptyMap()
            else CanProfile.parse(ctx.assets.open(cfg.car.profileAsset).bufferedReader().use { it.readText() }).nodes
        var i = 0
        fun frame(fed: Boolean = true) {
            if (sim != null) { clock += 33; if (fed) drive.step(cfg, 0.033f, clock) }
            else { clock += 50; if (fed) feed(rpm, speed, gear, i) }
            r.draw(c, area, VehicleData.snapshot(clock), cfg)
            i++
        }
        repeat(sim ?: 220) { frame() }
        if (switcher) r.switcherOpen = true
        if (boot != null) {
            r.playBoot(full = !short)
            val until = clock + boot
            frame()
            while (clock < until) frame()
        }
        if (poweredOff) repeat(130) { frame(fed = false) }
        if (switcher) frame()
        val dir = File("build/screenshots").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Warms the demo drive up so the timers, dyno and shift scores have numbers in them. */
    private fun warmUp(seconds: Int) {
        val r = DashRenderer(ctx.assets).also { it.page = DashRenderer.Page.BUS; it.bootPending = false }
        val bmp = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cfg = cfg(demo = true)
        repeat(seconds * 30) {
            clock += 33
            drive.step(cfg, 0.033f, clock)
            r.draw(c, RectF(0f, 0f, 320f, 200f), VehicleData.snapshot(clock), cfg)
        }
    }

    companion object {
        /** Ids the ASC connector carries, with roughly how often each is sent (Hz). */
        val ASC_RATES = listOf(0x40 to 100, 0x41 to 100, 0x138 to 50, 0x139 to 50, 0x13B to 50, 0x13C to 50, 0x241 to 20,
            0x345 to 10, 0x390 to 10, 0x393 to 5, 0x228 to 10, 0x6E1 to 2, 0x6E2 to 2)
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
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean("glow", false).commit()
        shot("drive_noglow_800x480", 800, 480, DashRenderer.Page.DRIVE, 4300f, 88f, 3)
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().remove("glow").commit()
        shot("engine_1280x720", 1280, 720, DashRenderer.Page.ENGINE, 5200f, 96f, 3, "cyberpunk")
        shot("handling_1280x720", 1280, 720, DashRenderer.Page.HANDLING, 5200f, 96f, 3)
        shot("body_1280x720", 1280, 720, DashRenderer.Page.BODY, 2400f, 48f, 3)
        shot("diff_asc_1280x720", 1280, 720, DashRenderer.Page.DIFF, 2400f, 48f, 3)
        shot("systems_1280x720", 1280, 720, DashRenderer.Page.SYSTEMS, 3100f, 64f, 4)
        shot("style_1280x720", 1280, 720, DashRenderer.Page.STYLE, 2500f, 40f, 2, "matrix")
        shot("phone_portrait_1080x2340", 1080, 2340, DashRenderer.Page.DRIVE, 3800f, 72f, 3)

        // the start-up animation
        shot("boot_1_intro_1280x720", 1280, 720, DashRenderer.Page.DRIVE, 900f, 0f, 0, boot = 1300)
        shot("boot_2_car_1280x720", 1280, 720, DashRenderer.Page.DRIVE, 900f, 0f, 0, boot = 2200)
        shot("boot_3_check_1280x720", 1280, 720, DashRenderer.Page.DRIVE, 900f, 0f, 0, boot = 3900)
        shot("boot_4_sweep_1280x720", 1280, 720, DashRenderer.Page.DRIVE, 900f, 0f, 0, boot = 5150)
        shot("system_check_1280x720", 1280, 720, DashRenderer.Page.DRIVE, 900f, 0f, 0, boot = 2600, short = true)
        shot("powered_off_1280x720", 1280, 720, DashRenderer.Page.DRIVE, 3100f, 64f, 4, poweredOff = true)
        shot("bus_1280x720", 1280, 720, DashRenderer.Page.BUS, 3100f, 64f, 4)
        shot("pages_1280x720", 1280, 720, DashRenderer.Page.DRIVE, 3100f, 64f, 4, switcher = true)

        // pages that need a drive behind them: the demo drive, run on the test clock
        warmUp(150)
        shot("dyno_1280x720", 1280, 720, DashRenderer.Page.DYNO, sim = 240)
        shot("clutch_1280x720", 1280, 720, DashRenderer.Page.CLUTCH, sim = 120)
        shot("shift_1280x720", 1280, 720, DashRenderer.Page.SHIFT, sim = 180)
        shot("throttle_1280x720", 1280, 720, DashRenderer.Page.THROTTLE, sim = 150)
        shot("vdc_1280x720", 1280, 720, DashRenderer.Page.VDC, sim = 120)
        shot("diff_1280x720", 1280, 720, DashRenderer.Page.DIFF, sim = 60)
        shot("timers_1280x720", 1280, 720, DashRenderer.Page.TIMERS, sim = 300)
        shot("demo_drive_1280x720", 1280, 720, DashRenderer.Page.DRIVE, sim = 200)
        shot("body_demo_1280x720", 1280, 720, DashRenderer.Page.BODY, sim = 260)
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString("dash_tiles", "vehicle,music,clutch,dyno,vdc,shift").commit()
        shot("demo_drive_wide_1920x720", 1920, 720, DashRenderer.Page.DRIVE, sim = 90, theme = "wrblue")
        shot("car_screen_demo_1280x720", 1280, 720, DashRenderer.Page.DRIVE, sim = 30, car = true)
        shot("demo_shift_nav_1280x720", 1280, 720, DashRenderer.Page.SHIFT, sim = 30)
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().remove("dash_tiles").commit()
        shot("body_demo_800x480", 800, 480, DashRenderer.Page.BODY, sim = 40)
    }
}
