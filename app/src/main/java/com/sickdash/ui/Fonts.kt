package com.sickdash.ui

import android.content.res.AssetManager
import android.graphics.Typeface

/** Oxanium (squared display figures) and Share Tech Mono (HUD labels), both SIL OFL, from assets/fonts. */
object Fonts {
    lateinit var display: Typeface; private set
    lateinit var displayMid: Typeface; private set
    lateinit var mono: Typeface; private set
    @Volatile private var loaded = false

    @Synchronized
    fun init(assets: AssetManager) {
        if (loaded) return
        display = oxanium(assets, 800) ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)
        displayMid = oxanium(assets, 600) ?: Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        mono = runCatching { Typeface.createFromAsset(assets, "fonts/ShareTechMono-Regular.ttf") }.getOrNull() ?: Typeface.MONOSPACE
        loaded = true
    }

    private fun oxanium(assets: AssetManager, weight: Int): Typeface? = runCatching {
        Typeface.Builder(assets, "fonts/Oxanium.ttf").setFontVariationSettings("'wght' $weight").build()
    }.getOrNull()
}
