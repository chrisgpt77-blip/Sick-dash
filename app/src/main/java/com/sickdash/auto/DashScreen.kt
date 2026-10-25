package com.sickdash.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.preference.PreferenceManager
import com.sickdash.ui.DashRenderer

/**
 * The only screen on the car display: a full-screen NavigationTemplate whose map surface we
 * draw the gauges on, plus a small action strip for switching layouts.
 */
class DashScreen(carContext: CarContext, private val renderer: SurfaceRenderer) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val dash = renderer.dash
        val nextPage = if (dash.page == DashRenderer.Page.DASH) "Race" else "Dash"
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle(nextPage).setOnClickListener {
                    dash.page = if (dash.page == DashRenderer.Page.DASH) DashRenderer.Page.RACE else DashRenderer.Page.DASH
                    invalidate()
                }.build()
            )
            .addAction(
                Action.Builder().setTitle("Units").setOnClickListener {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(carContext)
                    val imperial = prefs.getString("units", "imperial") == "imperial"
                    prefs.edit().putString("units", if (imperial) "metric" else "imperial").apply()
                }.build()
            )
            .addAction(
                Action.Builder().setTitle("Reset G").setOnClickListener { dash.resetPeaks() }.build()
            )
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }
}
