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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// =============================================================================================
// Link tracking: notices the car powering off and on
// =============================================================================================

internal fun DashRenderer.trackLink(s: Snapshot) {
    val live = s[Channel.RPM, 3000] != null
    if (live) {
        // the car came back after being off for a while: run the system check, as at ignition
        if (!wasLive && offlineSince != 0L && s.now - offlineSince > 10_000 && !booting) playBoot(full = false)
        wasLive = true
        lastLiveAt = s.now
        offlineSince = 0L
        poweredOffDismissed = false
    } else {
        if (wasLive) linkLostAt = s.now
        if (offlineSince == 0L) offlineSince = s.now
        wasLive = false
    }
}

internal fun previewTitle(kind: Events.Kind): String = when (kind) {
    Events.Kind.BOOT -> "BOOT"
    Events.Kind.START -> "ENGINE START"
    Events.Kind.SHIFT -> "3 → 2 · PERFECT"
    Events.Kind.LAUNCH -> "LAUNCH ARMED"
    Events.Kind.RESULT -> "0-60 MPH · 5.94 S"
    Events.Kind.SLIP -> "CLUTCH SLIP"
    Events.Kind.OVER_REV -> "OVER-REV"
    Events.Kind.WARNING -> "LOW FUEL"
    Events.Kind.LOCK -> "LOCKED"
    Events.Kind.RECORD -> "RECORDING"
    Events.Kind.MEDIA -> "NOW PLAYING"
}

// =============================================================================================
// Event pop-ups
// =============================================================================================

internal fun DashRenderer.eventBanner(c: Canvas, area: RectF, s: Snapshot) {
    val ev = Events.current(s.now) ?: return
    val el = (s.now - ev.at).toFloat()
    val inT = (el / 220f).coerceIn(0f, 1f)
    val outT = ((2200f - el) / 300f).coerceIn(0f, 1f)
    val k = min(inT, outT)
    val warn = ev.kind == Events.Kind.SLIP || ev.kind == Events.Kind.OVER_REV || ev.kind == Events.Kind.WARNING
    val accent = if (warn) t.secondary else t.primary
    val w = min(area.width() * 0.62f, 300f * u)
    val h = 48f * u
    val cx = area.centerX()
    val top = area.top + 12f * u - (1f - inT) * 30f * u
    val r = RectF(cx - w / 2, top, cx + w / 2, top + h)
    c.saveLayerAlpha(RectF(area.left, area.top, area.right, area.bottom), (255 * k).toInt())
    fill.color = withAlpha(t.bg, 235)
    c.drawRect(r, fill)
    stroke.color = accent
    stroke.strokeWidth = (if (ev.strong) 1.8f else 1.2f) * u
    c.drawRect(r, stroke)
    // a glow halo for the big moments
    if (ev.strong) {
        stroke.color = withAlpha(accent, 50)
        stroke.strokeWidth = 7f * u
        c.drawRect(r, stroke)
    }
    fill.color = accent
    c.drawRect(r.left, r.top, r.left + 4f * u, r.bottom, fill)
    // a scanline that sweeps across as it arrives
    if (el < 500f) {
        val sx = r.left + r.width() * (el / 500f)
        fill.color = withAlpha(t.text, 120)
        c.drawRect(sx - 2f * u, r.top, sx + 2f * u, r.bottom, fill)
    }
    display(c, ev.title, cx, r.top + h * 0.52f, 16f * u, if (warn) t.secondary else t.text, Paint.Align.CENTER, glow = true)
    if (ev.sub.isNotEmpty()) label(c, ev.sub, cx, r.top + h * 0.84f, 7.5f * u, t.dim, Paint.Align.CENTER)
    // remaining time
    fill.color = withAlpha(accent, 160)
    c.drawRect(r.left, r.bottom - 2f * u, r.left + r.width() * (1f - el / 2200f).coerceIn(0f, 1f), r.bottom, fill)
    c.restore()
}

// =============================================================================================
// Task switcher: every page as a card
// =============================================================================================

internal fun DashRenderer.pageSummary(p: DashRenderer.Page, s: Snapshot, cfg: DashConfig): String {
    fun spd(k: Float) = if (cfg.imperial) "${(k * DashRenderer.KPH_TO_MPH).roundToInt()} MPH" else "${k.roundToInt()} KM/H"
    return when (p) {
        DashRenderer.Page.DRIVE -> s[Channel.SPEED]?.let { "${spd(it)} · GEAR ${gearLabel(gearNumber(s, cfg))}" } ?: "WIREFRAME · LIVE CAR"
        DashRenderer.Page.BODY -> listOfNotNull(
            s[Channel.DOOR_LOCK]?.let { ChannelInfo.format(Channel.DOOR_LOCK, it, cfg.imperial) },
            s[Channel.FUEL]?.let { "FUEL ${it.roundToInt()}%" },
        ).joinToString(" · ").ifEmpty { "DOORS · LIGHTS · TYRES" }
        DashRenderer.Page.HANDLING -> "PEAK %.2f G".format(SessionStats.peakG)
        DashRenderer.Page.DIFF -> if (s[Channel.WHEEL_RL] != null) "REAR SPLIT %.1f".format((s[Channel.WHEEL_RL] ?: 0f) - (s[Channel.WHEEL_RR] ?: 0f)) else "WHEEL SPEEDS"
        DashRenderer.Page.SHIFT -> ShiftTracker.last?.let { "LAST ${it.score} · ${it.rating}" } ?: "REV-MATCH SCORE"
        DashRenderer.Page.CLUTCH -> "HEAT ${temps(cfg)(Analytics.clutchHeatC)} · ${Analytics.slipEvents} SLIPS"
        DashRenderer.Page.THROTTLE -> s[Channel.THROTTLE]?.let { "PEDAL ${it.roundToInt()}%" } ?: "PEDAL TRACES"
        DashRenderer.Page.ENGINE -> s[Channel.COOLANT]?.let { "COOLANT ${temps(cfg)(it)}" } ?: "LIVE TRACES"
        DashRenderer.Page.DYNO -> if (Analytics.peakKw > 0f) "PEAK ${(Analytics.peakKw * 1.341f).roundToInt()} WHP" else "DO A PULL"
        DashRenderer.Page.VDC -> balanceWord(Analytics.balance)
        DashRenderer.Page.TIMERS -> Analytics.zeroTo60.best?.let { "0-60 %.2f S".format(it) } ?: Analytics.runState
        DashRenderer.Page.SYSTEMS -> "${Analytics.verifiedSignals} / ${Analytics.liveSignals} VERIFIED"
        DashRenderer.Page.BUS -> "${VehicleData.sniffer.size} IDS · ${s.framesPerSecond}/S"
        DashRenderer.Page.STYLE -> t.name.uppercase()
    }
}

internal fun balanceWord(b: Float) = when {
    b > 12f -> "DRIFT"
    b > 4f -> "OVERSTEER"
    b < -4f -> "UNDERSTEER"
    else -> "NEUTRAL"
}

internal fun DashRenderer.taskSwitcher(c: Canvas, area: RectF, s: Snapshot, cfg: DashConfig) {
    val top = area.top + 34f * u
    val r = RectF(area.left + 8f * u, top, area.right - 8f * u, area.bottom - 8f * u)
    fill.color = withAlpha(t.bg, 240)
    c.drawRect(area.left, top - 4f * u, area.right, area.bottom, fill)
    newHits += RectF(area) to { switcherOpen = false }
    display(c, "PAGES · TASK SWITCHER", r.left + 4f * u, r.top + 12f * u, 12f * u, t.text, glow = true)
    label(c, "TAP A PAGE · TAP OUTSIDE TO CLOSE", r.right - 4f * u, r.top + 10f * u, 7f * u, t.dim, Paint.Align.RIGHT)
    val pages = DashRenderer.Page.entries
    val cols = if (r.width() > r.height() * 1.3f) 5 else 3
    val rows = (pages.size + cols - 1) / cols
    val gap = 6f * u
    val grid = RectF(r.left, r.top + 22f * u, r.right, r.bottom)
    val cw = (grid.width() - gap * (cols - 1)) / cols
    val ch = (grid.height() - gap * (rows - 1)) / rows
    for ((i, p) in pages.withIndex()) {
        val x = grid.left + (i % cols) * (cw + gap)
        val y = grid.top + (i / cols) * (ch + gap)
        val b = RectF(x, y, x + cw, y + ch)
        val active = p == page
        panel(c, b, if (active) t.primary else t.line, if (active) 1.6f else 0.8f)
        val ir = minOf(ch * 0.16f, cw * 0.12f, 18f * u)
        drawIcon(c, iconFor(p), b.left + 10f * u + ir, b.top + 10f * u + ir, ir, if (active) t.primary else t.dim)
        display(c, p.title.uppercase(), b.left + 8f * u, b.bottom - min(ch * 0.3f, 22f * u), min(ch * 0.2f, 14f * u), t.text, glow = active)
        label(c, ellipsize(pageSummary(p, s, cfg), b.width() - 14f * u, min(ch * 0.11f, 7.5f * u)),
            b.left + 8f * u, b.bottom - 6f * u, min(ch * 0.11f, 7.5f * u), t.dim)
        newHits += b to { page = p; switcherOpen = false }
    }
}

internal fun DashRenderer.ellipsize(str: String, maxW: Float, size: Float): String {
    if (textWidth(str, Fonts.mono, size, 0.16f) <= maxW) return str
    var x = str
    while (x.length > 3 && textWidth("$x…", Fonts.mono, size, 0.16f) > maxW) x = x.dropLast(1)
    return "$x…"
}

internal fun iconFor(p: DashRenderer.Page): String = when (p) {
    DashRenderer.Page.DRIVE -> "vehicle"
    DashRenderer.Page.BODY -> "body"
    DashRenderer.Page.HANDLING -> "g"
    DashRenderer.Page.DIFF -> "diff"
    DashRenderer.Page.SHIFT -> "shift"
    DashRenderer.Page.CLUTCH -> "clutch"
    DashRenderer.Page.THROTTLE -> "throttle"
    DashRenderer.Page.ENGINE -> "engine"
    DashRenderer.Page.DYNO -> "dyno"
    DashRenderer.Page.VDC -> "vdc"
    DashRenderer.Page.TIMERS -> "timers"
    DashRenderer.Page.SYSTEMS -> "systems"
    DashRenderer.Page.BUS -> "bus"
    DashRenderer.Page.STYLE -> "style"
}

/** Small line icons in the HUD style. */
internal fun DashRenderer.drawIcon(c: Canvas, kind: String, cx: Float, cy: Float, r: Float, colour: Int) {
    stroke.color = colour
    stroke.strokeWidth = max(1.2f, r * 0.12f)
    stroke.strokeCap = Paint.Cap.ROUND
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) = c.drawLine(cx + x0 * r, cy + y0 * r, cx + x1 * r, cy + y1 * r, stroke)
    when (kind) {
        "vehicle", "body" -> { // steering wheel / car
            if (kind == "vehicle") {
                c.drawCircle(cx, cy, r, stroke); c.drawCircle(cx, cy, r * 0.25f, stroke)
                line(-0.25f, 0f, -1f, 0f); line(0.25f, 0f, 1f, 0f); line(0f, 0.25f, 0f, 1f)
            } else {
                line(-1f, 0.3f, 1f, 0.3f); line(-1f, 0.3f, -0.8f, -0.1f); line(0.8f, -0.1f, 1f, 0.3f)
                line(-0.8f, -0.1f, -0.35f, -0.6f); line(-0.35f, -0.6f, 0.4f, -0.6f); line(0.4f, -0.6f, 0.8f, -0.1f)
                c.drawCircle(cx - 0.55f * r, cy + 0.45f * r, r * 0.22f, stroke); c.drawCircle(cx + 0.55f * r, cy + 0.45f * r, r * 0.22f, stroke)
            }
        }
        "timers" -> { c.drawCircle(cx, cy + 0.1f * r, r * 0.85f, stroke); line(0f, -0.75f, 0f, -1f); line(-0.25f, -1f, 0.25f, -1f); line(0f, 0.1f, 0.4f, -0.3f) }
        "clutch" -> {
            c.drawCircle(cx, cy, r, stroke); c.drawCircle(cx, cy, r * 0.42f, stroke)
            for (i in 0 until 6) { val a = i * PI / 3; line((0.55f * cos(a)).toFloat(), (0.55f * sin(a)).toFloat(), (0.8f * cos(a)).toFloat(), (0.8f * sin(a)).toFloat()) }
        }
        "dyno" -> { for ((i, h) in listOf(0.4f, 0.8f, 1.2f, 1.7f).withIndex()) { val x = -0.75f + i * 0.5f; line(x, 0.9f, x, 0.9f - h) } }
        "vdc" -> { line(-0.8f, -0.2f, 0.8f, -0.2f); line(-0.8f, -0.2f, -0.6f, -0.7f); line(0.6f, -0.7f, 0.8f, -0.2f); line(-0.6f, -0.7f, 0.6f, -0.7f)
            for (k in listOf(-0.5f, 0.5f)) { line(k - 0.2f, 0.3f, k, 0.55f); line(k, 0.55f, k - 0.2f, 0.8f); line(k - 0.2f, 0.8f, k, 1.0f) } }
        "shift" -> { line(-0.7f, -0.8f, -0.7f, 0.8f); line(0f, -0.8f, 0f, 0.8f); line(0.7f, -0.8f, 0.7f, 0.8f); line(-0.7f, 0f, 0.7f, 0f) }
        "diff" -> { c.drawCircle(cx, cy, r * 0.35f, stroke); line(-1f, 0f, -0.35f, 0f); line(0.35f, 0f, 1f, 0f); line(-1f, -0.6f, -1f, 0.6f); line(1f, -0.6f, 1f, 0.6f) }
        "tyres" -> { c.drawCircle(cx, cy, r, stroke); c.drawCircle(cx, cy, r * 0.5f, stroke) }
        "fuel" -> { c.drawRect(cx - 0.6f * r, cy - 0.8f * r, cx + 0.2f * r, cy + 0.9f * r, stroke); line(0.2f, -0.3f, 0.7f, 0f); line(0.7f, 0f, 0.7f, 0.6f) }
        "engine", "throttle" -> { c.drawRect(cx - 0.7f * r, cy - 0.5f * r, cx + 0.7f * r, cy + 0.6f * r, stroke); line(-0.3f, -0.5f, -0.3f, -0.85f); line(0.3f, -0.5f, 0.3f, -0.85f); line(-0.45f, -0.85f, 0.45f, -0.85f) }
        "systems", "bus" -> { for (k in 0 until 4) { val y = -0.75f + k * 0.5f; line(-0.9f, y, -0.6f, y); line(-0.35f, y, 0.9f, y) } }
        "music" -> { // a pair of quavers
            c.drawCircle(cx - 0.45f * r, cy + 0.6f * r, r * 0.28f, stroke); c.drawCircle(cx + 0.55f * r, cy + 0.4f * r, r * 0.28f, stroke)
            line(-0.17f, 0.6f, -0.17f, -0.8f); line(0.83f, 0.4f, 0.83f, -1f); line(-0.17f, -0.8f, 0.83f, -1f); line(-0.17f, -0.5f, 0.83f, -0.7f)
        }
        "g" -> { c.drawCircle(cx, cy, r, stroke); c.drawCircle(cx, cy, r * 0.5f, stroke); line(-1f, 0f, 1f, 0f); line(0f, -1f, 0f, 1f) }
        "style" -> { // a palette with three paint wells
            c.drawCircle(cx, cy, r, stroke)
            fill.color = colour
            for ((dx, dy) in listOf(-0.4f to -0.35f, 0.25f to -0.5f, 0.5f to 0.1f)) c.drawCircle(cx + dx * r, cy + dy * r, r * 0.17f, fill)
            c.drawCircle(cx - 0.3f * r, cy + 0.45f * r, r * 0.22f, stroke)
        }
        else -> { c.drawCircle(cx, cy, r * 0.8f, stroke); line(-0.4f, 0f, 0.4f, 0f) }
    }
    stroke.strokeCap = Paint.Cap.BUTT
}

// =============================================================================================
// Drive page: configurable shortcut tiles
// =============================================================================================

internal fun tilePage(kind: String): DashRenderer.Page = when (kind) {
    "vehicle", "tyres" -> DashRenderer.Page.BODY
    "timers" -> DashRenderer.Page.TIMERS
    "clutch" -> DashRenderer.Page.CLUTCH
    "dyno" -> DashRenderer.Page.DYNO
    "vdc" -> DashRenderer.Page.VDC
    "shift" -> DashRenderer.Page.SHIFT
    "diff" -> DashRenderer.Page.DIFF
    "throttle" -> DashRenderer.Page.THROTTLE
    "systems" -> DashRenderer.Page.SYSTEMS
    else -> DashRenderer.Page.ENGINE
}

internal fun tileTitle(kind: String): String = when (kind) {
    "music" -> "MUSIC"
    "vdc" -> "VDC"
    "shift" -> "REV MATCH"
    else -> kind.uppercase()
}

internal fun DashRenderer.tileValue(kind: String, s: Snapshot, cfg: DashConfig): String? = when (kind) {
    "vehicle" -> s[Channel.FUEL]?.let { "FUEL ${it.roundToInt()}%" } ?: s[Channel.BATTERY]?.let { "%.1f V".format(it) }
    "timers" -> Analytics.zeroTo60.best?.let { "%.2f S".format(it) }
    "clutch" -> temps(cfg)(Analytics.clutchHeatC)
    "dyno" -> if (Analytics.peakKw > 0f) "${(Analytics.peakKw * 1.341f).roundToInt()} WHP" else null
    "vdc" -> if (Analytics.yawExpected != null) balanceWord(Analytics.balance) else null
    "shift" -> ShiftTracker.last?.let { "${it.score} · ${it.rating}" }
    "diff" -> s[Channel.WHEEL_RL]?.let { "%.1f".format(it - (s[Channel.WHEEL_RR] ?: it)) }
    "tyres" -> listOfNotNull(s[Channel.TIRE_FL, 15_000], s[Channel.TIRE_FR, 15_000], s[Channel.TIRE_RL, 15_000], s[Channel.TIRE_RR, 15_000])
        .minOrNull()?.let { if (cfg.imperial) "MIN ${(it / 6.8948f).roundToInt()} PSI" else "MIN %.1f BAR".format(it / 100f) }
    "fuel" -> s[Channel.FUEL]?.let { "${it.roundToInt()}%" }
    "engine" -> s[Channel.OIL_TEMP]?.let { "OIL ${temps(cfg)(it)}" } ?: s[Channel.COOLANT]?.let { temps(cfg)(it) }
    "throttle" -> s[Channel.THROTTLE]?.let { "${it.roundToInt()}%" }
    "systems" -> "${Analytics.verifiedSignals}/${Analytics.liveSignals}"
    "music" -> com.sickdash.data.NowPlaying.current?.let { (if (it.playing) "▶ " else "❚❚ ") + it.title.uppercase() }
    else -> null
}

internal fun DashRenderer.driveTiles(c: Canvas, r: RectF, s: Snapshot, cfg: DashConfig) {
    val gap = 5f * u
    val cols = 2
    val rows = 3
    val cw = (r.width() - gap) / cols
    val ch = (r.height() - gap * (rows - 1)) / rows
    for ((i, kind) in cfg.tiles.take(6).withIndex()) {
        val x = r.left + (i % cols) * (cw + gap)
        val y = r.top + (i / cols) * (ch + gap)
        val b = RectF(x, y, x + cw, y + ch)
        val target = tilePage(kind)
        panel(c, b)
        val ir = min(cw, ch) * 0.17f
        drawIcon(c, kind, b.centerX(), b.top + ch * 0.36f, ir, t.text)
        display(c, tileTitle(kind), b.centerX(), b.top + ch * 0.68f, min(ch * 0.13f, 10f * u), t.text, Paint.Align.CENTER)
        val v = tileValue(kind, s, cfg)
        val vs = min(ch * 0.1f, 7.5f * u)
        label(c, v?.let { ellipsize(it, b.width() - 10f * u, vs) } ?: "—", b.centerX(), b.bottom - ch * 0.1f, vs, if (v == null) t.faint else t.dim, Paint.Align.CENTER)
        // the music tile is a play / pause button; the others open their page
        newHits += b to { if (kind == "music") com.sickdash.data.NowPlaying.playPause() else page = target }
    }
}

// =============================================================================================
// Vehicle powered off
// =============================================================================================

internal fun DashRenderer.poweredOff(c: Canvas, area: RectF, s: Snapshot, cfg: DashConfig) {
    if (booting || poweredOffDismissed || cfg.source == Source.DEMO) return
    if (s[Channel.RPM, 3000] != null || offlineSince == 0L || s.now - offlineSince < 2500) return
    fill.color = t.bg
    c.drawRect(area, fill)
    val cx = area.centerX()
    val cy = area.centerY() - area.height() * 0.08f
    // power symbol
    val pr = 13f * u
    stroke.color = t.text
    stroke.strokeWidth = 2f * u
    stroke.strokeCap = Paint.Cap.ROUND
    rect.set(cx - pr, cy - 46f * u - pr, cx + pr, cy - 46f * u + pr)
    c.drawArc(rect, -60f, 300f, false, stroke)
    c.drawLine(cx, cy - 46f * u - pr * 1.25f, cx, cy - 46f * u - pr * 0.2f, stroke)
    stroke.strokeCap = Paint.Cap.BUTT
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    label(c, if (linkLostAt != 0L) "LINK LOST AT ${clock.format(Date(linkLostAt))}" else "WAITING FOR THE CAR",
        cx, cy - 16f * u, 8f * u, t.dim, Paint.Align.CENTER)
    display(c, "VEHICLE POWERED OFF", cx, cy + 12f * u, 26f * u, t.text, Paint.Align.CENTER, glow = true)
    // the last drive
    val stats = listOf(
        "ODOMETER" to (s[Channel.ODOMETER, Long.MAX_VALUE]?.let { ChannelInfo.format(Channel.ODOMETER, it, cfg.imperial) } ?: "—"),
        "LAST DRIVE" to "%.1f %s".format(if (cfg.imperial) SessionStats.distanceKm * 0.621371 else SessionStats.distanceKm, if (cfg.imperial) "MI" else "KM"),
        "DRIVE TIME" to "${if (SessionStats.startedAt > 0 && lastLiveAt > SessionStats.startedAt) (lastLiveAt - SessionStats.startedAt) / 60_000 else 0} MIN",
        "TOP SPEED" to (if (cfg.imperial) "${(SessionStats.topKph * DashRenderer.KPH_TO_MPH).roundToInt()} MPH" else "${SessionStats.topKph.roundToInt()} KM/H"),
    )
    val colW = min(110f * u, area.width() / 5f)
    var x = cx - colW * 1.5f
    for ((name, value) in stats) {
        label(c, name, x, cy + 34f * u, 7f * u, t.dim, Paint.Align.CENTER)
        display(c, value, x, cy + 50f * u, 13f * u, t.text, Paint.Align.CENTER, glow = true)
        x += colW
    }
    label(c, ellipsize(s.link.message.uppercase(), area.width() * 0.8f, 7f * u), cx, cy + 70f * u, 7f * u, t.faint, Paint.Align.CENTER)
    val bw = 90f * u
    val b = RectF(cx - bw / 2, cy + 80f * u, cx + bw / 2, cy + 98f * u)
    stroke.color = t.dim; stroke.strokeWidth = max(1f, 0.8f * u); c.drawRect(b, stroke)
    label(c, "OPEN DASH", b.centerX(), b.centerY() + 3f * u, 8f * u, t.text, Paint.Align.CENTER)
    newHits += RectF(area) to { poweredOffDismissed = true }
}
