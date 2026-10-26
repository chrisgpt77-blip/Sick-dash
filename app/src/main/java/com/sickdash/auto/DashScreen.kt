package com.sickdash.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import com.sickdash.R

/**
 * The only screen on the car display: a full-screen NavigationTemplate whose map surface we
 * draw the HUD on. Taps go straight to the HUD; the action strip is a single page-switcher icon.
 */
class DashScreen(carContext: CarContext, private val renderer: SurfaceRenderer) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val dash = renderer.dash
        // The template insists on at least one action, and the host drops the strip down over the
        // dash on every tap, so it's one small icon: the page switcher. Everything else is on the
        // dash itself (tabs, Style page, tap the G radar to reset).
        val pages = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_pages)).build())
            .setOnClickListener { dash.switcherOpen = !dash.switcherOpen }
            .build()
        val strip = ActionStrip.Builder().addAction(pages).build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }
}
