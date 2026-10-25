package com.sickdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import com.sickdash.data.Navigation
import com.sickdash.data.NowPlaying
import kotlin.math.max
import kotlin.math.min

// =============================================================================================
// Google Maps' next turn and the phone's music, on the Drive page
// =============================================================================================

private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

/** The next turn, top-left of the Drive stage. */
internal fun DashRenderer.turnCard(c: Canvas, stage: RectF) {
    val turn = Navigation.current ?: return
    val w = min(stage.width() * 0.44f, 210f * u)
    val h = 56f * u
    val r = RectF(stage.left + 8f * u, stage.top + 8f * u, stage.left + 8f * u + w, stage.top + 8f * u + h)
    fill.color = withAlpha(t.bg, 225); c.drawRect(r, fill)
    stroke.color = t.text; stroke.strokeWidth = 1.2f * u; c.drawRect(r, stroke)
    fill.color = t.primary; c.drawRect(r.left, r.top, r.left + 3f * u, r.bottom, fill)

    val icon = RectF(r.left + 9f * u, r.top + 8f * u, r.left + 9f * u + h - 16f * u, r.bottom - 8f * u)
    val bmp = turn.icon
    if (bmp != null) drawTinted(c, bmp, icon, t.text) else turnArrow(c, turn.instruction, icon)

    val tx = icon.right + 9f * u
    val tw = r.right - tx - 6f * u
    val dist = turn.distance?.uppercase()
    if (dist != null) display(c, dist, tx, r.top + 19f * u, 15f * u, t.text, glow = true)
    val lines = wrap(turn.instruction.uppercase(), tw, 7f * u, if (dist != null) 2 else 3)
    var y = r.top + (if (dist != null) 31f else 15f) * u
    for (l in lines) { label(c, l, tx, y, 7f * u, if (dist != null) t.dim else t.text, spacing = 0.08f); y += 9f * u }
    turn.eta?.let { label(c, ellipsize(it.uppercase(), tw, 6f * u), tx, r.bottom - 5f * u, 6f * u, t.faint, spacing = 0.08f) }
    label(c, if (turn.demo) "DEMO ROUTE" else "GOOGLE MAPS", r.right - 5f * u, r.top + 9f * u, 5.5f * u, t.faint, Paint.Align.RIGHT, 0.1f)
}

/** Now playing with transport buttons, bottom-left of the Drive stage. */
internal fun DashRenderer.mediaCard(c: Canvas, stage: RectF) {
    val tr = NowPlaying.current ?: return
    val w = min(stage.width() * 0.5f, 240f * u)
    val h = 46f * u
    val r = RectF(stage.left + 8f * u, stage.bottom - 20f * u - h, stage.left + 8f * u + w, stage.bottom - 20f * u)
    fill.color = withAlpha(t.bg, 225); c.drawRect(r, fill)
    stroke.color = t.line; stroke.strokeWidth = max(1f, 0.8f * u); c.drawRect(r, stroke)

    val art = RectF(r.left + 5f * u, r.top + 5f * u, r.left + h - 5f * u, r.bottom - 5f * u)
    val bmp = tr.art
    if (bmp != null) c.drawBitmap(bmp, null, art, bitmapPaint)
    else {
        fill.color = t.panel; c.drawRect(art, fill)
        drawIcon(c, "music", art.centerX(), art.centerY(), art.width() * 0.3f, t.dim)
    }

    // buttons: previous, play / pause, next
    val bs = 20f * u
    val by = r.top + 6f * u
    val buttons = listOf(
        "prev" to { NowPlaying.previous() },
        (if (tr.playing) "pause" else "play") to { NowPlaying.playPause() },
        "next" to { NowPlaying.next() },
    )
    var bx = r.right - 5f * u - bs * buttons.size
    for ((kind, action) in buttons) {
        val b = RectF(bx, by, bx + bs, by + bs)
        if (kind == "play" || kind == "pause") { fill.color = t.text; c.drawRect(b, fill) }
        transportIcon(c, kind, b, if (kind == "play" || kind == "pause") t.bg else t.text)
        newHits += b to action
        bx += bs
    }

    val tx = art.right + 7f * u
    val tw = r.right - 5f * u - bs * 3 - tx - 4f * u
    display(c, ellipsizeDisplay(tr.title.uppercase(), tw, 9f * u), tx, r.top + 15f * u, 9f * u, t.text)
    tr.artist?.let { label(c, ellipsize(it.uppercase(), tw, 6.5f * u), tx, r.top + 26f * u, 6.5f * u, t.dim, spacing = 0.08f) }
    // progress
    val bar = RectF(tx, r.bottom - 9f * u, r.right - 6f * u, r.bottom - 7f * u)
    fill.color = t.line; c.drawRect(bar, fill)
    if (tr.durationMs > 0) {
        val f = (tr.position().toFloat() / tr.durationMs).coerceIn(0f, 1f)
        fill.color = t.primary; c.drawRect(bar.left, bar.top, bar.left + bar.width() * f, bar.bottom, fill)
        label(c, "${mmss(tr.position())} / ${mmss(tr.durationMs)}", r.right - 6f * u, bar.top - 3f * u, 5.5f * u, t.faint, Paint.Align.RIGHT, 0.06f)
    }
    label(c, appName(tr.app, tr.demo), tx, bar.top - 3f * u, 5.5f * u, t.faint, spacing = 0.1f)
}

private fun mmss(ms: Long) = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

private fun appName(pkg: String, demo: Boolean) = when {
    demo -> "DEMO"
    pkg == "com.spotify.music" -> "SPOTIFY"
    pkg.contains("youtube") -> "YOUTUBE MUSIC"
    pkg.contains("pandora") -> "PANDORA"
    pkg.contains("amazon") -> "AMAZON MUSIC"
    pkg.contains("apple") -> "APPLE MUSIC"
    else -> pkg.substringAfterLast('.').uppercase()
}

/** The turn as one line for the header: "0.3 MI · TURN RIGHT ONTO MAIN ST". */
internal fun turnLine(): String? = Navigation.current?.let { t -> listOfNotNull(t.distance, t.instruction).joinToString(" · ").uppercase() }

private fun DashRenderer.drawTinted(c: Canvas, bmp: Bitmap, r: RectF, colour: Int) {
    // Maps draws the manoeuvre as a shape on transparency; tint it to the theme. An opaque image is drawn as it is.
    bitmapPaint.colorFilter = if (bmp.hasAlpha()) PorterDuffColorFilter(colour, PorterDuff.Mode.SRC_IN) else null
    val side = min(r.width(), r.height())
    val k = side / max(bmp.width, bmp.height)
    val w = bmp.width * k; val h = bmp.height * k
    c.drawBitmap(bmp, null, RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2), bitmapPaint)
    bitmapPaint.colorFilter = null
}

/** A turn arrow worked out from the instruction, for when there's no icon. */
private fun DashRenderer.turnArrow(c: Canvas, instruction: String, r: RectF) {
    val s = instruction.lowercase()
    val cx = r.centerX(); val cy = r.centerY(); val k = min(r.width(), r.height()) / 2
    stroke.color = t.text; stroke.strokeWidth = 3f * u; stroke.strokeCap = Paint.Cap.ROUND
    path.reset()
    val dir = when {
        "u-turn" in s || "make a u" in s -> 0
        "roundabout" in s -> 3
        "left" in s -> -1
        "right" in s -> 1
        else -> 2
    }
    when (dir) {
        -1, 1 -> {
            path.moveTo(cx - dir * k * 0.3f, cy + k * 0.9f); path.lineTo(cx - dir * k * 0.3f, cy - k * 0.1f)
            path.lineTo(cx + dir * k * 0.7f, cy - k * 0.1f)
            c.drawPath(path, stroke)
            path.reset(); path.moveTo(cx + dir * k * 0.35f, cy - k * 0.45f); path.lineTo(cx + dir * k * 0.75f, cy - k * 0.1f)
            path.lineTo(cx + dir * k * 0.35f, cy + k * 0.25f)
        }
        0 -> {
            path.moveTo(cx + k * 0.4f, cy + k * 0.9f); path.lineTo(cx + k * 0.4f, cy - k * 0.3f)
            path.arcTo(RectF(cx - k * 0.4f, cy - k * 0.7f, cx + k * 0.4f, cy + k * 0.1f), 0f, -180f, false)
            path.lineTo(cx - k * 0.4f, cy + k * 0.3f)
            c.drawPath(path, stroke)
            path.reset(); path.moveTo(cx - k * 0.75f, cy - k * 0.05f); path.lineTo(cx - k * 0.4f, cy + k * 0.35f); path.lineTo(cx - k * 0.05f, cy - k * 0.05f)
        }
        3 -> {
            c.drawCircle(cx, cy - k * 0.1f, k * 0.4f, stroke)
            path.moveTo(cx, cy + k * 0.9f); path.lineTo(cx, cy + k * 0.3f)
            c.drawPath(path, stroke)
            path.reset(); path.moveTo(cx + k * 0.28f, cy - k * 0.38f); path.lineTo(cx + k * 0.8f, cy - k * 0.85f)
        }
        else -> {
            path.moveTo(cx, cy + k * 0.9f); path.lineTo(cx, cy - k * 0.8f)
            c.drawPath(path, stroke)
            path.reset(); path.moveTo(cx - k * 0.4f, cy - k * 0.4f); path.lineTo(cx, cy - k * 0.85f); path.lineTo(cx + k * 0.4f, cy - k * 0.4f)
        }
    }
    c.drawPath(path, stroke)
    stroke.strokeCap = Paint.Cap.BUTT
}

private fun DashRenderer.transportIcon(c: Canvas, kind: String, b: RectF, colour: Int) {
    fill.color = colour
    val cx = b.centerX(); val cy = b.centerY(); val k = b.width() * 0.22f
    path.reset()
    when (kind) {
        "play" -> { path.moveTo(cx - k * 0.7f, cy - k); path.lineTo(cx + k, cy); path.lineTo(cx - k * 0.7f, cy + k); path.close() }
        "pause" -> { c.drawRect(cx - k * 0.8f, cy - k, cx - k * 0.25f, cy + k, fill); c.drawRect(cx + k * 0.25f, cy - k, cx + k * 0.8f, cy + k, fill); return }
        "next" -> { path.moveTo(cx - k, cy - k); path.lineTo(cx + k * 0.4f, cy); path.lineTo(cx - k, cy + k); path.close()
            c.drawRect(cx + k * 0.5f, cy - k, cx + k, cy + k, fill) }
        "prev" -> { path.moveTo(cx + k, cy - k); path.lineTo(cx - k * 0.4f, cy); path.lineTo(cx + k, cy + k); path.close()
            c.drawRect(cx - k, cy - k, cx - k * 0.5f, cy + k, fill) }
    }
    c.drawPath(path, fill)
}

/** Word-wraps [s] into at most [maxLines] lines of the mono label font. */
private fun DashRenderer.wrap(s: String, width: Float, size: Float, maxLines: Int): List<String> {
    val out = ArrayList<String>()
    var line = ""
    for (word in s.split(' ')) {
        val next = if (line.isEmpty()) word else "$line $word"
        if (textWidth(next, Fonts.mono, size, 0.08f) <= width || line.isEmpty()) line = next
        else { out += line; line = word }
    }
    if (line.isNotEmpty()) out += line
    if (out.size <= maxLines) return out
    return out.take(maxLines - 1) + ellipsize(out.drop(maxLines - 1).joinToString(" "), width, size)
}

private fun DashRenderer.ellipsizeDisplay(s: String, maxW: Float, size: Float): String {
    if (textWidth(s, Fonts.display, size, 0.04f) <= maxW) return s
    var x = s
    while (x.length > 3 && textWidth("$x…", Fonts.display, size, 0.04f) > maxW) x = x.dropLast(1)
    return "$x…"
}
