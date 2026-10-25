package com.sickdash.data

import android.graphics.Bitmap

/**
 * The next turn, read from Google Maps' turn-by-turn notification while it navigates (the
 * notification listener feeds [update]). Maps doesn't publish its notification layout, so the
 * texts are read defensively: whichever of title / text looks like a distance is the distance,
 * the other is the instruction, and the sub-text (time · distance · ETA) is shown as it is.
 */
object Navigation {
    class Turn(
        val distance: String?,
        val instruction: String,
        val eta: String?,
        val icon: Bitmap?,
        val at: Long,
        val demo: Boolean = false,
    )

    @Volatile var current: Turn? = null
        private set

    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    private val DISTANCE = Regex("""^\s*[\d.,]+\s*(ft|feet|mi|mile|miles|m|km|yd|yds|yards?)\b.*""", RegexOption.IGNORE_CASE)

    fun looksLikeDistance(s: String?) = s != null && DISTANCE.matches(s)

    /** Builds a turn from the notification's texts, or null when there's no instruction in them. */
    fun parse(title: CharSequence?, text: CharSequence?, sub: CharSequence?, icon: Bitmap?, now: Long): Turn? {
        val t = title?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val x = text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val (distance, instruction) = when {
            looksLikeDistance(t) -> t to x
            looksLikeDistance(x) -> x to t
            else -> null to (t ?: x)
        }
        if (instruction.isNullOrBlank()) return null
        return Turn(distance, instruction, sub?.toString()?.trim()?.takeIf { it.isNotEmpty() }, icon, now)
    }

    fun update(turn: Turn?) { current = turn }

    fun clear() { current = null }

    /** The demo drive's route; never replaces a real one. */
    fun demo(turn: Turn?) {
        val c = current
        if (c == null || c.demo) current = turn
    }

    fun clearDemo() { if (current?.demo == true) current = null }
}
