package com.sickdash.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.sickdash.data.Channel
import com.sickdash.data.DashConfig
import com.sickdash.data.GearEstimator
import com.sickdash.data.LinkState
import com.sickdash.data.Snapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the whole dashboard onto any Canvas: the phone's [DashView] and the Android Auto
 * map surface both use it, so the car screen and the phone look identical.
 */
class DashRenderer {
    enum class Page { DASH, RACE }

    var page = Page.DASH
    private var accent = Color.CYAN

    private val bg = Color.rgb(6, 7, 10)
    private val panel = Color.rgb(14, 17, 23)
    private val panelEdge = Color.rgb(31, 37, 47)
    private val track = Color.rgb(24, 28, 36)
    private val textMain = Color.rgb(236, 240, 245)
    private val textDim = Color.rgb(118, 129, 145)
    private val green = Color.rgb(61, 220, 132)
    private val amber = Color.rgb(255, 179, 0)
    private val red = Color.rgb(255, 59, 48)
    private val segOff = Color.rgb(22, 26, 33)

    private val bold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Smoothed display values so the needle/bars glide instead of stepping at the CAN rate.
    private var rpmShown = 0f
    private var speedShown = 0f
    private var latShown = 0f
    private var lonShown = 0f
    private var peakLat = 0f
    private var peakBrake = 0f
    private var peakAccel = 0f
    private val trail = FloatArray(TRAIL * 2)
    private var trailHead = 0
    private var trailCount = 0

    fun resetPeaks() {
        peakLat = 0f; peakBrake = 0f; peakAccel = 0f; trailCount = 0
    }

    fun draw(canvas: Canvas, area: RectF, s: Snapshot, cfg: DashConfig) {
        accent = cfg.accent
        val rpm = s[Channel.RPM] ?: 0f
        val speed = s[Channel.SPEED] ?: 0f
        rpmShown += (rpm - rpmShown) * 0.45f
        speedShown += (speed - speedShown) * 0.35f
        val lat = s[Channel.LAT_G, 1000] ?: 0f
        val lon = s[Channel.LON_G, 1000] ?: 0f
        latShown += (lat - latShown) * 0.3f
        lonShown += (lon - lonShown) * 0.3f
        peakLat = max(peakLat, abs(latShown))
        peakBrake = max(peakBrake, -lonShown)
        peakAccel = max(peakAccel, lonShown)
        trail[trailHead * 2] = latShown
        trail[trailHead * 2 + 1] = lonShown
        trailHead = (trailHead + 1) % TRAIL
        trailCount = min(trailCount + 1, TRAIL)

        fill.color = bg
        canvas.drawRect(area, fill)
        when (page) {
            Page.DASH -> drawDash(canvas, area, s, cfg)
            Page.RACE -> drawRace(canvas, area, s, cfg)
        }
    }

    // ---------------------------------------------------------------- layouts

    private fun drawDash(c: Canvas, a: RectF, s: Snapshot, cfg: DashConfig) {
        val w = a.width()
        val h = a.height()
        val landscape = w / h > 1.25f
        val pad = min(w, h) * 0.035f
        val statusH = min(w, h) * 0.065f

        val shift = RectF(a.left + pad, a.top + pad, a.right - pad, a.top + pad + min(w, h) * 0.055f)
        drawShiftLights(c, shift, s.now, cfg)
        drawStatus(c, RectF(a.left + pad, a.bottom - statusH - pad * 0.5f, a.right - pad, a.bottom - pad * 0.5f), s)

        val top = shift.bottom + pad
        val bottom = a.bottom - statusH - pad
        val contentH = bottom - top

        if (landscape) {
            val r = min(contentH / 1.72f, w * 0.215f)
            val cx = a.centerX()
            val cy = top + r + (contentH - 1.72f * r) / 2f
            drawTach(c, cx, cy, r, s, cfg)

            val gap = pad * 0.6f
            val left = RectF(a.left + pad, top, cx - r - pad * 1.4f, bottom)
            val right = RectF(cx + r + pad * 1.4f, top, a.right - pad, bottom)
            drawTileColumn(c, left, gap, tiles(s, cfg).take(4))

            val gSize = min(right.width(), right.height() * 0.66f)
            val gRect = RectF(right.centerX() - gSize / 2, right.top, right.centerX() + gSize / 2, right.top + gSize)
            drawGMeter(c, gRect)
            val rest = RectF(right.left, gRect.bottom + gap, right.right, right.bottom)
            drawTileColumn(c, rest, gap, listOf(throttleTile(s)))
        } else {
            val r = min(w * 0.40f, contentH * 0.26f)
            val cx = a.centerX()
            val cy = top + r + pad * 0.3f
            drawTach(c, cx, cy, r, s, cfg)
            val gridTop = cy + r * 0.78f + pad
            val gap = pad * 0.6f
            val gH = min(w * 0.55f, (bottom - gridTop) * 0.5f)
            val grid = RectF(a.left + pad, gridTop, a.right - pad, bottom - gH - gap)
            drawTileGrid(c, grid, gap, tiles(s, cfg).take(4))
            val gRow = RectF(a.left + pad, bottom - gH, a.right - pad, bottom)
            val gRect = RectF(gRow.left, gRow.top, gRow.left + gH, gRow.bottom)
            drawGMeter(c, gRect)
            drawTileColumn(c, RectF(gRect.right + gap, gRow.top, gRow.right, gRow.bottom), gap, listOf(throttleTile(s)))
        }
    }

    private fun drawRace(c: Canvas, a: RectF, s: Snapshot, cfg: DashConfig) {
        val w = a.width()
        val h = a.height()
        val pad = min(w, h) * 0.035f
        val statusH = min(w, h) * 0.065f
        val shift = RectF(a.left + pad, a.top + pad, a.right - pad, a.top + pad + h * 0.11f)
        drawShiftLights(c, shift, s.now, cfg)
        val bar = RectF(shift.left, shift.bottom + pad * 0.6f, shift.right, shift.bottom + pad * 0.6f + h * 0.06f)
        drawRpmBar(c, bar, cfg)
        drawStatus(c, RectF(a.left + pad, a.bottom - statusH - pad * 0.5f, a.right - pad, a.bottom - pad * 0.5f), s)

        val tileH = h * 0.17f
        val bottom = a.bottom - statusH - pad
        val tilesRect = RectF(a.left + pad, bottom - tileH, a.right - pad, bottom)
        val mid = RectF(a.left + pad, bar.bottom + pad, a.right - pad, tilesRect.top - pad)

        val gear = gearText(s, cfg)
        val gearSize = mid.height() * 1.0f
        text.typeface = bold
        text.textSize = gearSize
        text.color = if (rpmShown >= cfg.shiftRpm) red else textMain
        text.textAlign = Paint.Align.CENTER
        c.drawText(gear, mid.centerX(), mid.centerY() + gearSize * 0.36f, text)

        val sideW = (mid.width() - gearSize * 0.8f) / 2f
        bigReadout(c, RectF(mid.left, mid.top, mid.left + sideW, mid.bottom), "%,d".format(rpmShown.roundToInt()), "RPM")
        val sp = if (cfg.imperial) speedShown * KPH_TO_MPH else speedShown
        bigReadout(c, RectF(mid.right - sideW, mid.top, mid.right, mid.bottom), sp.roundToInt().toString(), if (cfg.imperial) "MPH" else "KM/H")

        val list = tiles(s, cfg)
        drawTileRow(c, tilesRect, pad * 0.6f, listOf(list[0], list[1], list[3], throttleTile(s)))
    }

    // ---------------------------------------------------------------- widgets

    private fun drawShiftLights(c: Canvas, r: RectF, now: Long, cfg: DashConfig) {
        val n = 15
        val gap = r.width() * 0.006f
        val segW = (r.width() - gap * (n - 1)) / n
        val start = (cfg.shiftRpm - 2500).toFloat()
        val lit = ((rpmShown - start) / (cfg.shiftRpm - start) * n).coerceIn(0f, n.toFloat())
        val flash = rpmShown >= cfg.shiftRpm
        val flashOn = (now / 70) % 2 == 0L
        for (i in 0 until n) {
            val x = r.left + i * (segW + gap)
            rect.set(x, r.top, x + segW, r.bottom)
            val on = i < lit
            val col = when {
                flash -> if (flashOn) cfg.accent else segOff
                !on -> segOff
                i < n * 0.4f -> green
                i < n * 0.75f -> amber
                else -> red
            }
            if (col != segOff) {
                fill.color = col
                fill.alpha = 70
                c.drawRoundRect(rect.left - gap, rect.top - gap, rect.right + gap, rect.bottom + gap, r.height() * 0.3f, r.height() * 0.3f, fill)
                fill.alpha = 255
            }
            fill.color = col
            c.drawRoundRect(rect, r.height() * 0.22f, r.height() * 0.22f, fill)
        }
    }

    private fun drawRpmBar(c: Canvas, r: RectF, cfg: DashConfig) {
        val frac = (rpmShown / cfg.maxRpm).coerceIn(0f, 1f)
        fill.color = track
        c.drawRoundRect(r, r.height() * 0.2f, r.height() * 0.2f, fill)
        val redX = r.left + r.width() * cfg.redline / cfg.maxRpm
        fill.color = red
        fill.alpha = 60
        c.drawRect(redX, r.top, r.right, r.bottom, fill)
        fill.alpha = 255
        fill.color = rpmColor(cfg)
        rect.set(r.left, r.top, r.left + r.width() * frac, r.bottom)
        c.drawRoundRect(rect, r.height() * 0.2f, r.height() * 0.2f, fill)
        stroke.color = bg
        stroke.strokeWidth = max(1f, r.width() * 0.002f)
        for (k in 1 until cfg.maxRpm / 1000) {
            val x = r.left + r.width() * k * 1000f / cfg.maxRpm
            c.drawLine(x, r.top, x, r.bottom, stroke)
        }
    }

    private fun drawTach(c: Canvas, cx: Float, cy: Float, r: Float, s: Snapshot, cfg: DashConfig) {
        val startAngle = 135f
        val sweep = 270f
        val maxRpm = cfg.maxRpm.toFloat()
        val thick = r * 0.085f
        rect.set(cx - r, cy - r, cx + r, cy + r)

        // Backdrop disc
        fill.color = panel
        c.drawCircle(cx, cy, r * 0.82f, fill)
        stroke.color = panelEdge
        stroke.strokeWidth = r * 0.008f
        c.drawCircle(cx, cy, r * 0.82f, stroke)

        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = thick
        stroke.color = track
        c.drawArc(rect, startAngle, sweep, false, stroke)

        // Red zone on the outer edge
        val redStart = startAngle + sweep * cfg.redline / maxRpm
        stroke.color = red
        stroke.strokeWidth = r * 0.025f
        rect.inset(-thick * 0.75f, -thick * 0.75f)
        c.drawArc(rect, redStart, startAngle + sweep - redStart, false, stroke)
        rect.inset(thick * 0.75f, thick * 0.75f)

        // Value arc with a soft glow
        val frac = (rpmShown / maxRpm).coerceIn(0f, 1f)
        val col = rpmColor(cfg)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = col
        stroke.alpha = 55
        stroke.strokeWidth = thick * 1.9f
        if (frac > 0.002f) c.drawArc(rect, startAngle, sweep * frac, false, stroke)
        stroke.alpha = 255
        stroke.strokeWidth = thick
        if (frac > 0.002f) c.drawArc(rect, startAngle, sweep * frac, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT

        // Ticks + numbers
        text.typeface = bold
        text.textAlign = Paint.Align.CENTER
        text.textSize = r * 0.11f
        var rpmTick = 0
        while (rpmTick <= cfg.maxRpm) {
            val major = rpmTick % 1000 == 0
            val ang = Math.toRadians((startAngle + sweep * rpmTick / maxRpm).toDouble())
            val r1 = r - thick * 0.5f - r * 0.02f
            val r2 = r1 - if (major) r * 0.08f else r * 0.04f
            stroke.color = if (rpmTick >= cfg.redline) red else if (major) textMain else textDim
            stroke.strokeWidth = if (major) r * 0.014f else r * 0.008f
            val cosA = cos(ang).toFloat()
            val sinA = sin(ang).toFloat()
            c.drawLine(cx + cosA * r1, cy + sinA * r1, cx + cosA * r2, cy + sinA * r2, stroke)
            if (major) {
                val rl = r2 - r * 0.09f
                text.color = if (rpmTick >= cfg.redline) red else textDim
                c.drawText((rpmTick / 1000).toString(), cx + cosA * rl, cy + sinA * rl + text.textSize * 0.36f, text)
            }
            rpmTick += 250
        }

        // Tip marker
        if (frac > 0.002f) {
            val ang = Math.toRadians((startAngle + sweep * frac).toDouble())
            fill.color = Color.WHITE
            c.drawCircle(cx + cos(ang).toFloat() * r, cy + sin(ang).toFloat() * r, thick * 0.32f, fill)
        }

        // Digital readouts
        val sp = if (cfg.imperial) speedShown * KPH_TO_MPH else speedShown
        text.color = textMain
        text.textSize = r * 0.50f
        c.drawText(sp.roundToInt().toString(), cx, cy + r * 0.12f, text)
        text.typeface = medium
        text.textSize = r * 0.085f
        text.color = textDim
        c.drawText(if (cfg.imperial) "MPH" else "KM/H", cx, cy + r * 0.26f, text)
        text.textSize = r * 0.10f
        text.color = col
        text.typeface = bold
        c.drawText("%,d RPM".format(rpmShown.roundToInt()), cx, cy - r * 0.33f, text)

        // Gear box in the gap at the bottom of the arc
        val gear = gearText(s, cfg)
        val gs = r * 0.36f
        rect.set(cx - gs * 0.55f, cy + r * 0.40f, cx + gs * 0.55f, cy + r * 0.40f + gs)
        fill.color = bg
        c.drawRoundRect(rect, gs * 0.18f, gs * 0.18f, fill)
        stroke.color = if (rpmShown >= cfg.shiftRpm) red else cfg.accent
        stroke.strokeWidth = r * 0.012f
        c.drawRoundRect(rect, gs * 0.18f, gs * 0.18f, stroke)
        text.textSize = gs * 0.82f
        text.color = textMain
        c.drawText(gear, rect.centerX(), rect.centerY() + text.textSize * 0.36f, text)
    }

    private fun drawGMeter(c: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = min(r.width(), r.height()) / 2f
        val range = 1.25f
        fill.color = panel
        c.drawCircle(cx, cy, rad, fill)
        stroke.color = panelEdge
        stroke.strokeWidth = rad * 0.015f
        c.drawCircle(cx, cy, rad, stroke)
        stroke.color = track
        stroke.strokeWidth = rad * 0.01f
        for (g in floatArrayOf(0.5f, 1.0f)) c.drawCircle(cx, cy, rad * g / range, stroke)
        c.drawLine(cx - rad, cy, cx + rad, cy, stroke)
        c.drawLine(cx, cy - rad, cx, cy + rad, stroke)

        text.typeface = medium
        text.textSize = rad * 0.1f
        text.color = textDim
        text.textAlign = Paint.Align.LEFT
        c.drawText("0.5", cx + rad * 0.03f, cy - rad * 0.5f / range - rad * 0.02f, text)
        c.drawText("1.0", cx + rad * 0.03f, cy - rad * 1.0f / range - rad * 0.02f, text)
        text.textAlign = Paint.Align.CENTER
        c.drawText("G", cx - rad * 0.72f, cy - rad * 0.62f, text)

        for (k in 0 until trailCount) {
            val idx = ((trailHead - 1 - k) % TRAIL + TRAIL) % TRAIL
            val (x, y) = gPoint(cx, cy, rad, range, trail[idx * 2], trail[idx * 2 + 1])
            fill.color = textDim
            fill.alpha = (120 * (1f - k.toFloat() / trailCount)).toInt()
            c.drawCircle(x, y, rad * 0.022f, fill)
        }
        fill.alpha = 255
        val (x, y) = gPoint(cx, cy, rad, range, latShown, lonShown)
        fill.color = Color.WHITE
        fill.alpha = 60
        c.drawCircle(x, y, rad * 0.11f, fill)
        fill.alpha = 255
        fill.color = Color.WHITE
        c.drawCircle(x, y, rad * 0.055f, fill)

        val total = sqrt(latShown * latShown + lonShown * lonShown)
        text.typeface = bold
        text.textSize = rad * 0.17f
        text.color = textMain
        c.drawText("%.2f".format(total), cx + rad * 0.55f, cy + rad * 0.72f, text)
        text.typeface = medium
        text.textSize = rad * 0.085f
        text.color = textDim
        c.drawText("PK %.2f / %.2f".format(peakLat, max(peakBrake, peakAccel)), cx - rad * 0.45f, cy + rad * 0.74f, text)
    }

    /** Felt-force convention: braking (negative lon) moves the dot up, a right turn moves it left. */
    private fun gPoint(cx: Float, cy: Float, rad: Float, range: Float, lat: Float, lon: Float): Pair<Float, Float> {
        var dx = -lat / range
        var dy = lon / range
        val len = sqrt(dx * dx + dy * dy)
        if (len > 0.95f) { dx *= 0.95f / len; dy *= 0.95f / len }
        return Pair(cx + dx * rad, cy + dy * rad)
    }

    private class Tile(
        val label: String,
        val value: Float?,
        val format: String,
        val unit: String,
        val min: Float,
        val max: Float,
        val warnLow: Float = Float.NEGATIVE_INFINITY,
        val warnHigh: Float = Float.POSITIVE_INFINITY,
        val critHigh: Float = Float.POSITIVE_INFINITY,
    )

    private fun tiles(s: Snapshot, cfg: DashConfig): List<Tile> {
        fun t(c: Float) = if (cfg.imperial) c * 9f / 5f + 32f else c
        fun temp(c: Float?) = c?.let { t(it) }
        val deg = if (cfg.imperial) "°F" else "°C"
        return listOf(
            Tile("COOLANT", temp(s[Channel.COOLANT]), "%.0f", deg, t(40f), t(130f), t(60f), t(105f), t(112f)),
            Tile("OIL TEMP", temp(s[Channel.OIL_TEMP]), "%.0f", deg, t(40f), t(150f), t(70f), t(130f), t(140f)),
            Tile("INTAKE", temp(s[Channel.INTAKE_TEMP]), "%.0f", deg, t(-10f), t(80f), warnHigh = t(55f), critHigh = t(65f)),
            Tile("BATTERY", s[Channel.BATTERY], "%.1f", "V", 10f, 16f, 12.2f, 15.0f, 15.5f),
        )
    }

    private fun throttleTile(s: Snapshot) = Tile("THROTTLE", s[Channel.THROTTLE], "%.0f", "%", 0f, 100f)

    private fun drawTileColumn(c: Canvas, r: RectF, gap: Float, list: List<Tile>) {
        val th = (r.height() - gap * (list.size - 1)) / list.size
        list.forEachIndexed { i, t ->
            val top = r.top + i * (th + gap)
            drawTile(c, RectF(r.left, top, r.right, top + min(th, r.width() * 0.62f)), t)
        }
    }

    private fun drawTileGrid(c: Canvas, r: RectF, gap: Float, list: List<Tile>) {
        val tw = (r.width() - gap) / 2f
        val th = (r.height() - gap) / 2f
        list.forEachIndexed { i, t ->
            val x = r.left + (i % 2) * (tw + gap)
            val y = r.top + (i / 2) * (th + gap)
            drawTile(c, RectF(x, y, x + tw, y + th), t)
        }
    }

    private fun drawTileRow(c: Canvas, r: RectF, gap: Float, list: List<Tile>) {
        val tw = (r.width() - gap * (list.size - 1)) / list.size
        list.forEachIndexed { i, t ->
            val x = r.left + i * (tw + gap)
            drawTile(c, RectF(x, r.top, x + tw, r.bottom), t)
        }
    }

    private fun drawTile(c: Canvas, r: RectF, t: Tile) {
        val corner = min(r.width(), r.height()) * 0.12f
        fill.color = panel
        c.drawRoundRect(r, corner, corner, fill)
        val v = t.value
        val state = when {
            v == null -> textDim
            v >= t.critHigh -> red
            v >= t.warnHigh || v < t.warnLow -> amber
            else -> textMain
        }
        stroke.color = if (state == red || state == amber) state else panelEdge
        stroke.strokeWidth = max(1f, r.height() * 0.02f)
        c.drawRoundRect(r, corner, corner, stroke)

        val padX = r.width() * 0.08f
        val labelSize = min(r.height() * 0.19f, r.width() * 0.11f)
        text.typeface = medium
        text.textSize = labelSize
        text.color = textDim
        text.textAlign = Paint.Align.LEFT
        c.drawText(t.label, r.left + padX, r.top + r.height() * 0.08f + labelSize, text)

        val valueSize = min(r.height() * 0.46f, r.width() * 0.30f)
        text.typeface = bold
        text.textSize = valueSize
        text.color = state
        val vs = if (v == null) "--" else t.format.format(v)
        val baseline = r.top + r.height() * 0.30f + valueSize
        c.drawText(vs, r.left + padX, baseline, text)
        val vw = text.measureText(vs)
        text.typeface = medium
        text.textSize = labelSize
        text.color = textDim
        c.drawText(t.unit, r.left + padX + vw + labelSize * 0.3f, baseline, text)

        val barH = max(2f, r.height() * 0.06f)
        val by = r.bottom - r.height() * 0.13f
        fill.color = track
        rect.set(r.left + padX, by, r.right - padX, by + barH)
        c.drawRoundRect(rect, barH / 2, barH / 2, fill)
        if (v != null) {
            val f = ((v - t.min) / (t.max - t.min)).coerceIn(0f, 1f)
            fill.color = if (state == textMain) accent else state
            rect.right = rect.left + rect.width() * f
            c.drawRoundRect(rect, barH / 2, barH / 2, fill)
        }
    }

    private fun bigReadout(c: Canvas, r: RectF, value: String, unit: String) {
        text.typeface = bold
        text.textAlign = Paint.Align.CENTER
        text.textSize = min(r.height() * 0.42f, r.width() * 0.30f)
        text.color = textMain
        c.drawText(value, r.centerX(), r.centerY() + text.textSize * 0.2f, text)
        val vs = text.textSize
        text.typeface = medium
        text.textSize = vs * 0.28f
        text.color = textDim
        c.drawText(unit, r.centerX(), r.centerY() + vs * 0.2f + text.textSize * 1.6f, text)
    }

    private fun drawStatus(c: Canvas, r: RectF, s: Snapshot) {
        val dot = when (s.link.state) {
            LinkState.CONNECTED -> green
            LinkState.CONNECTING -> amber
            LinkState.ERROR -> red
            LinkState.IDLE -> textDim
        }
        val h = r.height()
        fill.color = dot
        c.drawCircle(r.left + h * 0.3f, r.centerY(), h * 0.18f, fill)
        text.typeface = medium
        text.textSize = h * 0.48f
        text.color = textDim
        text.textAlign = Paint.Align.LEFT
        val right = "${s.framesPerSecond} msg/s   ${clock.format(Date(s.now))}"
        val rightW = text.measureText(right)
        var msg = s.link.message
        val maxW = r.width() - rightW - h * 1.5f
        if (text.measureText(msg) > maxW) {
            while (msg.length > 4 && text.measureText("$msg…") > maxW) msg = msg.dropLast(1)
            msg = "$msg…"
        }
        c.drawText(msg, r.left + h * 0.7f, r.centerY() + text.textSize * 0.36f, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(right, r.right, r.centerY() + text.textSize * 0.36f, text)
    }

    // ---------------------------------------------------------------- helpers

    private fun rpmColor(cfg: DashConfig): Int = when {
        rpmShown >= cfg.shiftRpm -> red
        rpmShown >= cfg.shiftRpm - 1000 -> amber
        else -> cfg.accent
    }

    private fun gearText(s: Snapshot, cfg: DashConfig): String {
        s[Channel.GEAR]?.let { g ->
            return when (val n = g.roundToInt()) { 0 -> "N"; -1 -> "R"; else -> n.toString() }
        }
        val rpm = s[Channel.RPM] ?: return "–"
        val speed = s[Channel.SPEED] ?: return "–"
        return when (val g = GearEstimator.estimate(rpm, speed, cfg)) {
            null -> "–"
            0 -> "N"
            else -> g.toString()
        }
    }

    companion object {
        const val KPH_TO_MPH = 0.621371f
        private const val TRAIL = 48
    }
}
