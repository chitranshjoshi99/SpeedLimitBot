package com.speedlimitbot

import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/**
 * Paints the car's map surface.
 *
 * NavigationTemplate.setBackgroundColor only tints the small routing card; the rest of the
 * screen belongs to the app's surface. Filling that surface is the only way to wash the whole
 * window, which is the point — colour at the edge of vision is what a driver actually catches.
 */
class RadarSurface(private val ctx: CarContext) : SurfaceCallback {

    private val handler = Handler(Looper.getMainLooper())
    private var container: SurfaceContainer? = null
    private var bright = false

    private val pulse = object : Runnable {
        override fun run() {
            bright = !bright
            draw()
            if (Radar.distanceM >= 0) handler.postDelayed(this, PULSE_MS)
        }
    }

    fun attach() {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    /** Called whenever the alert state changes, so the pulse starts and stops with it. */
    fun onStateChanged() {
        handler.removeCallbacks(pulse)
        if (Radar.distanceM >= 0) handler.post(pulse) else { bright = false; draw() }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        draw()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        handler.removeCallbacks(pulse)
        container = null
    }

    private fun draw() {
        val surface = container?.surface ?: return
        if (!surface.isValid) return
        val canvas: Canvas = runCatching { surface.lockCanvas(null) }.getOrNull() ?: return
        try {
            canvas.drawColor(colour())
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    private fun colour(): Int = when {
        Radar.distanceM < 0 -> INK
        Radar.over -> if (bright) RED_BRIGHT else RED_DIM
        else -> if (bright) AMBER_BRIGHT else AMBER_DIM
    }

    companion object {
        private const val PULSE_MS = 420L
        private val INK = Color.rgb(7, 8, 10)
        private val AMBER_DIM = Color.rgb(92, 66, 0)
        private val AMBER_BRIGHT = Color.rgb(214, 152, 0)
        private val RED_DIM = Color.rgb(96, 18, 14)
        private val RED_BRIGHT = Color.rgb(200, 36, 28)
    }
}
