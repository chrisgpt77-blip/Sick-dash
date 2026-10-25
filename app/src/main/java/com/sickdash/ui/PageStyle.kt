package com.sickdash.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.sickdash.data.DashConfig
import com.sickdash.data.Events
import com.sickdash.data.Snapshot
import kotlin.math.max
import kotlin.math.min

// =============================================================================================
// STYLE: themes, colour pickers, animation previews and the Drive page's tiles
// =============================================================================================

internal fun DashRenderer.stylePage(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig, dt: Float) {
    val body = pageFrame(c, r, "STYLE · LOOK AND FEEL", "TAP TO APPLY · SAVED")
    val gap = 6f * u
    val leftW = body.width() * 0.52f
    val left = RectF(body.left, body.top, body.left + leftW, body.bottom)
    val right = RectF(left.right + 10f * u, body.top, body.right, body.bottom)
    val heading = 7f * u

    // ---- theme presets
    label(c, "THEMES", left.left, left.top + heading, heading, t.dim)
    val presets = RectF(left.left, left.top + heading + 4f * u, left.right, left.top + left.height() * 0.4f)
    for ((i, b) in cells(presets, Theme.ALL.size, 3, 4f * u).withIndex()) {
        val th = Theme.ALL[i]
        val active = th.key == cfg.themeKey
        fill.color = th.bg; c.drawRect(b, fill)
        stroke.color = if (active) t.text else t.line
        stroke.strokeWidth = if (active) 1.8f * u else max(1f, 0.8f * u)
        c.drawRect(b, stroke)
        val sw = min(b.height() * 0.5f, 10f * u)
        fill.color = th.primary; c.drawRect(b.left + 5f * u, b.centerY() - sw / 2, b.left + 5f * u + sw, b.centerY() + sw / 2, fill)
        fill.color = th.secondary; c.drawRect(b.left + 7f * u + sw, b.centerY() - sw / 2, b.left + 7f * u + sw * 2, b.centerY() + sw / 2, fill)
        label(c, th.name.uppercase(), b.left + 10f * u + sw * 2, b.centerY() + 3f * u, min(7.5f * u, b.height() * 0.38f), th.text, spacing = 0.08f)
        newHits += RectF(b) to { onSetting?.invoke("theme", th.key) }
    }

    // ---- colour pickers: a row of swatches per role, plus "theme" to clear the override
    val pickers = RectF(left.left, presets.bottom + gap, left.right, left.bottom)
    val roles = listOf(
        Triple("PRIMARY", "color_primary", cfg.colorPrimary),
        Triple("SECONDARY", "color_secondary", cfg.colorSecondary),
        Triple("CAR LINES", "color_car", cfg.colorCar),
    )
    val rowH = pickers.height() / roles.size
    for ((i, role) in roles.withIndex()) {
        val (name, key, current) = role
        val y0 = pickers.top + i * rowH
        label(c, "$name · ${if (current == null) "THEME COLOUR" else "#%06X".format(current and 0xFFFFFF)}", pickers.left, y0 + heading, heading, t.dim)
        val row = RectF(pickers.left, y0 + heading + 4f * u, pickers.right, y0 + rowH - 4f * u)
        val n = Theme.SWATCHES.size + 1
        val boxes = cells(row, n, n, 3f * u)
        // first box: back to the theme's own colour
        val reset = boxes[0]
        val rs = min(reset.width(), reset.height()) / 2
        val rq = RectF(reset.centerX() - rs, reset.centerY() - rs, reset.centerX() + rs, reset.centerY() + rs)
        fill.color = t.panel; c.drawRect(rq, fill)
        stroke.color = if (current == null) t.text else t.dim
        stroke.strokeWidth = if (current == null) 1.8f * u else max(1f, 0.8f * u)
        c.drawRect(rq, stroke)
        c.drawLine(rq.left + 3f * u, rq.bottom - 3f * u, rq.right - 3f * u, rq.top + 3f * u, stroke)
        newHits += RectF(reset) to { onSetting?.invoke(key, null) }
        for ((j, colour) in Theme.SWATCHES.withIndex()) {
            val b = boxes[j + 1]
            val side = min(b.width(), b.height())
            val sq = RectF(b.centerX() - side / 2, b.centerY() - side / 2, b.centerX() + side / 2, b.centerY() + side / 2)
            fill.color = colour; c.drawRect(sq, fill)
            if (current != null && (current and 0xFFFFFF) == (colour and 0xFFFFFF)) {
                stroke.color = t.text; stroke.strokeWidth = 1.8f * u
                c.drawRect(sq.left - 2f * u, sq.top - 2f * u, sq.right + 2f * u, sq.bottom + 2f * u, stroke)
            }
            newHits += RectF(b) to { onSetting?.invoke(key, "#%06X".format(colour and 0xFFFFFF)) }
        }
    }

    // ---- live preview
    val stage = RectF(right.left, right.top, right.right, right.top + right.height() * 0.4f)
    panel(c, stage)
    wheelSpin -= 6f * dt
    drawCar(c, RectF(stage.left + 2f * u, stage.top + 2f * u, stage.right - 2f * u, stage.bottom - 2f * u), s, cfg, live = true, callouts = false)
    label(c, "PREVIEW", stage.left + 6f * u, stage.top + 10f * u, 6.5f * u, t.dim)

    // ---- animations: play any of them on demand
    val animTop = stage.bottom + gap
    label(c, "PLAY AN ANIMATION", right.left, animTop + heading, heading, t.dim)
    val anims: List<Pair<String, () -> Unit>> = listOf(
        "BOOT" to { playBoot(true) },
        "CHECK" to { playBoot(false) },
        "START" to { preview(Events.Kind.START) },
        "SHIFT" to { preview(Events.Kind.SHIFT) },
        "LAUNCH" to { preview(Events.Kind.LAUNCH) },
        "SLIP" to { preview(Events.Kind.SLIP) },
        "LIMIT" to { preview(Events.Kind.OVER_REV) },
        "LOCK" to { preview(Events.Kind.LOCK) },
        "REC" to { preview(Events.Kind.RECORD) },
        "0-60" to { preview(Events.Kind.RESULT) },
    )
    val animArea = RectF(right.left, animTop + heading + 4f * u, right.right, animTop + heading + 4f * u + right.height() * 0.22f)
    for ((i, b) in cells(animArea, anims.size, 5, 3f * u).withIndex()) {
        val (name, action) = anims[i]
        val strong = i < 2
        fill.color = if (strong) withAlpha(t.primary, 50) else t.panel; c.drawRect(b, fill)
        stroke.color = if (strong) t.primary else t.line; stroke.strokeWidth = max(1f, 0.8f * u); c.drawRect(b, stroke)
        label(c, name, b.centerX(), b.centerY() + 2.5f * u, min(7f * u, b.width() / (name.length * 0.85f + 1f)), if (strong) t.text else t.dim,
            Paint.Align.CENTER, 0.06f)
        newHits += RectF(b) to action
    }

    // ---- Drive page tiles: tap a slot to cycle what it shows
    val tilesTop = animArea.bottom + gap
    label(c, "MAIN DASH TILES · TAP TO CHANGE", right.left, tilesTop + heading, heading, t.dim)
    val tileArea = RectF(right.left, tilesTop + heading + 4f * u, right.right, right.bottom)
    for ((i, b) in cells(tileArea, cfg.tiles.size, 3, 3f * u).withIndex()) {
        val kind = cfg.tiles[i]
        panel(c, b)
        val ir = min(b.height() * 0.22f, 7f * u)
        drawIcon(c, kind, b.left + 5f * u + ir, b.centerY(), ir, t.text)
        label(c, tileTitle(kind), b.left + 10f * u + ir * 2, b.centerY() + 3f * u, min(7.5f * u, b.height() * 0.3f), t.text, spacing = 0.08f)
        label(c, "${i + 1}", b.right - 4f * u, b.top + 8f * u, 6f * u, t.faint, Paint.Align.RIGHT)
        newHits += RectF(b) to {
            val kinds = DashConfig.TILE_KINDS
            val next = kinds[(kinds.indexOf(kind) + 1) % kinds.size]
            onSetting?.invoke("dash_tiles", cfg.tiles.toMutableList().also { it[i] = next }.joinToString(","))
        }
    }
}
