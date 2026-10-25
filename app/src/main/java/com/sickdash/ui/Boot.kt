package com.sickdash.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.sickdash.data.ChannelInfo
import com.sickdash.data.DashConfig
import com.sickdash.data.Snapshot
import com.sickdash.data.Source
import com.sickdash.data.VehicleData
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The start-up sequence, drawn over the dash:
 *
 *  0.0 - 2.6 s  a scan sweep, the wordmark glitching in, the spec line typing out, and the
 *               wireframe car drawing itself line by line over a fading-in floor grid;
 *  2.3 - 5.1 s  "CAN NODES · LISTENING": the car's modules light up with their live message
 *               rates, then every signal is checked off group by group with its live value,
 *               ending in an "N OK · M TO CHECK" badge;
 *  4.8 - 5.7 s  the rev bar sweeps to the limiter and back;
 *  5.6 - 6.2 s  the dash fades in underneath.
 *
 * The short version (when the car is started with the app already open) is just the check
 * and the sweep. Any tap skips it.
 */
internal fun DashRenderer.boot(c: Canvas, area: RectF, s: Snapshot, cfg: DashConfig) {
    if (bootPending) { bootAt = s.now; bootPending = false; bootSkip = false }
    if (bootAt == 0L) { booting = false; return }
    val total = if (bootFull) DashRenderer.BOOT_MS else DashRenderer.CHECK_MS
    val el = s.now - bootAt
    if (el >= total || bootSkip) { bootAt = 0L; booting = false; bootSkip = false; return }
    booting = true
    // timeline position in seconds; the short version starts at the system check
    val p = el / 1000f + if (bootFull) 0f else 2.0f

    val fade = (1f - (p - 5.6f) / 0.6f).coerceIn(0f, 1f)
    c.saveLayerAlpha(area, (255 * fade).toInt())
    fill.color = t.bg
    c.drawRect(area, fill)
    particles(c, area, s.now)

    if (p < 2.8f) bootIntro(c, area, s, cfg, p)
    if (p in 2.3f..5.1f) systemCheck(c, area, s, cfg, p)
    if (p >= 4.8f) revSweep(c, area, cfg, p)

    label(c, "TAP TO SKIP", area.right - 10f * u, area.bottom - 8f * u, 7f * u, t.faint, Paint.Align.RIGHT)
    c.restore()
    newHits += RectF(area) to { bootSkip = true }
}

/** Slow drifting points of light, like the reel's ambience. */
private fun DashRenderer.particles(c: Canvas, area: RectF, now: Long) {
    for (i in 0 until 46) {
        val seed = i * 7919
        val x = area.left + ((seed * 13 % 1000) / 1000f) * area.width()
        val drift = ((now / 40 + seed) % 2000) / 2000f
        val y = area.bottom - drift * area.height()
        val tw = 0.5f + 0.5f * sin((now / 300.0 + i).toFloat())
        fill.color = withAlpha(t.primary, (40 + 90 * tw).toInt())
        c.drawCircle(x, y, (1.2f + (i % 3)) * u, fill)
    }
}

private fun DashRenderer.bootIntro(c: Canvas, area: RectF, s: Snapshot, cfg: DashConfig, p: Float) {
    val out = ((2.8f - p) / 0.4f).coerceIn(0f, 1f)
    c.saveLayerAlpha(area, (255 * out).toInt())
    val w = area.width()
    val h = area.height()

    // the car, drawing itself, on the right
    val stage = RectF(area.left + w * 0.38f, area.top + h * 0.12f, area.right - w * 0.02f, area.bottom - h * 0.08f)
    val reveal = ((p - 0.5f) / 1.7f).coerceIn(0f, 1f)
    if (reveal > 0f) drawCar(c, stage, s, cfg, live = true, callouts = false, reveal = reveal, gridAlpha = ((p - 0.3f) / 1.2f).coerceIn(0f, 1f))

    // wordmark, glitching in
    val wx = area.left + w * 0.07f
    val wy = area.top + h * 0.48f
    val size = min(h * 0.2f, w * 0.12f)
    val g = ((p - 0.15f) / 0.8f).coerceIn(0f, 1f)
    if (g > 0f) {
        val jitter = (1f - g) * size * 0.25f
        val slices = 6
        text.typeface = Fonts.display
        text.textSize = size
        text.letterSpacing = 0.04f
        text.textAlign = Paint.Align.LEFT
        val tw = text.measureText(cfg.car.wordmark)
        for (k in 0 until slices) {
            val top = wy - size * 0.8f + k * size * 0.8f / slices
            val off = if (g < 1f) sin((p * 37f + k * 11f)) * jitter else 0f
            c.save()
            c.clipRect(wx - 10f * u, top, wx + tw + 30f * u, top + size * 0.8f / slices + 1f)
            display(c, cfg.car.wordmark, wx + off, wy, size, if (k % 2 == 0 || g >= 1f) t.text else t.primary, glow = true)
            c.restore()
        }
        // underline that runs out under it
        val ul = ((p - 0.8f) / 0.5f).coerceIn(0f, 1f)
        fill.color = t.primary
        c.drawRect(wx, wy + size * 0.18f, wx + tw * ul, wy + size * 0.18f + 3f * u, fill)
        // the spec line types itself out
        val spec = "${cfg.car.chassis} · ${cfg.subtitle} · SICK DASH"
        val chars = ((p - 0.7f) / 0.8f * spec.length).toInt().coerceIn(0, spec.length)
        label(c, spec.take(chars) + if (chars < spec.length && (s.now / 120) % 2 == 0L) "▌" else "", wx, wy + size * 0.18f + 18f * u, 9f * u, t.dim, spacing = 0.24f)
        label(c, "SYSTEM BOOT", wx, wy - size * 0.95f, 8f * u, t.faint, spacing = 0.3f)
    }

    // the scan sweep down the screen at the very start
    if (p < 0.7f) {
        val y = area.top + area.height() * (p / 0.7f)
        fill.color = withAlpha(t.primary, 160)
        c.drawRect(area.left, y - 1.5f * u, area.right, y + 1.5f * u, fill)
        fill.color = withAlpha(t.primary, 30)
        c.drawRect(area.left, y - 26f * u, area.right, y, fill)
    }
    c.restore()
}

private fun DashRenderer.systemCheck(c: Canvas, area: RectF, s: Snapshot, cfg: DashConfig, p: Float) {
    val inA = ((p - 2.3f) / 0.3f).coerceIn(0f, 1f)
    val outA = ((5.1f - p) / 0.3f).coerceIn(0f, 1f)
    c.saveLayerAlpha(area, (255 * min(inA, outA)).toInt())
    val pad = 14f * u
    val r = RectF(area.left + pad, area.top + pad, area.right - pad, area.bottom - pad)
    display(c, "CAN NODES · LISTENING", r.left, r.top + 12f * u, 12f * u, t.text, glow = true)
    label(c, "${cfg.car.chassis} · ${if (cfg.source == Source.DEMO) "SIMULATOR" else VehicleData.linkMode} · ${s.framesPerSecond} MSG/S",
        r.right, r.top + 10f * u, 7.5f * u, t.dim, Paint.Align.RIGHT)

    // the modules, with their live message rates
    val nodes: List<Pair<String, Int?>> = VehicleData.nodes.takeIf { it.isNotEmpty() }?.map { (name, ids) ->
        var hz = 0f
        var seen = false
        for (id in ids) VehicleData.sniffer[id]?.let { e ->
            if (s.now - e.lastSeen < 2000 && e.periodMs > 0f) { hz += 1000f / e.periodMs; seen = true }
        }
        name to (if (seen) hz.toInt() else null)
    } ?: listOf((if (cfg.source == Source.DEMO) "SIMULATOR" else "ECM · OBD") to s.framesPerSecond.takeIf { it > 0 })
    val nodeTop = r.top + 22f * u
    val nodeH = 34f * u
    val gap = 6f * u
    val nw = min(110f * u, (r.width() - gap * (nodes.size - 1)) / nodes.size)
    for ((i, n) in nodes.withIndex()) {
        val on = p > 2.5f + i * 0.12f
        val b = RectF(r.left + i * (nw + gap), nodeTop, r.left + i * (nw + gap) + nw, nodeTop + nodeH)
        panel(c, b, if (on && n.second != null) t.text else t.line, if (on && n.second != null) 1.3f else 0.8f)
        label(c, n.first, b.left + 6f * u, b.top + 11f * u, 7.5f * u, if (on) t.text else t.faint)
        display(c, if (!on) "…" else n.second?.let { "$it/S" } ?: "—", b.left + 6f * u, b.bottom - 6f * u, 12f * u,
            if (on && n.second != null) t.text else t.faint, glow = on && n.second != null)
        val bar = if (on) ((p - 2.5f - i * 0.12f) / 0.4f).coerceIn(0f, 1f) else 0f
        fill.color = if (n.second != null) t.text else t.faint
        c.drawRect(b.right - 22f * u, b.bottom - 9f * u, b.right - 22f * u + 16f * u * bar, b.bottom - 6f * u, fill)
    }

    // the signal checklist, group by group
    val listTop = nodeTop + nodeH + 14f * u
    val listBottom = r.bottom - 30f * u
    val groups = ChannelInfo.Group.entries
    val colW = (r.width() - gap * (groups.size - 1)) / groups.size
    var shown = 0
    var ok = 0
    var check = 0
    var total = 0
    val rowsPer = ChannelInfo.ALL.groupBy { it.group }
    val step = 1.9f / ChannelInfo.ALL.size
    var order = 0
    for ((gi, grp) in groups.withIndex()) {
        val x = r.left + gi * (colW + gap)
        label(c, grp.title, x, listTop, 8f * u, t.dim)
        stroke.color = t.line; stroke.strokeWidth = max(1f, 0.8f * u)
        c.drawLine(x, listTop + 4f * u, x + colW, listTop + 4f * u, stroke)
        val rows = rowsPer[grp].orEmpty()
        val rowH = min(15f * u, (listBottom - listTop - 10f * u) / max(1, rows.size))
        for ((ri, info) in rows.withIndex()) {
            val appear = 2.6f + order * step
            order++
            val v = s[info.channel, 5000]
            total++
            if (v != null) { if (ChannelInfo.needsCheck(info.channel, v, s)) check++ else ok++ }
            if (p < appear) continue
            shown++
            val y = listTop + 10f * u + ri * rowH + rowH * 0.7f
            val ts = min(7.5f * u, rowH * 0.62f)
            label(c, info.name, x, y, ts, if (v != null) t.text else t.faint)
            val value = v?.let { ChannelInfo.format(info.channel, it, cfg.imperial) } ?: "—"
            val warn = v != null && ChannelInfo.needsCheck(info.channel, v, s)
            label(c, value, x + colW - 26f * u, y, ts, if (warn) t.secondary else if (v != null) t.text else t.faint, Paint.Align.RIGHT)
            // status block
            val sb = RectF(x + colW - 20f * u, y - ts * 0.8f, x + colW, y + ts * 0.15f)
            if (v != null) {
                fill.color = if (warn) t.secondary else t.text
                c.drawRect(sb, fill)
            } else {
                stroke.color = t.faint; c.drawRect(sb, stroke)
            }
        }
    }

    // progress and the verdict
    val prog = ((p - 2.5f) / 2.1f).coerceIn(0f, 1f)
    val by = r.bottom - 14f * u
    fill.color = withAlpha(t.faint, 120)
    c.drawRect(r.left, by, r.right, by + 3f * u, fill)
    fill.color = t.text
    c.drawRect(r.left, by, r.left + r.width() * prog, by + 3f * u, fill)
    if (p > 4.4f) {
        val verdict = "$ok OK · $check TO CHECK"
        val vw = textWidth(verdict, Fonts.mono, 8f * u, 0.16f) + 16f * u
        val vb = RectF(r.centerX() - vw / 2, by - 22f * u, r.centerX() + vw / 2, by - 6f * u)
        val col = if (check > 0) t.secondary else t.text
        fill.color = withAlpha(col, 40); c.drawRect(vb, fill)
        stroke.color = col; stroke.strokeWidth = 1.2f * u; c.drawRect(vb, stroke)
        label(c, verdict, vb.centerX(), vb.centerY() + 3f * u, 8f * u, col, Paint.Align.CENTER)
    }
    c.restore()
}

private fun DashRenderer.revSweep(c: Canvas, area: RectF, cfg: DashConfig, p: Float) {
    // up to the limiter and back, like a cluster's needle sweep
    val k = ((p - 4.8f) / 0.9f).coerceIn(0f, 1f)
    val f = if (k < 0.5f) k * 2f else (1f - k) * 2f
    // the checklist steps back so the sweep has the screen
    fill.color = withAlpha(t.bg, (230 * ((p - 4.8f) / 0.2f).coerceIn(0f, 1f)).toInt())
    c.drawRect(area, fill)
    val w = area.width() * 0.7f
    val r = RectF(area.centerX() - w / 2, area.centerY() - 10f * u, area.centerX() + w / 2, area.centerY() + 10f * u)
    segments(c, r, 40, f) { x -> if (x * cfg.maxRpm >= cfg.shiftRpm) t.primary else if (x * cfg.maxRpm >= cfg.shiftRpm - 1500) t.secondary else t.text }
    display(c, "%,d".format((f * cfg.maxRpm).toInt()), area.centerX(), r.top - 10f * u, 22f * u, t.text, Paint.Align.CENTER, glow = true)
    label(c, if (k > 0.55f) "READY" else "SWEEP", area.centerX(), r.bottom + 18f * u, 9f * u, if (k > 0.55f) t.primary else t.dim, Paint.Align.CENTER, 0.4f)
    if (abs(f - 1f) < 0.08f) {
        fill.color = withAlpha(t.primary, 60)
        c.drawRect(area, fill)
    }
}
