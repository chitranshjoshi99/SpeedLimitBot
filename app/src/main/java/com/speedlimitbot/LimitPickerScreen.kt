package com.speedlimitbot

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/**
 * Five fixed limits for a camera OpenStreetMap has no limit for. A list, never a keyboard —
 * text entry while driving is both blocked by the host and a terrible idea.
 */
class LimitPickerScreen(ctx: CarContext, private val camera: Radar.Unknown) : Screen(ctx) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        LimitOverrides.CHOICES.forEach { kmh ->
            list.addItem(
                Row.Builder()
                    .setTitle("$kmh km/h")
                    .setOnClickListener {
                        LimitOverrides.save(carContext, camera.lat, camera.lon, kmh, camera.speedCamera)
                        Radar.pending = null
                        screenManager.pop()
                    }
                    .build()
            )
        }
        // Dismissing without choosing must clear the offer, or it reappears on the next fix.
        list.addItem(
            Row.Builder()
                .setTitle("Skip")
                .setOnClickListener {
                    Radar.pending = null
                    screenManager.pop()
                }
                .build()
        )
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle("Limit at that camera")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
