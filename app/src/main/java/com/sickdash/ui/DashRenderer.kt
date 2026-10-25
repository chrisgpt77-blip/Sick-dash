package com.sickdash.ui

import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.sickdash.data.Channel
import com.sickdash.data.DashConfig
import com.sickdash.data.GearEstimator
import com.sickdash.data.History
import com.sickdash.data.LinkState
import com.sickdash.data.SessionStats
import com.sickdash.data.ShiftTracker
import com.sickdash.data.Snapshot
import com.sickdash.data.Source
import com.sickdash.data.VehicleData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole HUD on any Canvas — the phone's [DashView] and the Android Auto surface share it.
 *
 * Layout: a header strip, a fixed instrument column on the left (speed, gear, revs, status
 * chips, pedals, clutch, coolant), a page area on the right, and a tab bar along the bottom.
 * Session timers and the page idea are borrowed from celica-dash (github.com/Kaito-Yagami/celica-dash).
 */
class DashRenderer(assets: AssetManager) {
    enum class Page(val title: String) {
        DRIVE("Drive"), SHIFT("Shift"), ENGINE("Engine"), HANDLING("Handling"), BUS("Bus"), STYLE("Style")
    }

    enum class Cam(val title: String) { AUTO("Auto"), ORBIT("Orbit"), CHASE("Chase"), QUARTER("3/4"), SIDE("Side"), TOP("Top") }

    @Volatile var page = Page.DRIVE
    @Volatile var cam = Cam.AUTO

    /** Called when a theme is tapped on the Style page; the host saves it. */
    var onThemePicked: ((String) -> Unit)? = null

    fun nextPage() { page = Page.entries[(page.ordinal + 1) % Page.entries.size] }

    fun resetSession() {
        SessionStats.reset()
        ShiftTracker.reset()
        trailCount = 0
    }

    /** Rotate the car by hand (drag / scroll). */
    fun orbit(dx: Float) {
        if (cam == Cam.AUTO) cam = Cam.ORBIT
        targetYaw -= dx * 0.008f
    }

    /** Hit-test a tap against the tabs and buttons drawn last frame. */
    fun handleTap(x: Float, y: Float): Boolean {
        for ((r, action) in hits) if (r.contains(x, y)) { action(); return true }
        return false
    }

    init {
        Fonts.init(assets)
    }

    private var t = Theme.ALL[0]
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var u = 1f
    private var newHits = ArrayList<Pair<RectF, () -> Unit>>()
    @Volatile private var hits: List<Pair<RectF, () -> Unit>> = emptyList()

    private var rpmShown = 0f
    private var speedShown = 0f
    private var latShown = 0f
    private var lonShown = 0f
    private val trail = FloatArray(TRAIL * 2)
    private var trailHead = 0
    private var trailCount = 0

    private var lastFrame = 0L
    private var yaw = 0.7f
    private var pitch = 0.3f
    private var targetYaw = 0.7f
    private var wheelSpin = 0f
    private var roadOffset = 0f

    // =========================================================================================

    fun draw(canvas: Canvas, area: RectF, s: Snapshot, cfg: DashConfig) {
        t = Theme.of(cfg.themeKey)
        val dt = if (lastFrame == 0L) 0.033f else ((s.now - lastFrame) / 1000f).coerceIn(0f, 0.2f)
        lastFrame = s.now
        newHits = ArrayList()

        rpmShown += ((s[Channel.RPM] ?: 0f) - rpmShown) * 0.45f
        speedShown += ((s[Channel.SPEED] ?: 0f) - speedShown) * 0.35f
        latShown += ((s[Channel.LAT_G, 1000] ?: 0f) - latShown) * 0.3f
        lonShown += ((s[Channel.LON_G, 1000] ?: 0f) - lonShown) * 0.3f
        trail[trailHead * 2] = latShown
        trail[trailHead * 2 + 1] = lonShown
        trailHead = (trailHead + 1) % TRAIL
        trailCount = min(trailCount + 1, TRAIL)
        val gear = gearNumber(s, cfg)
        SessionStats.update(s, cfg.imperial)
        ShiftTracker.update(s, gear, cfg)
        History.update(s, gear)
        updateCamera(dt)

        val w = area.width()
        val h = area.height()
        val portrait = h > w * 1.1f
        u = (if (portrait) w / 420f else h / 400f).coerceAtLeast(0.5f)

        fill.color = t.bg
        canvas.drawRect(area, fill)
        val pad = 8f * u
        val headerH = 26f * u
        val tabsH = 26f * u
        header(canvas, RectF(area.left + pad, area.top + pad * 0.5f, area.right - pad, area.top + headerH), s, cfg)
        tabs(canvas, RectF(area.left + pad, area.bottom - tabsH - pad * 0.6f, area.right - pad, area.bottom - pad * 0.6f))

        val top = area.top + headerH + pad
        val bottom = area.bottom - tabsH - pad * 1.4f
        val left: RectF
        val main: RectF
        if (portrait) {
            left = RectF(area.left + pad, top, area.right - pad, top + (bottom - top) * 0.5f)
            main = RectF(area.left + pad, left.bottom + pad, area.right - pad, bottom)
        } else {
            val lw = (w * 0.3f).coerceIn(150f * u, w * 0.38f)
            left = RectF(area.left + pad, top, area.left + pad + lw, bottom)
            main = RectF(left.right + pad, top, area.right - pad, bottom)
        }
        instrumentColumn(canvas, left, s, cfg, gear)
        when (page) {
            Page.DRIVE -> drivePage(canvas, main, s, cfg, dt)
            Page.SHIFT -> shiftPage(canvas, main, s, cfg)
            Page.ENGINE -> enginePage(canvas, main, s, cfg)
            Page.HANDLING -> handlingPage(canvas, main, s, cfg)
            Page.BUS -> busPage(canvas, main, s)
            Page.STYLE -> stylePage(canvas, main, cfg, dt)
        }
        hits = newHits
    }

    // =========================================================================================
    // Header and tabs
    // =========================================================================================

    private fun header(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
        val y = r.centerY()
        display(c, cfg.car.chassis, r.left, y + 6f * u, 17f * u, t.text, glow = true)
        val cw = textWidth(cfg.car.chassis, Fonts.display, 17f * u, 0.04f)
        label(c, "SICK DASH", r.left + cw + 7f * u, y + 4f * u, 9f * u, t.dim, spacing = 0.28f)

        val src = if (cfg.source == Source.DEMO) "DEMO" else VehicleData.linkMode
        val fresh = Channel.entries.count { s[it, 2000] != null }
        val chip = "■ $src · ${"%,d".format(s.framesPerSecond)} MSG/S · $fresh SIGNALS"
        val chipX = r.left + cw + 80f * u
        val chipW = textWidth(chip, Fonts.mono, 8f * u, 0.12f) + 12f * u
        if (chipX + chipW < r.centerX() + 40f * u) {
            rect.set(chipX, y - 7f * u, chipX + chipW, y + 7f * u)
            fill.color = t.panel
            c.drawRect(rect, fill)
            label(c, chip, chipX + 6f * u, y + 3f * u, 8f * u, t.dim)
        }

        // right: link state, gearbox, clock
        var x = r.right
        val time = clock.format(Date(s.now))
        display(c, time, x, y + 6f * u, 15f * u, t.text, Paint.Align.RIGHT)
        x -= textWidth(time, Fonts.display, 15f * u, 0.04f) + 14f * u
        val (word, col) = when (s.link.state) {
            LinkState.CONNECTED -> "LINKED" to t.text
            LinkState.CONNECTING -> "LINKING" to t.secondary
            LinkState.ERROR -> "NO LINK" to t.primary
            LinkState.IDLE -> "OFFLINE" to t.dim
        }
        display(c, word, x, y + 5f * u, 11f * u, col, Paint.Align.RIGHT)
        x -= textWidth(word, Fonts.display, 11f * u, 0.04f) + 8f * u
        fill.color = col
        c.drawCircle(x, y + 1f * u, 3f * u, fill)
        x -= 16f * u
        val box = if (cfg.automatic) "${cfg.gearRatios.size}AT" else "${cfg.gearRatios.size}MT"
        display(c, box, x, y + 5f * u, 11f * u, t.dim, Paint.Align.RIGHT)

        stroke.color = t.line
        stroke.strokeWidth = max(1f, u * 0.8f)
        c.drawLine(r.left, r.bottom + 2f * u, r.right, r.bottom + 2f * u, stroke)
    }

    private fun tabs(c: Canvas, r: RectF) {
        val n = Page.entries.size
        val gap = 4f * u
        val tw = min((r.width() - gap * (n - 1)) / n, 74f * u)
        for ((i, p) in Page.entries.withIndex()) {
            val x = r.left + i * (tw + gap)
            val box = RectF(x, r.top, x + tw, r.bottom)
            val active = p == page
            fill.color = if (active) t.text else t.panel
            c.drawRect(box, fill)
            if (!active) {
                stroke.color = t.line
                stroke.strokeWidth = max(1f, u * 0.8f)
                c.drawRect(box, stroke)
            }
            label(c, p.title.uppercase(), box.centerX(), box.centerY() + 3f * u, 8.5f * u, if (active) t.bg else t.dim, Paint.Align.CENTER, 0.1f)
            newHits += box to { page = p }
        }
    }

    // =========================================================================================
    // The instrument column (always visible)
    // =========================================================================================

    private fun instrumentColumn(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig, gear: Int?) {
        val gap = 5f * u
        val nominal = floatArrayOf(96f, 40f, 46f, 30f, 30f, 30f, 30f, 50f)
        val k = (r.height() - gap * (nominal.size - 1)) / nominal.sum()
        var y = r.top
        fun next(i: Int): RectF { val rr = RectF(r.left, y, r.right, y + nominal[i] * k); y += nominal[i] * k + gap; return rr }

        // speed and gear boxes
        val boxes = next(0)
        val speedBox = RectF(boxes.left, boxes.top, boxes.left + boxes.width() * 0.56f, boxes.bottom)
        val gearBox = RectF(speedBox.right + gap, boxes.top, boxes.right, boxes.bottom)
        panel(c, speedBox)
        label(c, if (cfg.imperial) "MPH" else "KM/H", speedBox.left + 6f * u, speedBox.top + 11f * u, 8f * u, t.dim)
        val speed = s[Channel.SPEED]?.let { (if (cfg.imperial) speedShown * KPH_TO_MPH else speedShown).roundToInt().toString() }
        val big = min(boxes.height() * 0.66f, speedBox.width() * 0.5f)
        bigFigure(c, speed ?: "—", speedBox.centerX(), speedBox.centerY() + big * 0.42f, big, if (speed == null) t.faint else t.text)

        val over = rpmShown >= cfg.shiftRpm
        panel(c, gearBox, if (over) t.primary else t.text, 1.6f)
        label(c, "GEAR", gearBox.centerX(), gearBox.top + 11f * u, 8f * u, t.dim, Paint.Align.CENTER)
        val g = gearLabel(gear)
        bigFigure(c, g, gearBox.centerX(), gearBox.centerY() + big * 0.45f, min(big * 1.05f, gearBox.width() * 0.8f),
            if (g == "–") t.faint else if (over) t.primary else t.text)

        // revs: number and a segmented bar
        val rpmRow = next(1)
        panel(c, rpmRow)
        label(c, "RPM", rpmRow.left + 6f * u, rpmRow.bottom - 6f * u, 8f * u, t.dim)
        val rpmTxt = s[Channel.RPM]?.let { "%,d".format(rpmShown.roundToInt()) } ?: "—"
        display(c, rpmTxt, rpmRow.right - 6f * u, rpmRow.top + rpmRow.height() * 0.38f, rpmRow.height() * 0.32f,
            if (over) t.primary else t.text, Paint.Align.RIGHT, glow = true)
        val bar = RectF(rpmRow.left + 30f * u, rpmRow.top + rpmRow.height() * 0.52f, rpmRow.right - 6f * u, rpmRow.bottom - 6f * u)
        segments(c, bar, 32, rpmShown / cfg.maxRpm) { f ->
            val r0 = f * cfg.maxRpm
            when {
                over -> if ((s.now / 90) % 2 == 0L) t.primary else t.faint
                r0 >= cfg.shiftRpm - 1500 -> t.secondary
                else -> t.text
            }
        }

        // status chips
        val chips = next(2)
        val coolant = s[Channel.COOLANT]
        val fuel = s[Channel.FUEL]
        val chipList = listOf(
            Chip("SHIFT", over, t.primary),
            Chip("BRAKE", (s[Channel.BRAKE] ?: 0f) > 5f, t.primary),
            Chip("CLUTCH", (s[Channel.CLUTCH] ?: 0f) >= 50f, t.secondary),
            Chip("LAUNCH", (s[Channel.SPEED] ?: 99f) < 1f && rpmShown > 2500f, t.secondary),
            Chip("COLD", coolant != null && coolant < 60f, t.secondary),
            Chip("LOW FUEL", fuel != null && fuel < 15f, t.secondary),
        )
        val cwid = (chips.width() - gap * 2) / 3
        val chH = (chips.height() - gap) / 2
        for ((i, ch) in chipList.withIndex()) {
            val x = chips.left + (i % 3) * (cwid + gap)
            val yy = chips.top + (i / 3) * (chH + gap)
            chip(c, RectF(x, yy, x + cwid, yy + chH), ch)
        }

        // rows
        val temp = temps(cfg)
        val oil = s[Channel.OIL_TEMP]
        row(c, next(3), "OIL TEMP", oil?.let { temp(it) } ?: "—", tempColour(oil, 70f, 130f, 140f))
        val steer = s[Channel.STEERING]
        val steerRow = next(4)
        row(c, steerRow, "STEER", steer?.let { "${abs(it).roundToInt()}°" } ?: "—", t.text,
            suffix = steer?.let { if (it > 0.5f) "L" else if (it < -0.5f) "R" else "" })
        sliderTrack(c, steerRow, steer?.let { (-it / 180f).coerceIn(-1f, 1f) })
        val thr = s[Channel.THROTTLE]
        val thrRow = next(5)
        row(c, thrRow, "THROTTLE", thr?.let { "${it.roundToInt()}%" } ?: "—", t.text)
        segments(c, rowBar(thrRow), 24, (thr ?: 0f) / 100f) { t.text }
        val brk = s[Channel.BRAKE]
        val brkRow = next(6)
        row(c, brkRow, "BRAKE", brk?.let { if (it < 2f) "OFF" else "${it.roundToInt()}%" } ?: "—", if ((brk ?: 0f) > 5f) t.primary else t.text)
        segments(c, rowBar(brkRow), 24, (brk ?: 0f) / 100f) { t.primary }

        // clutch | coolant
        val last = next(7)
        val clutchBox = RectF(last.left, last.top, last.centerX() - gap / 2, last.bottom)
        val coolBox = RectF(last.centerX() + gap / 2, last.top, last.right, last.bottom)
        val clutch = s[Channel.CLUTCH]
        val clutchIn = (clutch ?: 0f) >= 50f
        panel(c, clutchBox)
        label(c, "CLUTCH", clutchBox.left + 6f * u, clutchBox.centerY() + 3f * u, 8.5f * u, t.dim)
        display(c, clutch?.let { if (clutchIn) "IN" else "OUT" } ?: "—", clutchBox.right - 6f * u, clutchBox.top + clutchBox.height() * 0.45f,
            clutchBox.height() * 0.34f, if (clutchIn) t.secondary else if (clutch == null) t.faint else t.text, Paint.Align.RIGHT, glow = true)
        if (clutchIn) {
            fill.color = t.secondary
            c.drawRect(clutchBox.left + 6f * u, clutchBox.bottom - 7f * u, clutchBox.right - 6f * u, clutchBox.bottom - 4f * u, fill)
        }
        panel(c, coolBox)
        label(c, "COOLANT", coolBox.left + 6f * u, coolBox.centerY() + 3f * u, 8.5f * u, t.dim)
        display(c, coolant?.let { temp(it) } ?: "—", coolBox.right - 6f * u, coolBox.top + coolBox.height() * 0.45f,
            coolBox.height() * 0.34f, tempColour(coolant, 60f, 105f, 112f), Paint.Align.RIGHT, glow = true)
        if (coolant != null) {
            val f = ((coolant - 40f) / 90f).coerceIn(0f, 1f)
            fill.color = tempColour(coolant, 60f, 105f, 112f)
            c.drawRect(coolBox.left + 6f * u, coolBox.bottom - 7f * u, coolBox.left + 6f * u + (coolBox.width() - 12f * u) * f, coolBox.bottom - 4f * u, fill)
        }
    }

    private class Chip(val name: String, val on: Boolean, val colour: Int)

    private fun chip(c: Canvas, r: RectF, ch: Chip) {
        if (ch.on) {
            fill.color = withAlpha(ch.colour, 60)
            c.drawRect(r, fill)
            stroke.color = ch.colour
            stroke.strokeWidth = 1.2f * u
            c.drawRect(r, stroke)
        } else {
            fill.color = t.panel
            c.drawRect(r, fill)
        }
        label(c, ch.name, r.centerX(), r.centerY() + 3f * u, min(8f * u, r.height() * 0.42f), if (ch.on) ch.colour else t.faint, Paint.Align.CENTER, 0.08f)
    }

    private fun row(c: Canvas, r: RectF, name: String, value: String, colour: Int, suffix: String? = null) {
        panel(c, r)
        label(c, name, r.left + 6f * u, r.centerY() + 3.5f * u, 8.5f * u, t.dim)
        val size = r.height() * 0.6f
        var x = r.right - 6f * u
        if (!suffix.isNullOrEmpty()) {
            label(c, suffix, x, r.centerY() + size * 0.36f, 8f * u, t.dim, Paint.Align.RIGHT)
            x -= 10f * u
        }
        display(c, value, x, r.centerY() + size * 0.36f, size, if (value == "—") t.faint else colour, Paint.Align.RIGHT, glow = true)
    }

    private fun rowBar(r: RectF) = RectF(r.left + r.width() * 0.34f, r.top + r.height() * 0.32f, r.left + r.width() * 0.72f, r.bottom - r.height() * 0.32f)

    private fun sliderTrack(c: Canvas, r: RectF, pos: Float?) {
        val b = rowBar(r)
        fill.color = t.line
        c.drawRect(b.left, b.centerY() - 1.5f * u, b.right, b.centerY() + 1.5f * u, fill)
        if (pos != null) {
            val x = b.centerX() + pos * b.width() / 2
            fill.color = t.text
            c.drawRect(x - 1.5f * u, b.top - 2f * u, x + 1.5f * u, b.bottom + 2f * u, fill)
        }
    }

    private fun segments(c: Canvas, r: RectF, n: Int, frac: Float, colourAt: (Float) -> Int) {
        val gap = max(1f, r.width() * 0.006f)
        val sw = (r.width() - gap * (n - 1)) / n
        val lit = (frac.coerceIn(0f, 1f) * n)
        for (i in 0 until n) {
            val x = r.left + i * (sw + gap)
            fill.color = if (i < lit) colourAt(i.toFloat() / n) else withAlpha(t.faint, 90)
            c.drawRect(x, r.top, x + sw, r.bottom, fill)
        }
    }

    // =========================================================================================
    // DRIVE: the live wireframe car
    // =========================================================================================

    private fun drivePage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig, dt: Float) {
        frame(c, r)
        val wide = r.width() > r.height() * 1.6f
        val side = if (wide) RectF(r.right - r.width() * 0.27f, r.top + 8f * u, r.right - 8f * u, r.bottom - 8f * u) else null
        val stage = RectF(r.left + 4f * u, r.top + 4f * u, (side?.left ?: r.right) - 6f * u, r.bottom - 4f * u)

        val speedMs = (s[Channel.SPEED] ?: 0f) / 3.6f
        wheelSpin -= speedMs / CarWireframe.WHEEL_R * dt
        roadOffset = (roadOffset + speedMs * dt) % GRID
        val live = s.link.state == LinkState.CONNECTED || s[Channel.RPM] != null
        drawCar(c, stage, s, cfg, live, callouts = true)

        // camera buttons and caption
        var x = stage.right - 6f * u
        for (cm in Cam.entries.reversed()) {
            val w = textWidth(cm.title.uppercase(), Fonts.mono, 7.5f * u, 0.1f) + 10f * u
            val b = RectF(x - w, stage.top + 6f * u, x, stage.top + 20f * u)
            val active = cm == cam
            fill.color = if (active) t.text else t.panel
            c.drawRect(b, fill)
            label(c, cm.title.uppercase(), b.centerX(), b.centerY() + 2.6f * u, 7.5f * u, if (active) t.bg else t.dim, Paint.Align.CENTER, 0.1f)
            newHits += b to { cam = cm }
            x -= w + 3f * u
        }
        label(c, "WIREFRAME · LIVE CAR", stage.right - 6f * u, stage.top + 31f * u, 7f * u, t.dim, Paint.Align.RIGHT)
        display(c, "${cam.title.uppercase()} · ${if ((s[Channel.BRAKE] ?: 0f) > 5f) "BRAKING" else "ORBIT"}",
            stage.right - 6f * u, stage.top + 44f * u, 11f * u, t.text, Paint.Align.RIGHT)
        label(c, "MODEL: PROCEDURAL ${cfg.car.chassis} · ${cfg.car.engine} · ${cfg.subtitle}", stage.left + 8f * u, stage.bottom - 6f * u, 6.5f * u, t.faint)

        if (!live) {
            display(c, "VEHICLE POWERED OFF", stage.centerX(), stage.centerY() - 4f * u, 16f * u, t.text, Paint.Align.CENTER, glow = true)
            label(c, s.link.message.uppercase().take(60), stage.centerX(), stage.centerY() + 12f * u, 7.5f * u, t.dim, Paint.Align.CENTER)
        }

        if (side != null) driveSide(c, side, s, cfg)
    }

    private fun driveSide(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
        val gap = 5f * u
        val tyreH = r.height() * 0.36f
        val tyres = RectF(r.left, r.top, r.right, r.top + tyreH)
        panel(c, tyres)
        label(c, if (cfg.imperial) "TYRES · PSI" else "TYRES · BAR", tyres.left + 6f * u, tyres.top + 11f * u, 7.5f * u, t.dim)
        val names = listOf("FL" to Channel.TIRE_FL, "FR" to Channel.TIRE_FR, "RL" to Channel.TIRE_RL, "RR" to Channel.TIRE_RR)
        val cellW = tyres.width() / 2
        val cellH = (tyres.height() - 16f * u) / 2
        for ((i, p) in names.withIndex()) {
            val cx = tyres.left + (i % 2) * cellW
            val cy = tyres.top + 16f * u + (i / 2) * cellH
            label(c, p.first, cx + 8f * u, cy + 12f * u, 7.5f * u, t.dim)
            val kpa = s[p.second, 15_000]
            val low = kpa != null && kpa < 193f
            val v = kpa?.let { if (cfg.imperial) (it / 6.8948f).roundToInt().toString() else "%.1f".format(it / 100f) } ?: "—"
            display(c, v, cx + cellW - 10f * u, cy + cellH * 0.78f, min(cellH * 0.5f, 18f * u), if (low) t.secondary else if (kpa == null) t.faint else t.text, Paint.Align.RIGHT, glow = kpa != null)
        }
        var y = tyres.bottom + gap
        val rowH = (r.bottom - y - gap * 3) / 4
        val temp = temps(cfg)
        val items = listOf(
            Triple("FUEL", s[Channel.FUEL]?.let { "${it.roundToInt()}%" }, s[Channel.FUEL]?.let { it / 100f }),
            Triple("AIR", s[Channel.INTAKE_TEMP]?.let { temp(it) }, null),
            Triple("BATTERY", s[Channel.BATTERY]?.let { "%.1f V".format(it) }, null),
            Triple(if (cfg.imperial) "BEST 0-60" else "BEST 0-100", SessionStats.bestLaunchMs?.let { "%.2f S".format(it / 1000f) }, null),
        )
        for ((name, value, frac) in items) {
            val b = RectF(r.left, y, r.right, y + rowH)
            panel(c, b)
            label(c, name, b.left + 6f * u, b.top + 11f * u, 7.5f * u, t.dim)
            display(c, value ?: "—", b.right - 6f * u, b.bottom - (if (frac != null) 9f else 6f) * u, min(b.height() * 0.42f, 16f * u),
                if (value == null) t.faint else t.text, Paint.Align.RIGHT, glow = value != null)
            if (frac != null) {
                fill.color = t.text
                c.drawRect(b.left + 6f * u, b.bottom - 6f * u, b.left + 6f * u + (b.width() - 12f * u) * frac.coerceIn(0f, 1f), b.bottom - 4f * u, fill)
            }
            y += rowH + gap
        }
    }

    // ---- 3D --------------------------------------------------------------------------------

    private fun updateCamera(dt: Float) {
        val (ty, tp) = when (cam) {
            Cam.AUTO -> { targetYaw += 0.22f * dt; targetYaw to 0.30f }
            Cam.ORBIT -> targetYaw to 0.30f
            Cam.CHASE -> PI.toFloat() to 0.20f
            Cam.QUARTER -> 0.62f to 0.28f
            Cam.SIDE -> (PI / 2).toFloat() to 0.04f
            Cam.TOP -> (PI / 2).toFloat() to 1.40f
        }
        if (cam != Cam.AUTO && cam != Cam.ORBIT) targetYaw = ty
        var d = (ty - yaw) % (2 * PI.toFloat())
        if (d > PI) d -= 2 * PI.toFloat()
        if (d < -PI) d += 2 * PI.toFloat()
        yaw += d * min(1f, dt * 4f)
        pitch += (tp - pitch) * min(1f, dt * 4f)
    }

    private class Proj(val ox: Float, val oy: Float, val f: Float, val cam: FloatArray, val right: FloatArray, val up: FloatArray, val fwd: FloatArray)

    private fun projection(r: RectF, distScale: Float = 1f): Proj {
        val dist = 6.4f * distScale
        val target = floatArrayOf(0f, 0.55f, 0f)
        val camPos = floatArrayOf(
            target[0] + dist * cos(pitch) * cos(yaw), target[1] + dist * sin(pitch), target[2] + dist * cos(pitch) * sin(yaw),
        )
        val fwd = norm(floatArrayOf(target[0] - camPos[0], target[1] - camPos[1], target[2] - camPos[2]))
        val right = norm(cross(fwd, floatArrayOf(0f, 1f, 0f)))
        val up = cross(right, fwd)
        return Proj(r.centerX(), r.centerY() + r.height() * 0.06f, min(r.width() * 0.95f, r.height() * 1.55f), camPos, right, up, fwd)
    }

    private val ca = FloatArray(3)
    private val cb = FloatArray(3)

    /** World point to camera space (right, up, depth). */
    private fun toCam(p: Proj, x: Float, y: Float, z: Float, out: FloatArray) {
        val vx = x - p.cam[0]; val vy = y - p.cam[1]; val vz = z - p.cam[2]
        out[0] = vx * p.right[0] + vy * p.right[1] + vz * p.right[2]
        out[1] = vx * p.up[0] + vy * p.up[1] + vz * p.up[2]
        out[2] = vx * p.fwd[0] + vy * p.fwd[1] + vz * p.fwd[2]
    }

    /** Projects a world point into [out] (x, y); returns false when it is behind the camera. */
    private fun project(p: Proj, x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        toCam(p, x, y, z, ca)
        if (ca[2] < NEAR) return false
        out[0] = p.ox + p.f * ca[0] / ca[2]
        out[1] = p.oy - p.f * ca[1] / ca[2]
        return true
    }

    private val pa = FloatArray(2)
    private val pb = FloatArray(2)

    /** Adds world-space segments to [into], clipping any that cross behind the camera. */
    private fun addSegments(p: Proj, segs: FloatArray, into: Path, transform: ((FloatArray) -> Unit)? = null) {
        val a = FloatArray(3)
        val b = FloatArray(3)
        var i = 0
        while (i + 5 < segs.size) {
            a[0] = segs[i]; a[1] = segs[i + 1]; a[2] = segs[i + 2]
            b[0] = segs[i + 3]; b[1] = segs[i + 4]; b[2] = segs[i + 5]
            i += 6
            transform?.invoke(a); transform?.invoke(b)
            toCam(p, a[0], a[1], a[2], ca)
            toCam(p, b[0], b[1], b[2], cb)
            if (ca[2] < NEAR && cb[2] < NEAR) continue
            if (ca[2] < NEAR || cb[2] < NEAR) {
                val (near, far) = if (ca[2] < NEAR) ca to cb else cb to ca
                val k = (NEAR - far[2]) / (near[2] - far[2])
                for (j in 0..2) near[j] = far[j] + (near[j] - far[j]) * k
            }
            into.moveTo(p.ox + p.f * ca[0] / ca[2], p.oy - p.f * ca[1] / ca[2])
            into.lineTo(p.ox + p.f * cb[0] / cb[2], p.oy - p.f * cb[1] / cb[2])
        }
    }

    private fun glowPath(c: Canvas, p: Path, colour: Int, width: Float) {
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = withAlpha(colour, 38)
        stroke.strokeWidth = width * 4.5f
        c.drawPath(p, stroke)
        stroke.color = withAlpha(colour, 80)
        stroke.strokeWidth = width * 2.2f
        c.drawPath(p, stroke)
        stroke.color = colour
        stroke.strokeWidth = width
        c.drawPath(p, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawCar(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig, live: Boolean, callouts: Boolean) {
        c.save()
        c.clipRect(r)
        val p = projection(r)

        // the floor: a grid that streams past with road speed
        val gridPath = Path()
        val ext = 9f
        var k = -ext
        while (k <= ext) {
            val xo = k - roadOffset
            addSegments(p, floatArrayOf(xo, 0f, -ext, xo, 0f, ext), gridPath)
            addSegments(p, floatArrayOf(-ext, 0f, k, ext, 0f, k), gridPath)
            k += GRID
        }
        stroke.color = withAlpha(t.grid, 46)
        stroke.strokeWidth = max(1f, 0.8f * u)
        c.drawPath(gridPath, stroke)

        val colour = if (live) t.primary else withAlpha(t.primary, 90)
        val body = Path()
        addSegments(p, CarWireframe.body, body)
        addSegments(p, CarWireframe.headLights, body)
        val steerRad = Math.toRadians(((s[Channel.STEERING] ?: 0f) / 13.5f).toDouble()).toFloat()
        for ((ax, front) in listOf(CarWireframe.FRONT_AXLE to true, CarWireframe.REAR_AXLE to false)) {
            for (side in floatArrayOf(-1f, 1f)) {
                val cz = side * CarWireframe.TRACK_HALF
                val segs = CarWireframe.wheel(ax, cz, wheelSpin)
                if (front && steerRad != 0f) {
                    val cs = cos(steerRad); val sn = sin(steerRad)
                    addSegments(p, segs, body) { v ->
                        val dx = v[0] - ax; val dz = v[2] - cz
                        v[0] = ax + dx * cs - dz * sn; v[2] = cz + dx * sn + dz * cs
                    }
                } else addSegments(p, segs, body)
            }
        }
        glowPath(c, body, colour, 1.3f * u)

        val braking = (s[Channel.BRAKE] ?: 0f) > 5f
        val tails = Path()
        addSegments(p, CarWireframe.tailLights, tails)
        glowPath(c, tails, if (braking) t.text else colour, (if (braking) 2.6f else 1.3f) * u)

        if (callouts && live) {
            val wheels = listOf(
                Triple("FL", Channel.TIRE_FL, floatArrayOf(CarWireframe.FRONT_AXLE, 0.62f, -CarWireframe.TRACK_HALF - 0.1f)),
                Triple("FR", Channel.TIRE_FR, floatArrayOf(CarWireframe.FRONT_AXLE, 0.62f, CarWireframe.TRACK_HALF + 0.1f)),
                Triple("RL", Channel.TIRE_RL, floatArrayOf(CarWireframe.REAR_AXLE, 0.62f, -CarWireframe.TRACK_HALF - 0.1f)),
                Triple("RR", Channel.TIRE_RR, floatArrayOf(CarWireframe.REAR_AXLE, 0.62f, CarWireframe.TRACK_HALF + 0.1f)),
            )
            val speed = s[Channel.SPEED]?.let { (if (cfg.imperial) it * KPH_TO_MPH else it).roundToInt() }
            toCam(p, 0f, 0.6f, 0f, cb)
            val centreDepth = cb[2]
            for ((name, ch, pos) in wheels) {
                toCam(p, pos[0], pos[1], pos[2], cb)
                if (cb[2] > centreDepth + 0.45f) continue // far side: hidden behind the body
                if (!project(p, pos[0], pos[1], pos[2], pa)) continue
                val kpa = s[ch, 15_000]
                val txt = when {
                    kpa != null -> "$name ${if (cfg.imperial) "${(kpa / 6.8948f).roundToInt()} PSI" else "%.1f BAR".format(kpa / 100f)}"
                    speed != null -> "$name $speed ${if (cfg.imperial) "MPH" else "KM/H"}"
                    else -> continue
                }
                tag(c, txt, pa[0], pa[1], 6.5f * u, false)
            }
            if (braking && project(p, -2.3f, 1.15f, 0f, pa)) tag(c, "BRAKE ${(s[Channel.BRAKE] ?: 0f).roundToInt()}%", pa[0], pa[1], 8f * u, true)
            if (rpmShown >= cfg.shiftRpm && project(p, 1.0f, 1.45f, 0f, pa)) tag(c, "SHIFT", pa[0], pa[1], 9f * u, true)
        }
        c.restore()
    }

    /** A callout box with a hairline border, like a HUD tag. */
    private fun tag(c: Canvas, s: String, x: Float, y: Float, size: Float, strong: Boolean) {
        val w = textWidth(s, Fonts.mono, size, 0.1f) + size * 1.2f
        val h = size * 1.8f
        rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        fill.color = if (strong) t.text else withAlpha(t.bg, 210)
        c.drawRect(rect, fill)
        stroke.color = t.text
        stroke.strokeWidth = max(1f, 0.8f * u)
        c.drawRect(rect, stroke)
        label(c, s, x, y + size * 0.36f, size, if (strong) t.bg else t.text, Paint.Align.CENTER, 0.1f)
    }

    // =========================================================================================
    // SHIFT: live revs and a rev-match score for every gear change
    // =========================================================================================

    private fun shiftPage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
        frame(c, r)
        val inner = RectF(r.left + 10f * u, r.top + 8f * u, r.right - 10f * u, r.bottom - 8f * u)
        display(c, "SHIFTS · REV-MATCH SCORE", inner.left, inner.top + 13f * u, 13f * u, t.text, glow = true)
        label(c, "LIVE · GEAR · RPM · CLUTCH", inner.right, inner.top + 10f * u, 7f * u, t.dim, Paint.Align.RIGHT)
        val body = RectF(inner.left, inner.top + 22f * u, inner.right, inner.bottom - 14f * u)
        val leftW = body.width() * 0.3f
        val box = RectF(body.left, body.top, body.left + leftW, body.bottom)

        val last = ShiftTracker.last
        panel(c, box)
        label(c, "LAST SHIFT", box.left + 6f * u, box.top + 11f * u, 7.5f * u, t.dim)
        if (last == null) {
            display(c, "NO", box.left + 8f * u, box.top + box.height() * 0.32f, 18f * u, t.text, glow = true)
            display(c, "RECORD", box.left + 8f * u, box.top + box.height() * 0.32f + 18f * u, 18f * u, t.text, glow = true)
            label(c, "SHIFT TO SCORE", box.left + 8f * u, box.bottom - 10f * u, 7f * u, t.faint)
        } else {
            val col = ratingColour(last.score)
            display(c, "${last.from} → ${last.to}", box.left + 8f * u, box.top + box.height() * 0.3f, 22f * u, t.text, glow = true)
            display(c, last.rating, box.left + 8f * u, box.top + box.height() * 0.3f + 18f * u, 12f * u, col, glow = true)
            display(c, last.score.toString(), box.left + 8f * u, box.top + box.height() * 0.72f, 26f * u, col, glow = true)
            label(c, "/ 100", box.left + 10f * u + textWidth(last.score.toString(), Fonts.display, 26f * u, 0.04f), box.top + box.height() * 0.72f, 8f * u, t.dim)
            label(c, "TARGET ${"%,d".format(last.target.roundToInt())}  ACTUAL ${"%,d".format(last.rpm.roundToInt())}", box.left + 8f * u, box.bottom - 10f * u, 6.5f * u, t.dim)
        }

        // graph: last 10 s of revs with gear changes marked
        val g = RectF(box.right + 10f * u, body.top, body.right, body.bottom)
        panel(c, g)
        val plot = RectF(g.left + 26f * u, g.top + 10f * u, g.right - 8f * u, g.bottom - 14f * u)
        val maxRpm = cfg.maxRpm.toFloat()
        fill.color = withAlpha(t.primary, 30)
        c.drawRect(plot.left, plot.top, plot.right, plot.bottom - plot.height() * cfg.redline / maxRpm, fill)
        stroke.color = t.line
        stroke.strokeWidth = max(1f, 0.8f * u)
        var k = 2000
        while (k < cfg.maxRpm) {
            val y = plot.bottom - plot.height() * k / maxRpm
            c.drawLine(plot.left, y, plot.right, y, stroke)
            label(c, "${k / 1000}K", plot.left - 4f * u, y + 3f * u, 7f * u, t.dim, Paint.Align.RIGHT)
            k += 2000
        }
        label(c, "-10 S", plot.left, g.bottom - 4f * u, 6.5f * u, t.faint)
        label(c, "NOW", plot.right, g.bottom - 4f * u, 6.5f * u, t.faint, Paint.Align.RIGHT)
        val series = History.series(Channel.RPM)
        traceLine(c, plot, series, 0f, maxRpm, t.text, 1.6f * u)
        val now = s.now
        for ((at, gear) in History.gearMarks) {
            val x = plot.right - plot.width() * (now - at) / 10_000f
            if (x < plot.left) continue
            stroke.color = withAlpha(t.secondary, 160)
            stroke.strokeWidth = max(1f, 0.8f * u)
            var y = plot.top
            while (y < plot.bottom) { c.drawLine(x, y, x, min(y + 4f * u, plot.bottom), stroke); y += 7f * u }
            label(c, gear.toString(), x + 3f * u, plot.top + 9f * u, 8f * u, t.secondary)
        }

        // legend
        var x = inner.left
        for ((name, col) in listOf("PERFECT 90+" to ratingColour(95), "CLEAN 75+" to ratingColour(80), "GOOD 55+" to ratingColour(60),
            "ROUGH 30+" to ratingColour(40), "MISSED" to ratingColour(0))) {
            label(c, name, x, inner.bottom, 7f * u, col)
            x += textWidth(name, Fonts.mono, 7f * u, 0.16f) + 12f * u
        }
        label(c, "${ShiftTracker.count} SHIFTS", inner.right, inner.bottom, 7f * u, t.dim, Paint.Align.RIGHT)
    }

    private fun ratingColour(score: Int) = when {
        score >= 90 -> t.primary
        score >= 75 -> t.text
        score >= 55 -> t.dim
        score >= 30 -> t.secondary
        else -> t.faint
    }

    private fun traceLine(c: Canvas, r: RectF, data: FloatArray, lo: Float, hi: Float, colour: Int, width: Float) {
        path.reset()
        var started = false
        for (i in data.indices) {
            val v = data[i]
            if (v.isNaN()) { started = false; continue }
            val x = r.left + r.width() * i / (data.size - 1)
            val y = r.bottom - r.height() * ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            if (started) path.lineTo(x, y) else { path.moveTo(x, y); started = true }
        }
        stroke.color = withAlpha(colour, 60)
        stroke.strokeWidth = width * 3f
        c.drawPath(path, stroke)
        stroke.color = colour
        stroke.strokeWidth = width
        c.drawPath(path, stroke)
    }

    // =========================================================================================
    // ENGINE: every reading with a live trace
    // =========================================================================================

    private fun enginePage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
        frame(c, r)
        val inner = RectF(r.left + 10f * u, r.top + 8f * u, r.right - 10f * u, r.bottom - 8f * u)
        val mode = when {
            cfg.source == Source.DEMO -> "DEMO"
            VehicleData.linkMode == "OBD" -> "OBD-II"
            else -> VehicleData.linkMode
        }
        display(c, "ENGINE · $mode", inner.left, inner.top + 13f * u, 13f * u, t.text, glow = true)
        label(c, "LISTENING · ${s.framesPerSecond} MSG/S", inner.right, inner.top + 10f * u, 7f * u, t.dim, Paint.Align.RIGHT)
        val grid = RectF(inner.left, inner.top + 22f * u, inner.right, inner.bottom)
        val toF: (Float) -> Float = { if (cfg.imperial) it * 9f / 5f + 32f else it }
        val deg = if (cfg.imperial) "°F" else "°C"
        val cells = listOf(
            Spark("ENGINE SPEED", Channel.RPM, s[Channel.RPM]?.let { "%,d".format(it.roundToInt()) }, "RPM", 0f, cfg.maxRpm.toFloat(), null),
            Spark("ACCEL PEDAL", Channel.THROTTLE, s[Channel.THROTTLE]?.let { "${it.roundToInt()}" }, "%", 0f, 100f, null),
            Spark("ROAD SPEED", Channel.SPEED, s[Channel.SPEED]?.let { (if (cfg.imperial) it * KPH_TO_MPH else it).roundToInt().toString() },
                if (cfg.imperial) "MPH" else "KM/H", 0f, 220f, null),
            Spark("COOLANT", Channel.COOLANT, s[Channel.COOLANT]?.let { toF(it).roundToInt().toString() }, deg, 40f, 120f, toF),
            Spark("OIL TEMP", Channel.OIL_TEMP, s[Channel.OIL_TEMP]?.let { toF(it).roundToInt().toString() }, deg, 40f, 140f, toF),
            Spark("AIR TEMP", Channel.INTAKE_TEMP, s[Channel.INTAKE_TEMP]?.let { toF(it).roundToInt().toString() }, deg, -10f, 60f, toF),
            Spark("BATTERY", Channel.BATTERY, s[Channel.BATTERY]?.let { "%.1f".format(it) }, "V", 11f, 15f, null),
            Spark("FUEL LEVEL", Channel.FUEL, s[Channel.FUEL]?.let { "${it.roundToInt()}" }, "%", 0f, 100f, null),
        )
        val cols = if (grid.width() > grid.height() * 1.7f) 4 else 2
        val rows = (cells.size + cols - 1) / cols
        val gap = 6f * u
        val cw = (grid.width() - gap * (cols - 1)) / cols
        val ch = (grid.height() - gap * (rows - 1)) / rows
        for ((i, cell) in cells.withIndex()) {
            val x = grid.left + (i % cols) * (cw + gap)
            val y = grid.top + (i / cols) * (ch + gap)
            sparkCell(c, RectF(x, y, x + cw, y + ch), cell)
        }
    }

    private class Spark(val name: String, val ch: Channel, val value: String?, val unit: String, val lo: Float, val hi: Float, val conv: ((Float) -> Float)?)

    private fun sparkCell(c: Canvas, r: RectF, sp: Spark) {
        panel(c, r)
        label(c, sp.name, r.left + 6f * u, r.top + 11f * u, 7f * u, t.dim)
        val size = min(r.height() * 0.3f, r.width() * 0.2f)
        display(c, sp.value ?: "—", r.left + 6f * u, r.top + 14f * u + size, size, if (sp.value == null) t.faint else t.text, glow = sp.value != null)
        if (sp.value != null) label(c, sp.unit, r.left + 9f * u + textWidth(sp.value, Fonts.display, size, 0.04f), r.top + 14f * u + size, 7f * u, t.dim)
        val plot = RectF(r.left + 6f * u, r.top + 20f * u + size, r.right - 6f * u, r.bottom - 10f * u)
        if (plot.height() > 8f * u) {
            var data = History.series(sp.ch, History.SIZE)
            sp.conv?.let { f -> data = FloatArray(data.size) { if (data[it].isNaN()) Float.NaN else f(data[it]) } }
            val lo = sp.conv?.invoke(sp.lo) ?: sp.lo
            val hi = sp.conv?.invoke(sp.hi) ?: sp.hi
            traceLine(c, plot, data, lo, hi, t.secondary, 1.3f * u)
            label(c, "LIVE", plot.left, r.bottom - 3f * u, 6f * u, t.faint)
        }
    }

    // =========================================================================================
    // HANDLING: the g radar and the session
    // =========================================================================================

    private fun handlingPage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
        frame(c, r)
        val inner = RectF(r.left + 10f * u, r.top + 8f * u, r.right - 10f * u, r.bottom - 8f * u)
        display(c, "HANDLING · G-FORCE", inner.left, inner.top + 13f * u, 13f * u, t.text, glow = true)
        val body = RectF(inner.left, inner.top + 22f * u, inner.right, inner.bottom)
        val gs = min(body.height(), body.width() * 0.48f)
        val radar = RectF(body.left, body.centerY() - gs / 2, body.left + gs, body.centerY() + gs / 2)
        gRadar(c, radar)

        val launch = if (cfg.imperial) "0-60" else "0-100"
        val sp = { kph: Float -> (if (cfg.imperial) kph * KPH_TO_MPH else kph).roundToInt().toString() }
        val stats = listOf(
            Triple("LATERAL", "%.2f".format(abs(latShown)), "G"),
            Triple("PEAK LAT", "%.2f".format(SessionStats.peakLatG), "G"),
            Triple("PEAK BRAKE", "%.2f".format(SessionStats.peakBrakeG), "G"),
            Triple("PEAK ACCEL", "%.2f".format(SessionStats.peakAccelG), "G"),
            Triple("BEST $launch", SessionStats.bestLaunchMs?.let { "%.2f".format(it / 1000f) } ?: "—", "S"),
            Triple("LAST $launch", SessionStats.lastLaunchMs?.let { "%.2f".format(it / 1000f) } ?: "—", "S"),
            Triple("TOP SPEED", SessionStats.topKph.takeIf { it > 0 }?.let(sp) ?: "—", if (cfg.imperial) "MPH" else "KM/H"),
            Triple("DISTANCE", "%.1f".format(if (cfg.imperial) SessionStats.distanceKm * 0.621371 else SessionStats.distanceKm), if (cfg.imperial) "MI" else "KM"),
        )
        val grid = RectF(radar.right + 12f * u, body.top, body.right, body.bottom)
        val gap = 5f * u
        val ch = (grid.height() - gap * 3) / 4
        val cw = (grid.width() - gap) / 2
        for ((i, st) in stats.withIndex()) {
            val x = grid.left + (i % 2) * (cw + gap)
            val y = grid.top + (i / 2) * (ch + gap)
            val b = RectF(x, y, x + cw, y + ch)
            panel(c, b)
            label(c, st.first, b.left + 6f * u, b.top + 11f * u, 7f * u, t.dim)
            val size = min(b.height() * 0.42f, b.width() * 0.2f)
            display(c, st.second, b.right - 22f * u, b.bottom - 7f * u, size, if (st.second == "—") t.faint else t.text, Paint.Align.RIGHT, glow = true)
            label(c, st.third, b.right - 6f * u, b.bottom - 7f * u, 6.5f * u, t.dim, Paint.Align.RIGHT)
        }
        newHits += RectF(radar) to { resetSession() }
        label(c, "TAP TO RESET", radar.left, radar.top + 4f * u, 6.5f * u, t.faint)
    }

    private fun gRadar(c: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = min(r.width(), r.height()) / 2f - 4f * u
        val range = 1.25f
        stroke.strokeWidth = max(1f, 0.8f * u)
        for (g in floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f)) {
            stroke.color = if (g == 1.0f) withAlpha(t.primary, 140) else t.line
            c.drawCircle(cx, cy, rad * g / range, stroke)
        }
        stroke.color = t.line
        for (a in 0 until 12) {
            val ang = a * PI / 6
            c.drawLine(cx, cy, cx + rad * cos(ang).toFloat(), cy + rad * sin(ang).toFloat(), stroke)
        }
        label(c, "1.0G", cx + 3f * u, cy - rad / range - 3f * u, 7f * u, t.dim)
        label(c, "BRAKE", cx, cy - rad - 1f * u, 6.5f * u, t.faint, Paint.Align.CENTER)
        label(c, "ACCEL", cx, cy + rad + 7f * u, 6.5f * u, t.faint, Paint.Align.CENTER)
        val peak = SessionStats.peakG
        if (peak > 0.05f) {
            stroke.color = t.secondary
            stroke.strokeWidth = 1.2f * u
            c.drawCircle(cx, cy, rad * min(peak, range) / range, stroke)
        }
        for (k in 0 until trailCount) {
            val idx = ((trailHead - 1 - k) % TRAIL + TRAIL) % TRAIL
            val (x, y) = gPoint(cx, cy, rad, range, trail[idx * 2], trail[idx * 2 + 1])
            fill.color = withAlpha(t.primary, (150 * (1f - k.toFloat() / trailCount)).toInt())
            c.drawCircle(x, y, 2.2f * u, fill)
        }
        val (x, y) = gPoint(cx, cy, rad, range, latShown, lonShown)
        fill.color = withAlpha(t.primary, 70)
        c.drawCircle(x, y, 11f * u, fill)
        fill.color = t.text
        c.drawCircle(x, y, 5f * u, fill)
    }

    /** Felt-force convention: braking (negative lon) moves the dot up, a right turn moves it left. */
    private fun gPoint(cx: Float, cy: Float, rad: Float, range: Float, lat: Float, lon: Float): Pair<Float, Float> {
        var dx = -lat / range
        var dy = lon / range
        val len = sqrt(dx * dx + dy * dy)
        if (len > 0.97f) { dx *= 0.97f / len; dy *= 0.97f / len }
        return Pair(cx + dx * rad, cy + dy * rad)
    }

    // =========================================================================================
    // BUS: the raw CAN traffic
    // =========================================================================================

    private fun busPage(c: Canvas, r: RectF, s: Snapshot) {
        frame(c, r)
        val inner = RectF(r.left + 10f * u, r.top + 8f * u, r.right - 10f * u, r.bottom - 8f * u)
        display(c, "BUS · LIVE CAN", inner.left, inner.top + 13f * u, 13f * u, t.text, glow = true)
        val rows = VehicleData.sniffer.values.sortedBy { it.id }
        label(c, "${rows.size} IDS · ${s.framesPerSecond} MSG/S", inner.right, inner.top + 10f * u, 7f * u, t.dim, Paint.Align.RIGHT)
        val body = RectF(inner.left, inner.top + 24f * u, inner.right, inner.bottom)
        if (rows.isEmpty()) {
            label(c, "NO RAW FRAMES", body.centerX(), body.centerY() - 6f * u, 10f * u, t.text, Paint.Align.CENTER)
            label(c, "USE A CAN ADAPTER ON THE DCM / ASC CONNECTOR, OR SET READ MODE TO RAW CAN", body.centerX(), body.centerY() + 8f * u, 6.5f * u, t.dim, Paint.Align.CENTER)
            return
        }
        val size = 7.5f * u
        val lineH = size * 1.55f
        val colW = textWidth("000  000.0  00 00 00 00 00 00 00 00", Fonts.mono, size, 0f) + 14f * u
        val cols = max(1, (body.width() / colW).toInt())
        val perCol = max(1, (body.height() / lineH).toInt())
        val now = s.now
        text.typeface = Fonts.mono
        text.textSize = size
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.LEFT
        for ((i, e) in rows.take(cols * perCol).withIndex()) {
            val x = body.left + (i / perCol) * colW
            val y = body.top + (i % perCol) * lineH + size
            synchronized(e) {
                val stale = now - e.lastSeen > 2000
                text.color = if (stale) t.faint else t.text
                val hz = if (e.periodMs > 0) 1000f / e.periodMs else 0f
                val head = "%03X  %5.1f  ".format(e.id, hz)
                c.drawText(head, x, y, text)
                var bx = x + text.measureText(head)
                for (b in e.data.indices) {
                    val age = now - e.changedAt[b]
                    text.color = when { stale -> t.faint; age < 300 -> t.primary; age < 1500 -> t.secondary; else -> t.dim }
                    val hx = "%02X ".format(e.data[b])
                    c.drawText(hx, bx, y, text)
                    bx += text.measureText(hx)
                }
            }
        }
    }

    // =========================================================================================
    // STYLE: themes with a live preview
    // =========================================================================================

    private fun stylePage(c: Canvas, r: RectF, cfg: DashConfig, dt: Float) {
        frame(c, r)
        val inner = RectF(r.left + 10f * u, r.top + 8f * u, r.right - 10f * u, r.bottom - 8f * u)
        display(c, "STYLE · CUSTOMIZE", inner.left, inner.top + 13f * u, 13f * u, t.text, glow = true)
        val body = RectF(inner.left, inner.top + 22f * u, inner.right, inner.bottom)
        val listW = body.width() * 0.42f
        val list = RectF(body.left, body.top, body.left + listW, body.bottom)
        label(c, "PRESETS", list.left, list.top + 8f * u, 7f * u, t.dim)
        val cols = 2
        val gap = 4f * u
        val n = Theme.ALL.size
        val rowsN = (n + cols - 1) / cols
        val bh = min(22f * u, (list.height() - 14f * u - gap * (rowsN - 1)) / rowsN)
        val bw = (list.width() - gap) / cols
        for ((i, th) in Theme.ALL.withIndex()) {
            val x = list.left + (i % cols) * (bw + gap)
            val y = list.top + 14f * u + (i / cols) * (bh + gap)
            val b = RectF(x, y, x + bw, y + bh)
            val active = th.key == t.key
            fill.color = t.panel
            c.drawRect(b, fill)
            stroke.color = if (active) t.text else t.line
            stroke.strokeWidth = (if (active) 1.4f else 0.8f) * u
            c.drawRect(b, stroke)
            val sw = bh * 0.45f
            fill.color = th.primary
            c.drawRect(b.left + 5f * u, b.centerY() - sw / 2, b.left + 5f * u + sw, b.centerY() + sw / 2, fill)
            fill.color = th.secondary
            c.drawRect(b.left + 6f * u + sw, b.centerY() - sw / 2, b.left + 6f * u + sw * 1.5f, b.centerY() + sw / 2, fill)
            label(c, th.name.uppercase(), b.left + 12f * u + sw * 1.5f, b.centerY() + 3f * u, 7.5f * u, if (active) t.text else t.dim)
            newHits += b to { onThemePicked?.invoke(th.key) }
        }
        val preview = RectF(list.right + 12f * u, body.top, body.right, body.bottom)
        panel(c, preview)
        label(c, "LIVE PREVIEW", preview.right - 6f * u, preview.top + 11f * u, 7f * u, t.dim, Paint.Align.RIGHT)
        roadOffset = (roadOffset + 2f * dt) % GRID
        drawCar(c, preview, VehicleData.snapshot(), cfg, live = true, callouts = false)
    }

    // =========================================================================================
    // Drawing helpers
    // =========================================================================================

    /** A panel: dark fill, hairline edge, a small bracket on the top-right corner. */
    private fun panel(c: Canvas, r: RectF, edge: Int = t.line, weight: Float = 0.8f) {
        fill.color = t.panel
        c.drawRect(r, fill)
        stroke.color = edge
        stroke.strokeWidth = max(1f, weight * u)
        c.drawRect(r, stroke)
        stroke.color = withAlpha(t.dim, 140)
        val k = min(6f * u, r.height() * 0.2f)
        c.drawLine(r.right - k, r.top, r.right, r.top, stroke)
        c.drawLine(r.right, r.top, r.right, r.top + k, stroke)
    }

    /** The big page frame: corner brackets only, like a viewfinder. */
    private fun frame(c: Canvas, r: RectF) {
        stroke.color = t.line
        stroke.strokeWidth = max(1f, 0.8f * u)
        c.drawRect(r, stroke)
        stroke.color = t.dim
        stroke.strokeWidth = 1.4f * u
        val k = 10f * u
        for ((x, y, sx, sy) in listOf(
            floatArrayOf(r.left, r.top, 1f, 1f), floatArrayOf(r.right, r.top, -1f, 1f),
            floatArrayOf(r.left, r.bottom, 1f, -1f), floatArrayOf(r.right, r.bottom, -1f, -1f),
        )) {
            c.drawLine(x, y, x + k * sx, y, stroke)
            c.drawLine(x, y, x, y + k * sy, stroke)
        }
    }

    private fun label(
        c: Canvas, s: String, x: Float, y: Float, size: Float, colour: Int,
        align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0.16f,
    ) {
        text.typeface = Fonts.mono
        text.textSize = size
        text.letterSpacing = spacing
        text.color = colour
        text.textAlign = align
        c.drawText(s, x, y, text)
    }

    private fun display(
        c: Canvas, s: String, x: Float, y: Float, size: Float, colour: Int,
        align: Paint.Align = Paint.Align.LEFT, glow: Boolean = false,
    ) {
        text.typeface = Fonts.display
        text.textSize = size
        text.letterSpacing = 0.04f
        text.color = colour
        text.textAlign = align
        if (glow) text.setShadowLayer(size * 0.22f, 0f, 0f, withAlpha(colour, 150))
        c.drawText(s, x, y, text)
        text.clearShadowLayer()
    }

    /** The huge glowing figures in the speed and gear boxes, with the zero slashed. */
    private fun bigFigure(c: Canvas, s: String, cx: Float, baseline: Float, size: Float, colour: Int) {
        display(c, s, cx, baseline, size, colour, Paint.Align.CENTER, glow = s != "—")
        if ('0' !in s) return
        text.typeface = Fonts.display
        text.textSize = size
        text.letterSpacing = 0.04f
        val total = text.measureText(s)
        val bounds = android.graphics.Rect()
        text.getTextBounds("0", 0, 1, bounds)
        stroke.color = colour
        stroke.strokeWidth = size * 0.085f
        stroke.strokeCap = Paint.Cap.SQUARE
        for (i in s.indices) if (s[i] == '0') {
            val x0 = cx - total / 2 + text.measureText(s, 0, i)
            val w0 = text.measureText("0")
            c.drawLine(x0 + w0 * 0.3f, baseline + bounds.bottom - size * 0.12f, x0 + w0 * 0.7f, baseline + bounds.top + size * 0.12f, stroke)
        }
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun textWidth(s: String, tf: Typeface, size: Float, spacing: Float): Float {
        text.typeface = tf
        text.textSize = size
        text.letterSpacing = spacing
        return text.measureText(s)
    }

    private fun temps(cfg: DashConfig): (Float) -> String = { v ->
        if (cfg.imperial) "${(v * 9f / 5f + 32f).roundToInt()}°F" else "${v.roundToInt()}°C"
    }

    private fun tempColour(v: Float?, cold: Float, warn: Float, crit: Float): Int = when {
        v == null -> t.faint
        v >= crit -> t.primary
        v >= warn -> t.secondary
        v < cold -> t.dim
        else -> t.text
    }

    private fun gearNumber(s: Snapshot, cfg: DashConfig): Int? {
        s[Channel.GEAR]?.let { return it.roundToInt() }
        val rpm = s[Channel.RPM] ?: return null
        val speed = s[Channel.SPEED] ?: return null
        return GearEstimator.estimate(rpm, speed, cfg)
    }

    private fun gearLabel(g: Int?) = when (g) { null -> "–"; 0 -> "N"; -1 -> "R"; else -> g.toString() }

    private fun withAlpha(colour: Int, alpha: Int) = (colour and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun norm(v: FloatArray): FloatArray {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f)
        return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )

    companion object {
        const val KPH_TO_MPH = 0.621371f
        private const val TRAIL = 48
        private const val GRID = 0.9f
        private const val NEAR = 0.3f
    }
}
