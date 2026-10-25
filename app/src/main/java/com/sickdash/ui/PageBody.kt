package com.sickdash.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.sickdash.data.Analytics
import com.sickdash.data.Channel
import com.sickdash.data.ChannelInfo
import com.sickdash.data.DashConfig
import com.sickdash.data.Events
import com.sickdash.data.SessionStats
import com.sickdash.data.ShiftTracker
import com.sickdash.data.Snapshot
import com.sickdash.data.Source
import com.sickdash.data.VehicleData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// =============================================================================================
// BODY SYSTEMS: a tile per body state, then tyres and the car, this drive, chassis, and the bus
// =============================================================================================

/** One state tile: [level] 0 is normal, 1 active (lit in its group's colour), 2 needs attention. */
private class StateTile(val name: String, val group: Int, val value: String?, val level: Int, val source: Channel?)

private const val G_BODY = 0
private const val G_LIGHTS = 1
private const val G_SAFETY = 2
private const val G_DRIVETRAIN = 3

private val hms = SimpleDateFormat("HH:mm:ss", Locale.US)

internal fun DashRenderer.bodyPage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig, dt: Float) {
    frame(c, r)
    val inner = RectF(r.left + 10f * u, r.top + 8f * u, r.right - 10f * u, r.bottom - 8f * u)
    display(c, "BODY SYSTEMS", inner.left, inner.top + 13f * u, 13f * u, t.text, glow = true)
    val groupColour = intArrayOf(mix(t.dim, t.text, 0.4f), t.secondary, t.primary, t.text)
    // legend
    var lx = inner.right
    for ((i, name) in listOf("BODY", "LIGHTS", "SAFETY", "DRIVETRAIN").withIndex().reversed()) {
        val w = textWidth(name, Fonts.mono, 6.5f * u, 0.16f)
        label(c, name, lx, inner.top + 10f * u, 6.5f * u, t.dim, Paint.Align.RIGHT)
        fill.color = groupColour[i]
        c.drawRect(lx - w - 9f * u, inner.top + 5f * u, lx - w - 4f * u, inner.top + 10f * u, fill)
        lx -= w + 16f * u
    }

    val body = RectF(inner.left, inner.top + 22f * u, inner.right, inner.bottom)
    val tilesArea = RectF(body.left, body.top, body.right, body.top + body.height() * 0.42f)
    val tiles = stateTiles(s)
    for ((i, b) in cells(tilesArea, tiles.size, 5, 4f * u).withIndex()) stateTile(c, b, tiles[i], groupColour[tiles[i].group], s)

    val bottom = RectF(body.left, tilesArea.bottom + 6f * u, body.right, body.bottom)
    val weights = floatArrayOf(0.24f, 0.25f, 0.26f, 0.25f)
    val gap = 6f * u
    val usable = bottom.width() - gap * 3
    var x = bottom.left
    val cols = weights.map { w -> RectF(x, bottom.top, x + usable * w, bottom.bottom).also { x += usable * w + gap } }
    vehicleColumn(c, cols[0], s, cfg)
    driveColumn(c, cols[1], s, cfg)
    chassisColumn(c, cols[2], s, cfg)
    busColumn(c, cols[3], s, cfg)
}

private fun stateTiles(s: Snapshot): List<StateTile> {
    fun v(ch: Channel) = s[ch, 10_000]
    fun on(ch: Channel) = v(ch)?.let { it >= 0.5f }
    fun onOff(name: String, g: Int, ch: Channel, activeLevel: Int = 1) =
        StateTile(name, g, on(ch)?.let { if (it) "ON" else "OFF" }, if (on(ch) == true) activeLevel else 0, ch)
    fun door(name: String, ch: Channel, open: String = "OPEN", shut: String = "CLOSED") =
        StateTile(name, G_BODY, on(ch)?.let { if (it) open else shut }, if (on(ch) == true) 2 else 0, ch)

    val lights = v(Channel.HEADLIGHTS)?.roundToInt()
    val turn = v(Channel.TURN_SIGNAL)?.roundToInt()
    val blinkOn = (s.now / 400) % 2 == 0L
    val wipers = v(Channel.WIPERS)?.roundToInt()
    val moving = (s[Channel.SPEED] ?: 0f) > 3f
    val rpm = s[Channel.RPM, 3000]
    val brakeLight = on(Channel.BRAKE_LIGHT)
    val vdcOff = on(Channel.VDC_OFF)
    return listOf(
        door("DOOR · DRIVER", Channel.DOOR_DRIVER),
        door("DOOR · PASS", Channel.DOOR_PASS),
        door("TRUNK", Channel.HATCH, "OPEN", "SHUT"),
        StateTile("DOOR LOCKS", G_SAFETY, on(Channel.DOOR_LOCK)?.let { if (it) "LOCKED" else "UNLOCKED" },
            if (on(Channel.DOOR_LOCK) == false) 2 else 0, Channel.DOOR_LOCK),
        StateTile("HEADLIGHTS", G_LIGHTS, lights?.let { when (it) { 0 -> "OFF"; 1 -> "PARKING"; 2 -> "LOW BEAM"; else -> "HIGH BEAM" } },
            when { lights == null || lights == 0 -> 0; lights >= 3 -> 2; else -> 1 }, Channel.HEADLIGHTS),
        StateTile("TURN / HAZARD", G_LIGHTS, turn?.let { when (it) { 0 -> "OFF"; 1 -> "◀ LEFT"; 2 -> "RIGHT ▶"; else -> "HAZARD" } },
            when { turn == null || turn == 0 -> 0; !blinkOn -> 0; turn >= 3 -> 2; else -> 1 }, Channel.TURN_SIGNAL),
        StateTile("WIPERS", G_BODY, wipers?.let { when (it) { 0 -> "OFF"; 1 -> "INT"; 2 -> "LOW"; else -> "HIGH" } },
            if ((wipers ?: 0) > 0) 1 else 0, Channel.WIPERS),
        StateTile("SEATBELT", G_SAFETY, on(Channel.BELT)?.let { if (it) "ON" else "OFF" }, if (on(Channel.BELT) == false) 2 else 0, Channel.BELT),
        StateTile("PARK BRAKE", G_SAFETY, on(Channel.PARK_BRAKE)?.let { if (it) "SET" else "OFF" },
            if (on(Channel.PARK_BRAKE) == true) (if (moving) 2 else 1) else 0, Channel.PARK_BRAKE),
        StateTile("BRAKE LIGHTS", G_LIGHTS, brakeLight?.let { if (it) "ON" else "OFF" }, if (brakeLight == true) 1 else 0, Channel.BRAKE_LIGHT),
        onOff("REVERSE", G_DRIVETRAIN, Channel.REVERSE),
        StateTile("ENGINE", G_DRIVETRAIN, rpm?.let { if (it > 400f) "RUNNING" else "OFF" }, if ((rpm ?: 0f) > 400f) 1 else 0, Channel.RPM),
        StateTile("VDC", G_SAFETY, vdcOff?.let { if (it) "OFF" else "ON" }, if (vdcOff == true) 2 else 0, Channel.VDC_OFF),
        onOff("TRACK MODE", G_DRIVETRAIN, Channel.TRACK_MODE),
        onOff("CRUISE", G_DRIVETRAIN, Channel.CRUISE),
    )
}

private fun DashRenderer.stateTile(c: Canvas, b: RectF, st: StateTile, colour: Int, s: Snapshot) {
    val accent = when (st.level) { 2 -> t.primary; 1 -> colour; else -> t.line }
    fill.color = if (st.level > 0) mix(t.panel, accent, 0.22f) else t.panel
    c.drawRect(b, fill)
    stroke.color = accent
    stroke.strokeWidth = if (st.level > 0) 1.4f * u else max(1f, 0.8f * u)
    c.drawRect(b, stroke)
    // group mark
    fill.color = colour
    c.drawRect(b.right - 8f * u, b.top + 4f * u, b.right - 4f * u, b.top + 8f * u, fill)
    val ls = min(6.5f * u, b.height() * 0.2f)
    label(c, st.name, b.left + 5f * u, b.top + ls + 3f * u, ls, t.dim, spacing = 0.1f)
    val vs = min(b.height() * 0.36f, 12f * u)
    if (st.value == null) {
        label(c, "NOT MAPPED", b.left + 5f * u, b.bottom - 6f * u, ls, t.faint, spacing = 0.1f)
    } else {
        display(c, st.value, b.left + 5f * u, b.bottom - 5f * u, vs,
            when (st.level) { 2 -> t.text; 1 -> mix(colour, t.text, 0.35f); else -> t.text }, glow = st.level > 0)
        st.source?.let { ch ->
            val src = VehicleData.sourceOf(ch).removePrefix("CAN ")
            if (src != "—") label(c, src, b.right - 4f * u, b.bottom - 5f * u, 5.5f * u, t.faint, Paint.Align.RIGHT, 0.08f)
        }
    }
}

// ---- the four columns ------------------------------------------------------------------------

/** Panel with a heading; returns the area under the heading. */
private fun DashRenderer.column(c: Canvas, r: RectF, title: String, note: String? = null): RectF {
    panel(c, r)
    label(c, title, r.left + 6f * u, r.top + 11f * u, 7f * u, t.text, spacing = 0.14f)
    note?.let { label(c, ellipsize(it, r.width() * 0.4f, 5.5f * u), r.right - 6f * u, r.top + 11f * u, 5.5f * u, t.faint, Paint.Align.RIGHT, 0.08f) }
    stroke.color = t.line; stroke.strokeWidth = max(1f, 0.6f * u)
    c.drawLine(r.left + 6f * u, r.top + 15f * u, r.right - 6f * u, r.top + 15f * u, stroke)
    return RectF(r.left + 6f * u, r.top + 18f * u, r.right - 6f * u, r.bottom - 4f * u)
}

/** Rows of name … value, spread over [r]. */
private fun DashRenderer.rows(c: Canvas, r: RectF, items: List<Triple<String, String?, Int?>>, maxSize: Float = 7f * u) {
    if (items.isEmpty()) return
    val rowH = r.height() / items.size
    val size = min(maxSize, rowH * 0.62f)
    for ((i, it) in items.withIndex()) {
        val y = r.top + rowH * i + rowH * 0.5f + size * 0.36f
        label(c, it.first, r.left, y, size * 0.9f, t.dim, spacing = 0.1f)
        val v = it.second
        display(c, v ?: "—", r.right, y, size * 1.05f, if (v == null) t.faint else it.third ?: t.text, Paint.Align.RIGHT)
    }
}

private fun DashRenderer.vehicleColumn(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
    val imp = cfg.imperial
    val src = VehicleData.sourceOf(Channel.TIRE_FL).takeIf { it != "—" }?.removePrefix("CAN ")
    val area = column(c, r, if (imp) "TYRES · PSI" else "TYRES · BAR", src)
    // tyres, 2 × 2
    val tyreH = area.height() * 0.28f
    val names = listOf("FL", "FR", "RL", "RR")
    val chs = listOf(Channel.TIRE_FL, Channel.TIRE_FR, Channel.TIRE_RL, Channel.TIRE_RR)
    for ((i, b) in cells(RectF(area.left, area.top, area.right, area.top + tyreH), 4, 2, 4f * u).withIndex()) {
        val v = s[chs[i], 15_000]
        val ts = min(b.height() * 0.42f, 11f * u)
        label(c, names[i], b.left, b.centerY() + ts * 0.36f, ts * 0.7f, t.dim)
        display(c, v?.let { if (imp) "${(it / 6.8948f).roundToInt()}" else "%.1f".format(it / 100f) } ?: "—",
            b.right - 4f * u, b.centerY() + ts * 0.36f, ts, if (v == null) t.faint else if (v < 193f) t.secondary else t.text, Paint.Align.RIGHT, glow = v != null)
    }
    val vt = area.top + tyreH + 4f * u
    label(c, "VEHICLE", area.left, vt + 7f * u, 6.5f * u, t.primary, spacing = 0.14f)

    val fuel = s[Channel.FUEL]
    val litres = fuel?.let { it / 100f * cfg.car.tankL }
    val l100 = SessionStats.avgL100 ?: cfg.car.ratedL100
    val rangeKm = litres?.let { it / l100 * 100f }
    fun econ(x: Float?) = x?.let { if (imp) "%.1f".format(235.215f / it) else "%.1f".format(it) }
    val odo = s[Channel.ODOMETER, Long.MAX_VALUE]
    rows(c, RectF(area.left, vt + 10f * u, area.right, area.bottom), listOf(
        Triple("ODOMETER", odo?.let { ChannelInfo.format(Channel.ODOMETER, it, imp) } ?: "NOT MAPPED", if (odo == null) t.faint else null),
        Triple("RANGE · EST", rangeKm?.let { if (imp) "${(it * 0.621371f).roundToInt()} MI" else "${it.roundToInt()} KM" }, null),
        Triple("FUEL", fuel?.let { "${it.roundToInt()}% · " + if (imp) "%.1f GAL".format(litres!! / 3.785f) else "%.1f L".format(litres) },
            if ((fuel ?: 100f) < 15f) t.secondary else null),
        Triple(if (imp) "AVG MPG" else "AVG L/100", econ(SessionStats.avgL100), null),
        Triple(if (imp) "MPG NOW" else "L/100 NOW", econ(SessionStats.nowL100), null),
        Triple("BRAKE", s[Channel.BRAKE]?.let { "${it.roundToInt()}%" }, if ((s[Channel.BRAKE] ?: 0f) > 5f) t.primary else null),
    ))
}

private fun DashRenderer.driveColumn(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
    val imp = cfg.imperial
    val area = column(c, r, "THIS DRIVE", "SINCE CONNECTED")
    fun clock(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "%d:%02d H".format(m / 60, m % 60) else "%d:%02d".format(m, (ms / 1000) % 60)
    }
    fun spd(kph: Float) = if (imp) "${(kph * DashRenderer.KPH_TO_MPH).roundToInt()} MPH" else "${kph.roundToInt()} KM/H"
    val started = SessionStats.startedAt
    val km = SessionStats.distanceKm.toFloat()
    val movingH = SessionStats.movingMs / 3_600_000f
    rows(c, area, listOf(
        Triple("DRIVE TIME", if (started > 0) clock(s.now - started) else null, null),
        Triple("MOVING", if (started > 0) clock(SessionStats.movingMs) else null, null),
        Triple("DISTANCE", if (imp) "%.1f MI".format(km * 0.621371f) else "%.1f KM".format(km), null),
        Triple("AVG SPEED", if (movingH > 0.001f) spd(km / movingH) else null, null),
        Triple("TOP SPEED", if (SessionStats.topKph > 0f) spd(SessionStats.topKph) else null, null),
        Triple("MAX RPM", if (SessionStats.maxRpm > 0f) "%,d".format(SessionStats.maxRpm.roundToInt()) else null,
            if (SessionStats.maxRpm >= cfg.redline) t.primary else null),
        Triple("MAX LATERAL", "%.2f G".format(SessionStats.peakLatG), null),
        Triple("HARDEST STOP", "%.2f G".format(SessionStats.peakBrakeG), null),
        Triple("FUEL USED", if (imp) "%.2f GAL".format(SessionStats.fuelUsedL / 3.785f) else "%.2f L".format(SessionStats.fuelUsedL), null),
        Triple("DOWNSHIFTS", ShiftTracker.downshiftCount.toString(), null),
    ))
}

private fun DashRenderer.chassisColumn(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
    val area = column(c, r, "CHASSIS · WHEELS", VehicleData.sourceOf(Channel.WHEEL_FL).takeIf { it != "—" }?.removePrefix("CAN "))
    val imp = cfg.imperial
    // top view: four wheels, lit when their speed is known
    val top = RectF(area.left, area.top, area.right, area.top + area.height() * 0.34f)
    val cx = top.centerX(); val cy = top.centerY()
    val carH = top.height() * 0.92f
    val carW = carH * 0.46f
    rect.set(cx - carW / 2, cy - carH / 2, cx + carW / 2, cy + carH / 2)
    stroke.color = t.car; stroke.strokeWidth = 1.1f * u
    c.drawRoundRect(rect, carW * 0.3f, carW * 0.3f, stroke)
    val wheels = listOf(Channel.WHEEL_FL, Channel.WHEEL_FR, Channel.WHEEL_RL, Channel.WHEEL_RR)
    val steerDeg = (s[Channel.STEERING] ?: 0f) / 13.5f
    for ((i, ch) in wheels.withIndex()) {
        val left = i % 2 == 0
        val x = if (left) cx - carW / 2 else cx + carW / 2
        val y = cy + (if (i < 2) -1 else 1) * carH * 0.3f
        val v = s[ch]
        c.save()
        if (i < 2) c.rotate(-steerDeg, x, y)
        fill.color = if (v != null) t.text else t.line
        c.drawRect(x - carW * 0.11f, y - carH * 0.11f, x + carW * 0.11f, y + carH * 0.11f, fill)
        c.restore()
        val ts = min(7.5f * u, top.height() * 0.16f)
        display(c, v?.let { (if (imp) it * DashRenderer.KPH_TO_MPH else it).roundToInt().toString() } ?: "—",
            if (left) x - carW * 0.2f else x + carW * 0.2f, y + ts * 0.36f, ts, if (v == null) t.faint else t.text,
            if (left) Paint.Align.RIGHT else Paint.Align.LEFT)
    }

    val fl = s[Channel.WHEEL_FL]; val fr = s[Channel.WHEEL_FR]; val rl = s[Channel.WHEEL_RL]; val rr = s[Channel.WHEEL_RR]
    val rearSlip = if (fl != null && fr != null && rl != null && rr != null && fl + fr > 6f) ((rl + rr) / (fl + fr) - 1f) * 100f else null
    val yaw = Analytics.yawActual
    val steer = s[Channel.STEERING]
    val rpm = s[Channel.RPM]
    val kph = s[Channel.SPEED]
    val ratio = if (rpm != null && kph != null && kph > 5f) rpm / (kph / 3.6f / cfg.tireCircumferenceM * 60f) else null
    fun zero(x: Float, fmt: String) = fmt.format(if (abs(x) < 0.05f) 0f else x)
    rows(c, RectF(area.left, top.bottom + 4f * u, area.right, area.bottom), listOf(
        Triple("REAR SLIP", rearSlip?.let { zero(it, "%+.1f%%") }, if (abs(rearSlip ?: 0f) > 8f) t.secondary else null),
        Triple("SLIP ANGLE β", Analytics.slipAngle?.let { zero(it, "%.1f°") }, if (abs(Analytics.slipAngle ?: 0f) > 6f) t.primary else null),
        Triple("YAW RATE", yaw?.let { zero(it, "%.1f°/S") }, null),
        Triple("LAT G", s[Channel.LAT_G, 1000]?.let { "%.2f G".format(abs(it)) }, null),
        Triple("STEERING", steer?.let { ChannelInfo.format(Channel.STEERING, it, imp) }, null),
        Triple("COUNTERSTEER", if (yaw != null && steer != null) (if (Analytics.counterSteer) "YES" else "NO") else null,
            if (Analytics.counterSteer) t.primary else null),
        Triple("ENGINE ÷ WHEELS", ratio?.let { "%.2f : 1".format(it) }, null),
    ))
}

private fun DashRenderer.busColumn(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
    val mode = if (cfg.source == Source.DEMO) "DEMO" else VehicleData.linkMode
    val area = column(c, r, "BUS · LINK", if (s.link.state == com.sickdash.data.LinkState.CONNECTED) "LIVE" else "OFFLINE")
    val raw = mode == "CAN"
    val head = RectF(area.left, area.top, area.right, area.top + area.height() * 0.45f)
    rows(c, head, listOf(
        Triple("LINK", mode, null),
        Triple("MSGS / S", "%,d".format(s.framesPerSecond), null),
        // a standard 8-byte frame is ~125 bits on the wire at 500 kbit/s
        Triple("BUS LOAD", if (raw) "${(s.framesPerSecond * 125f / 500_000f * 100f).roundToInt()}%" else null, null),
        Triple("CAN IDS", if (raw) VehicleData.sniffer.size.toString() else null, null),
        Triple("SIGNALS", "${Analytics.verifiedSignals} / ${Analytics.liveSignals} OK", null),
    ))
    val et = head.bottom + 4f * u
    label(c, "BUS EVENTS", area.left, et + 7f * u, 6.5f * u, t.primary, spacing = 0.14f)
    val list = RectF(area.left, et + 11f * u, area.right, area.bottom)
    val size = min(6.5f * u, list.height() / 6f * 0.62f)
    val lineH = size * 1.65f
    val n = max(1, (list.height() / lineH).toInt())
    val events = Events.recent(n)
    if (events.isEmpty()) label(c, "NONE YET", area.left, list.top + size * 1.2f, size, t.faint)
    for ((i, e) in events.withIndex()) {
        val y = list.top + lineH * i + size * 1.2f
        val time = hms.format(Date(e.at))
        label(c, time, area.left, y, size, t.faint, spacing = 0.04f)
        val tx = area.left + textWidth("00:00:00 ", Fonts.mono, size, 0.04f)
        label(c, ellipsize(e.title, area.right - tx, size), tx, y, size, if (e.strong) t.text else t.dim, spacing = 0.08f)
    }
}
