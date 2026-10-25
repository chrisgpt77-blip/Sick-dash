package com.sickdash.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.sickdash.data.Analytics
import com.sickdash.data.Channel
import com.sickdash.data.ChannelInfo
import com.sickdash.data.DashConfig
import com.sickdash.data.History
import com.sickdash.data.ShiftTracker
import com.sickdash.data.Snapshot
import com.sickdash.data.VehicleData
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// =============================================================================================
// CLUTCH: engine speed against the gearbox speed the road says it should be
// =============================================================================================

internal fun DashRenderer.clutchPage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig, gear: Int?) {
    val body = pageFrame(c, r, "CLUTCH · SLIP", "ENGINE VS GEARBOX · FROM ROAD SPEED")
    val rpm = s[Channel.RPM]
    val gb = Analytics.gearboxRpm
    val clutchIn = (s[Channel.CLUTCH] ?: 0f) >= 50f

    // two concentric rings: outer the engine, inner the gearbox, both swept over 270°
    val dial = RectF(body.left, body.top, body.left + min(body.width() * 0.42f, body.height() * 0.62f * 1.1f), body.top + body.height() * 0.62f)
    val cx = dial.centerX(); val cy = dial.centerY()
    val rad = min(dial.width(), dial.height()) / 2f - 6f * u
    val maxRpm = cfg.maxRpm.toFloat()
    fun ring(radius: Float, value: Float?, colour: Int, width: Float) {
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        stroke.strokeWidth = width
        stroke.color = withAlpha(t.faint, 110)
        c.drawArc(rect, 135f, 270f, false, stroke)
        if (value != null) {
            stroke.color = colour
            c.drawArc(rect, 135f, 270f * (value / maxRpm).coerceIn(0f, 1f), false, stroke)
        }
    }
    ring(rad, rpm, t.text, 6f * u)
    ring(rad * 0.78f, gb, t.secondary, 6f * u)
    val slipping = abs(Analytics.slipPct) > 2.5f
    val centre = when {
        rpm == null -> "—"
        clutchIn -> "IN"
        gear == 0 -> "N"
        else -> gearLabel(gear)
    }
    display(c, centre, cx, cy + rad * 0.2f, rad * 0.55f, if (slipping) t.primary else t.text, Paint.Align.CENTER, glow = true)
    label(c, when { rpm == null -> "NO DATA"; clutchIn -> "PEDAL DOWN"; gear == 0 -> "NEUTRAL"; slipping -> "SLIPPING"; else -> "LOCKED UP" },
        cx, cy + rad * 0.45f, 7f * u, if (slipping) t.primary else t.dim, Paint.Align.CENTER)
    label(c, "ENGINE", cx - rad * 0.7f, dial.bottom - 1f * u, 6.5f * u, t.text, Paint.Align.CENTER)
    label(c, "GEARBOX", cx + rad * 0.7f, dial.bottom - 1f * u, 6.5f * u, t.secondary, Paint.Align.CENTER)

    val stats = cells(RectF(dial.right + 10f * u, body.top, body.right, dial.bottom), 6, 2)
    statCell(c, stats[0], "ENGINE", rpm?.let { "%,d".format(it.roundToInt()) }, "RPM")
    statCell(c, stats[1], "GEARBOX", gb?.let { "%,d".format(it.roundToInt()) }, "RPM", t.secondary)
    statCell(c, stats[2], "DIFFERENCE", if (rpm != null && gb != null && !clutchIn) "%+,d".format((rpm - gb).roundToInt()) else null, "RPM")
    statCell(c, stats[3], "SLIP", if (gb != null && !clutchIn) "%.1f".format(Analytics.slipPct) else null, "%", if (slipping) t.primary else t.text)
    statCell(c, stats[4], "HEAT ESTIMATE", temps(cfg)(Analytics.clutchHeatC).dropLast(2), if (cfg.imperial) "°F" else "°C",
        if (Analytics.clutchHeatC > 200f) t.primary else if (Analytics.clutchHeatC > 120f) t.secondary else t.text)
    statCell(c, stats[5], "SLIP EVENTS", Analytics.slipEvents.toString(), "", if (Analytics.slipEvents > 0) t.secondary else t.text)

    val g = RectF(body.left, dial.bottom + 8f * u, body.right, body.bottom)
    panel(c, g)
    label(c, "SLIP · LAST 30 S", g.left + 6f * u, g.top + 10f * u, 7f * u, t.dim)
    label(c, "ESTIMATE · NOT A SENSOR", g.right - 6f * u, g.top + 10f * u, 6.5f * u, t.faint, Paint.Align.RIGHT)
    val plot = RectF(g.left + 6f * u, g.top + 14f * u, g.right - 6f * u, g.bottom - 4f * u)
    centreAxis(c, plot, -15f, 15f, "+15%", "-15%")
    ringPlot(c, plot, Analytics.slipHistory, Analytics.slipHead, -15f, 15f, t.primary)
}

// =============================================================================================
// THROTTLE: pedal work — throttle, brake and revs together, heel-toe overlap shaded
// =============================================================================================

internal fun DashRenderer.throttlePage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
    val body = pageFrame(c, r, "THROTTLE · PEDALS", "LAST 10 S · THROTTLE · BRAKE · RPM")
    val thr = History.series(Channel.THROTTLE)
    val brk = History.series(Channel.BRAKE)
    val rpm = History.series(Channel.RPM)

    val g = RectF(body.left, body.top, body.right, body.top + body.height() * 0.66f)
    panel(c, g)
    val plot = RectF(g.left + 26f * u, g.top + 10f * u, g.right - 8f * u, g.bottom - 14f * u)
    stroke.color = t.line; stroke.strokeWidth = max(1f, 0.8f * u)
    for (k in listOf(25, 50, 75, 100)) {
        val y = plot.bottom - plot.height() * k / 100f
        c.drawLine(plot.left, y, plot.right, y, stroke)
        label(c, "$k", plot.left - 4f * u, y + 3f * u, 6.5f * u, t.faint, Paint.Align.RIGHT)
    }
    // heel-toe: brake and throttle pressed together
    fill.color = withAlpha(t.secondary, 50)
    val step = plot.width() / (thr.size - 1)
    for (i in thr.indices) {
        if (!thr[i].isNaN() && !brk[i].isNaN() && thr[i] > 8f && brk[i] > 5f) {
            val x = plot.left + step * i
            c.drawRect(x - step / 2, plot.top, x + step / 2, plot.bottom, fill)
        }
    }
    traceLine(c, plot, rpm, 0f, cfg.maxRpm.toFloat(), withAlpha(t.dim, 160), 1f * u)
    traceLine(c, plot, brk, 0f, 100f, t.primary, 1.5f * u)
    traceLine(c, plot, thr, 0f, 100f, t.text, 1.5f * u)
    var x = plot.left
    for ((name, col) in listOf("THROTTLE" to t.text, "BRAKE" to t.primary, "RPM" to t.dim, "HEEL-TOE" to t.secondary)) {
        fill.color = col
        c.drawRect(x, g.bottom - 8f * u, x + 8f * u, g.bottom - 5f * u, fill)
        label(c, name, x + 11f * u, g.bottom - 4f * u, 6.5f * u, col)
        x += textWidth(name, Fonts.mono, 6.5f * u, 0.16f) + 22f * u
    }
    label(c, "NOW", plot.right, g.bottom - 4f * u, 6.5f * u, t.faint, Paint.Align.RIGHT)

    // the last 10 s in numbers
    var full = 0; var overlap = 0; var n = 0; var sum = 0f
    for (i in thr.indices) {
        if (thr[i].isNaN()) continue
        n++; sum += thr[i]
        if (thr[i] >= 95f) full++
        if (!brk[i].isNaN() && thr[i] > 8f && brk[i] > 5f) overlap++
    }
    val hz = History.HZ.toFloat()
    val stats = cells(RectF(body.left, g.bottom + 8f * u, body.right, body.bottom), 5, 5)
    statCell(c, stats[0], "PEDAL", s[Channel.THROTTLE]?.let { "${it.roundToInt()}" }, "%")
    statCell(c, stats[1], "BRAKE", s[Channel.BRAKE]?.let { "${it.roundToInt()}" }, "%", t.primary)
    statCell(c, stats[2], "AVERAGE", if (n > 0) "${(sum / n).roundToInt()}" else null, "%")
    statCell(c, stats[3], "FULL THROTTLE", if (n > 0) "%.1f".format(full / hz) else null, "S")
    statCell(c, stats[4], "HEEL-TOE ×${ShiftTracker.heelToeCount}", if (n > 0) "%.1f".format(overlap / hz) else null, "S",
        if (overlap > 0) t.secondary else t.text)
}

// =============================================================================================
// DYNO: wheel power from acceleration, plotted against revs for every full-throttle pull
// =============================================================================================

internal fun DashRenderer.dynoPage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
    val body = pageFrame(c, r, "DYNO · ROAD PULL", "${Analytics.MASS_KG.roundToInt()} KG · WOT IN GEAR")
    val hp = 1.341f
    val g = RectF(body.left, body.top, body.left + body.width() * 0.68f, body.bottom)
    panel(c, g)
    val plot = RectF(g.left + 28f * u, g.top + 12f * u, g.right - 28f * u, g.bottom - 16f * u)
    val maxRpm = cfg.maxRpm.toFloat()
    val topKw = max(160f, (Analytics.bestKw.maxOrNull() ?: 0f) * 1.15f)
    val topNm = topKw * 1000f / (3000f * 2f * PI.toFloat() / 60f)   // torque axis that keeps both curves on screen
    stroke.color = t.line; stroke.strokeWidth = max(1f, 0.8f * u)
    var k = 1000
    while (k < cfg.maxRpm) {
        val xx = plot.left + plot.width() * k / maxRpm
        c.drawLine(xx, plot.top, xx, plot.bottom, stroke)
        label(c, "${k / 1000}K", xx, g.bottom - 5f * u, 6.5f * u, t.faint, Paint.Align.CENTER)
        k += 1000
    }
    label(c, if (cfg.imperial) "WHP" else "KW", plot.left - 4f * u, plot.top + 4f * u, 6.5f * u, t.text, Paint.Align.RIGHT)
    label(c, if (cfg.imperial) "LB-FT" else "NM", plot.right + 4f * u, plot.top + 4f * u, 6.5f * u, t.secondary)
    for (i in 1..3) {
        val y = plot.bottom - plot.height() * i / 4f
        c.drawLine(plot.left, y, plot.right, y, stroke)
        val p = topKw * i / 4f * (if (cfg.imperial) hp else 1f)
        val tq = topNm * i / 4f * (if (cfg.imperial) 0.7376f else 1f)
        label(c, "${p.roundToInt()}", plot.left - 4f * u, y + 3f * u, 6.5f * u, t.faint, Paint.Align.RIGHT)
        label(c, "${tq.roundToInt()}", plot.right + 4f * u, y + 3f * u, 6.5f * u, t.faint)
    }

    fun curve(kw: FloatArray, torque: Boolean, colour: Int, width: Float) {
        path.reset()
        var started = false
        for (i in kw.indices) {
            val rpm = i * 100f + 50f
            if (kw[i] <= 0f || rpm > maxRpm || (torque && rpm < 1000f)) { started = false; continue }
            val v = if (torque) kw[i] * 1000f / (rpm * 2f * PI.toFloat() / 60f) / topNm else kw[i] / topKw
            val xx = plot.left + plot.width() * rpm / maxRpm
            val y = plot.bottom - plot.height() * v.coerceIn(0f, 1f)
            if (started) path.lineTo(xx, y) else { path.moveTo(xx, y); started = true }
        }
        stroke.color = withAlpha(colour, 60); stroke.strokeWidth = width * 3f; c.drawPath(path, stroke)
        stroke.color = colour; stroke.strokeWidth = width; c.drawPath(path, stroke)
    }
    curve(Analytics.bestKw, false, withAlpha(t.text, 110), 1.2f * u)
    curve(Analytics.bestKw, true, withAlpha(t.secondary, 110), 1.2f * u)
    curve(Analytics.pullKw, false, t.text, 1.8f * u)
    curve(Analytics.pullKw, true, t.secondary, 1.8f * u)
    if (Analytics.bestKw.all { it <= 0f } && Analytics.pullKw.all { it <= 0f }) {
        display(c, "NO PULL YET", plot.centerX(), plot.centerY() - 4f * u, 14f * u, t.faint, Paint.Align.CENTER)
        label(c, "FULL THROTTLE IN 2ND OR 3RD FROM ~2,500 RPM TO REDLINE", plot.centerX(), plot.centerY() + 10f * u, 6.5f * u, t.dim, Paint.Align.CENTER)
    }
    s[Channel.RPM]?.let { live ->
        val xx = plot.left + plot.width() * (live / maxRpm).coerceIn(0f, 1f)
        stroke.color = withAlpha(t.primary, 160); stroke.strokeWidth = 1f * u
        c.drawLine(xx, plot.top, xx, plot.bottom, stroke)
    }

    val side = RectF(g.right + 8f * u, body.top, body.right, body.bottom)
    val boxes = cells(side, 5, 1)
    val pw = { kw: Float -> if (cfg.imperial) "${(kw * hp).roundToInt()}" else "${kw.roundToInt()}" }
    val pu = if (cfg.imperial) "WHP" else "KW"
    statCell(c, boxes[0], "STATUS", if (Analytics.pulling) "PULLING" else if (Analytics.peakKw > 0f) "DONE" else "READY", "",
        if (Analytics.pulling) t.primary else t.text)
    statCell(c, boxes[1], "POWER NOW", pw(Analytics.powerKw), pu)
    statCell(c, boxes[2], "PEAK @ ${"%,d".format(Analytics.peakKwRpm)}", if (Analytics.peakKw > 0f) pw(Analytics.peakKw) else null, pu)
    statCell(c, boxes[3], "TORQUE @ ${"%,d".format(Analytics.peakNmRpm)}",
        if (Analytics.peakNm > 0f) "${(Analytics.peakNm * if (cfg.imperial) 0.7376f else 1f).roundToInt()}" else null,
        if (cfg.imperial) "LB-FT" else "NM", t.secondary)
    panel(c, boxes[4])
    label(c, "RESET", boxes[4].centerX(), boxes[4].centerY() + 3f * u, 8f * u, t.text, Paint.Align.CENTER)
    newHits += RectF(boxes[4]) to { resetSession() }
}

// =============================================================================================
// SYSTEMS: every signal, where it comes from, how fast, and whether it looks right
// =============================================================================================

internal fun DashRenderer.systemsPage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
    val body = pageFrame(c, r, "SYSTEMS · SIGNALS",
        "${Analytics.liveSignals} SIGNALS · ${Analytics.verifiedSignals} VERIFIED · ${ChannelInfo.ALL.size} KNOWN")
    val rows = ChannelInfo.ALL
    val size = min(7f * u, body.height() / 24f * 0.62f)
    val lineH = size * 1.6f
    val perCol = max(1, ((body.height() - lineH) / lineH).toInt())
    val cols = (rows.size + perCol - 1) / perCol
    val colGap = 10f * u
    val colW = (body.width() - colGap * (cols - 1)) / cols
    for (col in 0 until cols) {
        val x0 = body.left + col * (colW + colGap)
        val xs = floatArrayOf(x0, x0 + colW * 0.27f, x0 + colW * 0.48f, x0 + colW * 0.68f)
        for ((i, h) in listOf("SIGNAL", "VALUE", "SOURCE", "HZ").withIndex()) label(c, h, xs[i], body.top + size, size * 0.9f, t.faint)
        label(c, "STATUS", x0 + colW, body.top + size, size * 0.9f, t.faint, Paint.Align.RIGHT)
        stroke.color = t.line; stroke.strokeWidth = max(1f, 0.6f * u)
        c.drawLine(x0, body.top + size + 3f * u, x0 + colW, body.top + size + 3f * u, stroke)
        for (j in 0 until perCol) {
            val info = rows.getOrNull(col * perCol + j) ?: break
            val y = body.top + lineH * (j + 1) + size
            val ch = info.channel
            val fresh = s[ch, 3000]
            val old = s[ch, Long.MAX_VALUE]
            val (status, colour) = when {
                old == null -> "NOT MAPPED" to t.faint
                fresh == null -> "STALE" to t.dim
                ChannelInfo.needsCheck(ch, fresh, s) -> "CHECK" to t.secondary
                else -> "OK" to t.text
            }
            label(c, info.name, xs[0], y, size, if (old == null) t.faint else t.dim)
            label(c, ellipsize(old?.let { ChannelInfo.format(ch, it, cfg.imperial) } ?: "—", xs[2] - xs[1] - 4f * u, size),
                xs[1], y, size, if (fresh == null) t.faint else t.text)
            label(c, if (old == null) "—" else VehicleData.sourceOf(ch), xs[2], y, size, t.dim)
            val hz = Analytics.rateHz[ch.ordinal]
            label(c, if (fresh == null) "—" else if (hz >= 10f) "%.0f".format(hz) else "%.1f".format(hz), xs[3], y, size, t.dim)
            label(c, status, x0 + colW, y, size, colour, Paint.Align.RIGHT)
        }
    }
}
