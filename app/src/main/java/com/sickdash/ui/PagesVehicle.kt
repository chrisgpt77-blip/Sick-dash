package com.sickdash.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.sickdash.data.Analytics
import com.sickdash.data.Channel
import com.sickdash.data.ChannelInfo
import com.sickdash.data.DashConfig
import com.sickdash.data.History
import com.sickdash.data.LinkState
import com.sickdash.data.Recorder
import com.sickdash.data.Snapshot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// =============================================================================================
// Shared page pieces
// =============================================================================================

/** Frames a page with its title; returns the area below the title. */
internal fun DashRenderer.pageFrame(c: Canvas, r: RectF, title: String, right: String? = null): RectF {
    frame(c, r)
    val inner = RectF(r.left + 10f * u, r.top + 8f * u, r.right - 10f * u, r.bottom - 8f * u)
    display(c, title, inner.left, inner.top + 13f * u, 13f * u, t.text, glow = true)
    right?.let { label(c, it, inner.right, inner.top + 10f * u, 7f * u, t.dim, Paint.Align.RIGHT) }
    return RectF(inner.left, inner.top + 22f * u, inner.right, inner.bottom)
}

/** A labelled figure in a panel. */
internal fun DashRenderer.statCell(c: Canvas, b: RectF, name: String, value: String?, unit: String = "", colour: Int = t.text, edge: Int = t.line) {
    panel(c, b, edge)
    val ls = min(7f * u, b.height() * 0.22f)
    label(c, name, b.left + 6f * u, b.top + ls + 4f * u, ls, t.dim)
    val size = min(b.height() * 0.42f, b.width() * 0.2f)
    val unitW = if (unit.isEmpty()) 0f else textWidth(unit, Fonts.mono, 6.5f * u, 0.16f) + 4f * u
    display(c, value ?: "—", b.right - 6f * u - unitW, b.bottom - 6f * u, size, if (value == null) t.faint else colour, Paint.Align.RIGHT, glow = value != null)
    if (unit.isNotEmpty()) label(c, unit, b.right - 6f * u, b.bottom - 6f * u, 6.5f * u, t.dim, Paint.Align.RIGHT)
}

/** Lays out [n] cells in a grid inside [r]. */
internal fun DashRenderer.cells(r: RectF, n: Int, cols: Int, gap: Float = 5f * u): List<RectF> {
    val rows = (n + cols - 1) / cols
    val cw = (r.width() - gap * (cols - 1)) / cols
    val ch = (r.height() - gap * (rows - 1)) / rows
    return (0 until n).map { i ->
        val x = r.left + (i % cols) * (cw + gap)
        val y = r.top + (i / cols) * (ch + gap)
        RectF(x, y, x + cw, y + ch)
    }
}

/** Plots a ring buffer (oldest first, starting at [head]) between [lo] and [hi]. */
internal fun DashRenderer.ringPlot(c: Canvas, plot: RectF, data: FloatArray, head: Int, lo: Float, hi: Float, colour: Int) {
    val ordered = FloatArray(data.size) { data[(head + it) % data.size] }
    traceLine(c, plot, ordered, lo, hi, colour, 1.4f * u)
}

/** Zero line and limits for a centred plot. */
internal fun DashRenderer.centreAxis(c: Canvas, plot: RectF, lo: Float, hi: Float, topLabel: String, bottomLabel: String) {
    stroke.color = t.line; stroke.strokeWidth = max(1f, 0.8f * u)
    val zy = plot.bottom - plot.height() * (0f - lo) / (hi - lo)
    c.drawLine(plot.left, zy, plot.right, zy, stroke)
    label(c, topLabel, plot.left + 2f * u, plot.top + 8f * u, 6.5f * u, t.faint)
    label(c, bottomLabel, plot.left + 2f * u, plot.bottom - 3f * u, 6.5f * u, t.faint)
}

// =============================================================================================
// BODY: the car's state
// =============================================================================================

internal fun DashRenderer.bodyPage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig, dt: Float) {
    val body = pageFrame(c, r, "BODY · VEHICLE STATE", "DOORS · LOCKS · LIGHTS · TYRES")
    val stage = RectF(body.left, body.top, body.left + body.width() * 0.56f, body.bottom)
    val speedMs = (s[Channel.SPEED] ?: 0f) / 3.6f
    wheelSpin -= speedMs / CarWireframe.WHEEL_R * dt
    roadOffset = (roadOffset + speedMs * dt) % DashRenderer.GRID
    drawCar(c, stage, s, cfg, live = s[Channel.RPM] != null || s.link.state == LinkState.CONNECTED, callouts = true)

    val list = RectF(stage.right + 8f * u, body.top, body.right, body.bottom)
    val items = listOf(Channel.DOORS_OPEN, Channel.DOOR_LOCK, Channel.BELT, Channel.HEADLIGHTS, Channel.PARK_BRAKE,
        Channel.REVERSE, Channel.BRAKE_LIGHT, Channel.FUEL, Channel.ODOMETER)
    val tyreH = list.height() * 0.28f
    val rowH = (list.height() - tyreH - 6f * u) / items.size
    for ((i, ch) in items.withIndex()) {
        val b = RectF(list.left, list.top + i * rowH, list.right, list.top + (i + 1) * rowH - 2f * u)
        val v = s[ch, if (ch == Channel.ODOMETER) Long.MAX_VALUE else 10_000]
        val warn = v != null && ChannelInfo.needsCheck(ch, v, s)
        val ts = min(7.5f * u, b.height() * 0.42f)
        label(c, ChannelInfo.of(ch)?.name ?: ch.key.uppercase(), b.left + 4f * u, b.centerY() + ts * 0.36f, ts, t.dim)
        display(c, v?.let { ChannelInfo.format(ch, it, cfg.imperial) } ?: "NOT MAPPED", b.right - 4f * u, b.centerY() + ts * 0.4f,
            if (v == null) ts else min(b.height() * 0.55f, 11f * u), if (v == null) t.faint else if (warn) t.secondary else t.text, Paint.Align.RIGHT, glow = v != null)
        stroke.color = t.line; stroke.strokeWidth = max(1f, 0.6f * u)
        c.drawLine(b.left, b.bottom + 1f * u, b.right, b.bottom + 1f * u, stroke)
    }
    val tyres = RectF(list.left, list.bottom - tyreH, list.right, list.bottom)
    for ((i, b) in cells(tyres, 4, 2, 4f * u).withIndex()) {
        val ch = listOf(Channel.TIRE_FL, Channel.TIRE_FR, Channel.TIRE_RL, Channel.TIRE_RR)[i]
        val v = s[ch, 15_000]
        statCell(c, b, listOf("TYRE FL", "TYRE FR", "TYRE RL", "TYRE RR")[i],
            v?.let { if (cfg.imperial) (it / 6.8948f).roundToInt().toString() else "%.1f".format(it / 100f) },
            if (cfg.imperial) "PSI" else "BAR", if (v != null && v < 193f) t.secondary else t.text)
    }
}

// =============================================================================================
// DIFF: wheel speeds and the limited-slip diff at work
// =============================================================================================

internal fun DashRenderer.diffPage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
    val body = pageFrame(c, r, "DIFF · WHEEL SPEEDS", "TORSEN LSD · REAR")
    val fl = s[Channel.WHEEL_FL]; val fr = s[Channel.WHEEL_FR]; val rl = s[Channel.WHEEL_RL]; val rr = s[Channel.WHEEL_RR]
    fun spd(k: Float?) = k?.let { (if (cfg.imperial) it * DashRenderer.KPH_TO_MPH else it) }
    val unit = if (cfg.imperial) "MPH" else "KM/H"
    // what the yaw rate says the outside rear wheel should be doing over the inside one
    val yaw = Analytics.yawActual
    val expected = yaw?.let { abs(it) * PI.toFloat() / 180f * 1.55f * 3.6f }

    val left = RectF(body.left, body.top, body.left + body.width() * 0.45f, body.bottom)
    // top view of the car with a wheel at each corner
    val cx = left.centerX(); val cy = left.centerY()
    val carH = min(left.height() * 0.85f, left.width() * 1.3f)
    val carW = carH * 0.42f
    rect.set(cx - carW / 2, cy - carH / 2, cx + carW / 2, cy + carH / 2)
    stroke.color = t.car; stroke.strokeWidth = 1.3f * u
    c.drawRoundRect(rect, carW * 0.3f, carW * 0.3f, stroke)
    c.drawLine(cx - carW / 2, cy + carH * 0.3f, cx + carW / 2, cy + carH * 0.3f, stroke)   // rear axle
    c.drawCircle(cx, cy + carH * 0.3f, carW * 0.1f, stroke)                              // the diff
    val corners = listOf(
        Triple(fl, cx - carW / 2, cy - carH * 0.3f), Triple(fr, cx + carW / 2, cy - carH * 0.3f),
        Triple(rl, cx - carW / 2, cy + carH * 0.3f), Triple(rr, cx + carW / 2, cy + carH * 0.3f),
    )
    for ((v, x, y) in corners) {
        fill.color = if (v != null) t.text else t.line
        c.drawRect(x - carW * 0.1f, y - carH * 0.08f, x + carW * 0.1f, y + carH * 0.08f, fill)
        val leftSide = x < cx
        display(c, spd(v)?.let { "%.1f".format(it) } ?: "—", if (leftSide) x - carW * 0.18f else x + carW * 0.18f, y + 5f * u,
            12f * u, if (v != null) t.text else t.faint, if (leftSide) Paint.Align.RIGHT else Paint.Align.LEFT, glow = v != null)
    }
    label(c, unit, cx, left.bottom - 2f * u, 7f * u, t.dim, Paint.Align.CENTER)

    val right = RectF(left.right + 10f * u, body.top, body.right, body.bottom)
    if (rl == null || rr == null) {
        panel(c, right)
        display(c, "WHEEL SPEEDS NOT ON THIS BUS", right.left + 10f * u, right.top + 24f * u, 12f * u, t.secondary, glow = true)
        val lines = listOf(
            "The GR86 sends per-wheel speeds on id 0x13A,",
            "which the DCM connector carries but the ASC",
            "(body CAN) connector does not.",
            "",
            "Move the CAN adapter to the DCM connector",
            "behind the glovebox to light this page up.",
        )
        for ((i, l) in lines.withIndex()) label(c, l.uppercase(), right.left + 10f * u, right.top + 44f * u + i * 12f * u, 7f * u, t.dim, spacing = 0.06f)
        statCell(c, RectF(right.left + 10f * u, right.bottom - 50f * u, right.right - 10f * u, right.bottom - 10f * u),
            "EXPECTED REAR SPLIT FROM YAW", spd(expected)?.let { "%.1f".format(it) }, unit)
        return
    }
    val rearSplit = rl - rr
    val frontAvg = ((fl ?: rl) + (fr ?: rr)) / 2f
    val rearAvg = (rl + rr) / 2f
    val slip = if (frontAvg > 3f) (rearAvg - frontAvg) / frontAvg * 100f else 0f
    val locking = expected != null && abs(rearSplit) < (expected * 0.5f) && (expected > 2f)
    val boxes = cells(RectF(right.left, right.top, right.right, right.top + right.height() * 0.5f), 4, 2)
    statCell(c, boxes[0], "REAR SPLIT L-R", spd(abs(rearSplit))?.let { "%.1f".format(it) }, unit)
    statCell(c, boxes[1], "EXPECTED FROM YAW", spd(expected)?.let { "%.1f".format(it) }, unit)
    statCell(c, boxes[2], "REAR VS FRONT SLIP", "%+.1f".format(slip), "%", if (abs(slip) > 8f) t.secondary else t.text)
    statCell(c, boxes[3], "LSD", if (locking) "BIASING" else "OPEN", "", if (locking) t.primary else t.text)
    val g = RectF(right.left, right.top + right.height() * 0.5f + 6f * u, right.right, right.bottom)
    panel(c, g)
    label(c, "REAR SPLIT · LAST 10 S", g.left + 6f * u, g.top + 10f * u, 7f * u, t.dim)
    val plot = RectF(g.left + 6f * u, g.top + 16f * u, g.right - 6f * u, g.bottom - 6f * u)
    val a = History.series(Channel.WHEEL_RL)
    val b = History.series(Channel.WHEEL_RR)
    val d = FloatArray(a.size) { if (a[it].isNaN() || b[it].isNaN()) Float.NaN else a[it] - b[it] }
    centreAxis(c, plot, -10f, 10f, "LEFT FASTER", "RIGHT FASTER")
    traceLine(c, plot, d, -10f, 10f, t.primary, 1.4f * u)
}

// =============================================================================================
// VDC: balance — does the car rotate as much as the steering asks?
// =============================================================================================

internal fun DashRenderer.vdcPage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
    val body = pageFrame(c, r, "VDC · BALANCE", "YAW ${if (s[Channel.YAW_RATE] != null) "· CAN" else "· FROM LATERAL G"}")
    val bal = Analytics.balance
    val has = Analytics.yawExpected != null && Analytics.yawActual != null
    // a semicircular meter: understeer on the left, oversteer on the right
    val meter = RectF(body.left, body.top, body.left + body.width() * 0.5f, body.top + body.height() * 0.62f)
    val mcx = meter.centerX(); val mcy = meter.bottom - 8f * u
    val mr = min(meter.width() * 0.45f, meter.height() * 0.85f)
    rect.set(mcx - mr, mcy - mr, mcx + mr, mcy + mr)
    stroke.strokeWidth = 8f * u
    stroke.color = withAlpha(t.secondary, 120); c.drawArc(rect, 180f, 70f, false, stroke)
    stroke.color = withAlpha(t.text, 70); c.drawArc(rect, 250f, 40f, false, stroke)
    stroke.color = withAlpha(t.primary, 140); c.drawArc(rect, 290f, 70f, false, stroke)
    val ang = Math.toRadians((270.0 + (bal.coerceIn(-20f, 20f) / 20f) * 90.0))
    stroke.color = if (has) t.text else t.faint
    stroke.strokeWidth = 2.5f * u
    stroke.strokeCap = Paint.Cap.ROUND
    c.drawLine(mcx, mcy, mcx + (mr * 0.95f * cos(ang)).toFloat(), mcy + (mr * 0.95f * sin(ang)).toFloat(), stroke)
    stroke.strokeCap = Paint.Cap.BUTT
    fill.color = t.text; c.drawCircle(mcx, mcy, 4f * u, fill)
    label(c, "UNDERSTEER", meter.left + 4f * u, mcy, 7f * u, t.secondary)
    label(c, "OVERSTEER", meter.right - 4f * u, mcy, 7f * u, t.primary, Paint.Align.RIGHT)
    display(c, if (has) balanceWord(bal) else "DRIVE TO MEASURE", mcx, mcy - mr * 0.35f, 15f * u,
        if (!has) t.faint else if (bal > 4f) t.primary else if (bal < -4f) t.secondary else t.text, Paint.Align.CENTER, glow = has)

    val stats = cells(RectF(body.left + body.width() * 0.5f + 8f * u, body.top, body.right, body.top + body.height() * 0.62f), 6, 2)
    fun one(x: Float) = "%.1f".format(if (abs(x) < 0.05f) 0f else x)
    statCell(c, stats[0], "YAW ACTUAL", Analytics.yawActual?.let(::one), "°/S")
    statCell(c, stats[1], "YAW ASKED", Analytics.yawExpected?.let(::one), "°/S")
    statCell(c, stats[2], "STEERING", s[Channel.STEERING]?.let { "%.0f".format(it) }, "°")
    statCell(c, stats[3], "LATERAL", s[Channel.LAT_G, 1000]?.let { "%.2f".format(abs(it)) }, "G")
    statCell(c, stats[4], "OVERSTEER TIME", "%.1f".format(Analytics.oversteerTimeS), "S")
    statCell(c, stats[5], "VDC", s[Channel.VDC_OFF]?.let { ChannelInfo.format(Channel.VDC_OFF, it, cfg.imperial) }
        ?: s[Channel.TRACK_MODE]?.let { if (it >= 0.5f) "TRACK" else "ON" }, "",
        if ((s[Channel.VDC_OFF] ?: 0f) >= 0.5f) t.secondary else t.text)

    val g = RectF(body.left, body.top + body.height() * 0.62f + 8f * u, body.right, body.bottom)
    panel(c, g)
    label(c, "BALANCE · LAST 10 S", g.left + 6f * u, g.top + 10f * u, 7f * u, t.dim)
    val plot = RectF(g.left + 6f * u, g.top + 14f * u, g.right - 6f * u, g.bottom - 4f * u)
    centreAxis(c, plot, -20f, 20f, "OVERSTEER", "UNDERSTEER")
    ringPlot(c, plot, Analytics.balanceHistory, Analytics.balanceHead, -20f, 20f, t.primary)
}

// =============================================================================================
// TIMERS: drag-strip numbers and drive recording
// =============================================================================================

internal fun DashRenderer.timersPage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
    val body = pageFrame(c, r, "TIMERS · PERFORMANCE", "FROM A STANDSTILL · AUTOMATIC")
    val top = RectF(body.left, body.top, body.right, body.top + body.height() * 0.24f)
    val run = Analytics.runState
    val live = run == "RUNNING"
    statCell(c, RectF(top.left, top.top, top.left + top.width() * 0.32f, top.bottom), "RUN",
        if (live) "%.2f".format(Analytics.runElapsed) else run, if (live) "S" else "", if (live) t.primary else t.text)
    statCell(c, RectF(top.left + top.width() * 0.34f, top.top, top.left + top.width() * 0.62f, top.bottom), "DISTANCE",
        if (cfg.imperial) "%.0f".format(Analytics.runDistanceM * 3.281f) else "%.0f".format(Analytics.runDistanceM), if (cfg.imperial) "FT" else "M")
    // record button
    val rb = RectF(top.left + top.width() * 0.64f, top.top, top.right, top.bottom)
    val rec = Recorder.recording
    panel(c, rb, if (rec) t.primary else t.line, if (rec) 1.6f else 0.8f)
    fill.color = if (rec && (s.now / 500) % 2 == 0L) t.primary else if (rec) withAlpha(t.primary, 120) else t.dim
    c.drawCircle(rb.left + 16f * u, rb.centerY(), 6f * u, fill)
    display(c, if (rec) "STOP REC" else "RECORD", rb.left + 30f * u, rb.centerY() + 2f * u, 13f * u, if (rec) t.primary else t.text, glow = rec)
    label(c, if (rec) "${Recorder.rows} ROWS · 20 HZ CSV" else "LOG ALL SIGNALS TO CSV", rb.left + 30f * u, rb.centerY() + 13f * u, 6.5f * u, t.dim)
    newHits += rb to { Recorder.toggle() }

    val grid = RectF(body.left, top.bottom + 8f * u, body.right, body.bottom)
    val runs = listOf(Analytics.zeroTo60, Analytics.zeroTo100k, Analytics.zeroTo100m, Analytics.eighth, Analytics.quarter)
    val boxes = cells(grid, 6, 3)
    for ((i, rn) in runs.withIndex()) {
        val b = boxes[i]
        panel(c, b)
        label(c, rn.label, b.left + 6f * u, b.top + 11f * u, 7f * u, t.dim)
        val size = min(b.height() * 0.32f, b.width() * 0.17f)
        val base = b.centerY() + size * 0.3f
        display(c, rn.best?.let { "%.2f".format(it) } ?: "—", b.left + 6f * u, base, size, if (rn.best != null) t.text else t.faint, glow = rn.best != null)
        label(c, "BEST · S", b.left + 6f * u, base + 14f * u, 6.5f * u, t.faint)
        label(c, "LAST " + (rn.last?.let { "%.2f S".format(it) } ?: "—"), b.right - 6f * u, b.bottom - 6f * u, 7f * u, t.dim, Paint.Align.RIGHT)
        if (rn === Analytics.quarter) Analytics.quarterTrapKph?.let {
            label(c, "TRAP ${(if (cfg.imperial) it * DashRenderer.KPH_TO_MPH else it).roundToInt()} ${if (cfg.imperial) "MPH" else "KM/H"}",
                b.left + 6f * u, b.bottom - 6f * u, 7f * u, t.dim)
        }
    }
    val bb = boxes[5]
    val unit = if (cfg.imperial) "FT" else "M"
    val conv = { m: Float -> if (cfg.imperial) m * 3.281f else m }
    panel(c, bb)
    label(c, "60-0 MPH BRAKING", bb.left + 6f * u, bb.top + 11f * u, 7f * u, t.dim)
    val size = min(bb.height() * 0.32f, bb.width() * 0.17f)
    val base = bb.centerY() + size * 0.3f
    display(c, Analytics.brake60Best?.let { "%.0f".format(conv(it)) } ?: "—", bb.left + 6f * u, base, size,
        if (Analytics.brake60Best != null) t.text else t.faint, glow = Analytics.brake60Best != null)
    label(c, "BEST · $unit", bb.left + 6f * u, base + 14f * u, 6.5f * u, t.faint)
    label(c, "LAST " + (Analytics.brake60m?.let { "%.0f $unit".format(conv(it)) } ?: "—"), bb.right - 6f * u, bb.bottom - 6f * u, 7f * u, t.dim, Paint.Align.RIGHT)
}
