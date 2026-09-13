package com.speedlimitbot

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * The car screen.
 *
 * NavigationTemplate rather than PaneTemplate for two reasons: it is the only template that
 * colours the whole window, and the host treats its updates as refreshes rather than steps in
 * a task, so it can be redrawn on every GPS fix without burning the five-template quota.
 *
 * Two lines of content, because the window this is meant to live in is the small one.
 */
class RadarScreen(ctx: CarContext) : Screen(ctx), DefaultLifecycleObserver {

    private val surface = RadarSurface(ctx)
    private var picking = false

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        surface.attach()
        picking = false
        Radar.onChange = {
            surface.onStateChanged()
            offerLimitIfPending()
            invalidate()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        Radar.onChange = null
    }

    /**
     * Not every car host renders a NavigationTemplate action strip — the Automotive host does
     * not — so the picker cannot depend on the driver finding a button. Present it directly
     * once the camera is behind us and nothing else is being warned about.
     */
    private fun offerLimitIfPending() {
        val unknown = Radar.pending ?: return
        if (picking || Radar.distanceM >= 0) return
        picking = true
        screenManager.push(LimitPickerScreen(carContext, unknown))
    }

    override fun onGetTemplate(): Template {
        val armed = Radar.distanceM >= 0

        val title = when {
            !Radar.running -> "Stopped"
            !Radar.hasFix -> "Acquiring GPS"
            !armed -> "${Radar.speedKmh} km/h"
            Radar.limitKmh > 0 -> "${Radar.speedKmh} in ${Radar.limitKmh}"
            else -> "${Radar.speedKmh} km/h"
        }
        val text = when {
            !armed -> "No camera ahead"
            Radar.distanceM >= 1000 -> "Camera %.1f km".format(Radar.distanceM / 1000f)
            else -> "Camera ${Radar.distanceM} m"
        }

        val builder = NavigationTemplate.Builder()
            .setNavigationInfo(MessageInfo.Builder(title).setText(text).build())
            .setActionStrip(actions(armed))

        // Tints the routing card only; the full-window wash is painted by RadarSurface.
        if (armed) {
            builder.setBackgroundColor(if (Radar.over) CarColor.RED else CarColor.YELLOW)
        }
        return builder.build()
    }

    private fun actions(armed: Boolean): ActionStrip {
        val strip = ActionStrip.Builder()
        val unknown = Radar.pending
        if (unknown != null && !armed) {
            strip.addAction(
                Action.Builder()
                    .setTitle("Set limit")
                    .setOnClickListener { screenManager.push(LimitPickerScreen(carContext, unknown)) }
                    .build()
            )
        }
        strip.addAction(
            Action.Builder()
                .setTitle(if (Radar.running) "Stop" else "Start")
                .setOnClickListener {
                    if (Radar.running) AlertService.stop(carContext) else AlertService.start(carContext)
                    invalidate()
                }
                .build()
        )
        return strip.build()
    }
}
