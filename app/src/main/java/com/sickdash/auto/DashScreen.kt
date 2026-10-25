package com.sickdash.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.preference.PreferenceManager
import com.sickdash.ui.Theme

/**
 * The only screen on the car display: a full-screen NavigationTemplate whose map surface we
 * draw the HUD on. The action strip pages through the HUD for head units without a touchscreen.
 */
class DashScreen(carContext: CarContext, private val renderer: SurfaceRenderer) : Screen(carContext) {
    init {
        renderer.onChanged = { invalidate() }
    }

    override fun onGetTemplate(): Template {
        val dash = renderer.dash
        val prefs = PreferenceManager.getDefaultSharedPreferences(carContext)
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle(dash.page.title).setOnClickListener {
                    dash.nextPage()
                    invalidate()
                }.build()
            )
            .addAction(
                Action.Builder().setTitle("Theme").setOnClickListener {
                    val all = Theme.ALL
                    val i = all.indexOfFirst { it.key == prefs.getString("theme", null) }
                    prefs.edit().putString("theme", all[(i + 1).mod(all.size)].key).apply()
                }.build()
            )
            .addAction(
                Action.Builder().setTitle(if (dash.switcherOpen) "Close" else "Pages").setOnClickListener {
                    dash.switcherOpen = !dash.switcherOpen
                    invalidate()
                }.build()
            )
            .addAction(Action.Builder().setTitle("Reset").setOnClickListener { dash.resetSession() }.build())
            .build()
        val mapStrip = ActionStrip.Builder().addAction(Action.PAN).build()
        return NavigationTemplate.Builder().setActionStrip(strip).setMapActionStrip(mapStrip).build()
    }
}
