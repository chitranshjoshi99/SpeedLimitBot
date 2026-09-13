package com.speedlimitbot

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * The car screen: current speed and the camera ahead. Deliberately two rows — anything
 * more is reading material at 100 km/h.
 */
class RadarScreen(ctx: CarContext) : Screen(ctx), DefaultLifecycleObserver {

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        Radar.onChange = { invalidate() }
    }

    override fun onStop(owner: LifecycleOwner) {
        Radar.onChange = null
    }

    override fun onGetTemplate(): Template {
        val speed = Row.Builder()
            .setTitle("${Radar.speedKmh} km/h")
            .addText(
                when {
                    !Radar.hasFix -> "Acquiring GPS"
                    Radar.over -> "Over the limit"
                    else -> "Within the limit"
                }
            )
            .build()

        val camera = Row.Builder()
            .setTitle(
                when {
                    Radar.distanceM < 0 -> "No camera ahead"
                    Radar.limitKmh > 0 -> "Camera ahead · limit ${Radar.limitKmh}"
                    else -> "Camera ahead"
                }
            )
            .addText(if (Radar.distanceM < 0) "Clear road" else "${Radar.distanceM} m")
            .build()

        return PaneTemplate.Builder(
            Pane.Builder().addRow(speed).addRow(camera).build()
        )
            .setTitle("SpeedLimitBot")
            .setActionStrip(
                ActionStrip.Builder().addAction(
                    Action.Builder()
                        .setTitle(if (Radar.running) "Stop" else "Start")
                        .setBackgroundColor(if (Radar.over) CarColor.RED else CarColor.DEFAULT)
                        .setOnClickListener {
                            if (Radar.running) AlertService.stop(carContext)
                            else AlertService.start(carContext)
                            invalidate()
                        }
                        .build()
                ).build()
            )
            .build()
    }
}
