package com.speedlimitbot

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * The car screen: a POI list of the cameras around the car on a host-drawn map, with a live
 * row for speed and the camera ahead.
 *
 * Redrawn on every GPS fix, so each template has to count as a refresh rather than a new step,
 * or the host closes the app after five. For this template that means the header title, the
 * number of rows and every row title — spans aside — stay put. Hence the fixed "Now" title, and
 * camera rows whose title is nothing but a distance span: everything that changes lives in the
 * text line underneath.
 */
class RadarScreen(ctx: CarContext) : Screen(ctx), DefaultLifecycleObserver {

    private var picking = false

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        picking = false
        Radar.onChange = {
            offerLimitIfPending()
            invalidate()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        Radar.onChange = null
    }

    /**
     * Not every car host renders an action strip — the Automotive host does not — so the picker
     * cannot depend on the driver finding a button. Present it directly once the camera is
     * behind us and nothing else is being warned about.
     */
    private fun offerLimitIfPending() {
        val unknown = Radar.pending ?: return
        if (picking || Radar.distanceM >= 0) return
        picking = true
        screenManager.push(LimitPickerScreen(carContext, unknown))
    }

    override fun onGetTemplate(): Template {
        val template = PlaceListMapTemplate.Builder()
            .setTitle("Cameras nearby")
            .setCurrentLocationEnabled(true)
            .setActionStrip(actions())

        // ponytail: full sort of every camera per fix; a few thousand rows is well under a
        // millisecond. Bbox-filter first if the downloaded set grows into the hundreds of thousands.
        val near = if (Radar.lat.isNaN()) emptyList()
            else CameraDb.get(carContext).nearest(Radar.lat, Radar.lon, rowLimit() - 1)
        // Every row needs a distance, and the template wants a list or a spinner, never neither.
        if (near.isEmpty()) return template.setLoading(true).build()

        val list = ItemList.Builder().addItem(nowRow(near.first()))
        near.forEach { list.addItem(cameraRow(it)) }
        return template.setItemList(list.build()).build()
    }

    /** Speed and the camera ahead; with none ahead, the distance is to the nearest one. */
    private fun nowRow(nearest: CameraDb.Hit): Row {
        val armed = Radar.distanceM >= 0
        val speed = when {
            !Radar.running -> "Stopped"
            !Radar.hasFix -> "Acquiring GPS"
            armed && Radar.limitKmh > 0 -> "${Radar.speedKmh} in ${Radar.limitKmh} km/h"
            else -> "${Radar.speedKmh} km/h"
        }
        val text = SpannableString("$speed · ${if (armed) "camera ahead" else "nearest camera"}  ")
        text.setSpan(
            DistanceSpan.create(distance(if (armed) Radar.distanceM.toDouble() else nearest.distance)),
            text.length - 1, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE
        )
        if (Radar.over) {
            text.setSpan(ForegroundCarColorSpan.create(CarColor.RED), 0, speed.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return Row.Builder().setTitle("Now").addText(text).build()
    }

    private fun cameraRow(hit: CameraDb.Hit): Row {
        val title = SpannableString(" ")
        title.setSpan(DistanceSpan.create(distance(hit.distance)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        val what = if (hit.speedCamera) "Speed camera" else "Traffic camera"
        val marker = PlaceMarker.Builder()
            .setColor(if (hit.speedCamera) CarColor.RED else CarColor.YELLOW)
            .apply { if (hit.limitKmh > 0) setLabel(hit.limitKmh.toString()) }
            .build()
        return Row.Builder()
            .setTitle(CarText.create(title))
            .addText(if (hit.limitKmh > 0) "$what · limit ${hit.limitKmh}" else what)
            .setMetadata(
                Metadata.Builder()
                    .setPlace(Place.Builder(CarLocation.create(hit.lat, hit.lon)).setMarker(marker).build())
                    .build()
            )
            .build()
    }

    private fun distance(m: Double): Distance =
        if (m >= 1000) Distance.create(m / 1000, Distance.UNIT_KILOMETERS)
        else Distance.create(m, Distance.UNIT_METERS)

    /** The host drops rows past its limit; ask for it where the host can say (car API 2+). */
    private fun rowLimit(): Int =
        if (carContext.carAppApiLevel >= 2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
        } else 6

    private fun actions(): ActionStrip {
        val strip = ActionStrip.Builder()
        val unknown = Radar.pending
        if (unknown != null && Radar.distanceM < 0) {
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
