package com.speedlimitbot

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * A floating disc over whatever app is in front — Google Maps, usually — so the warning is
 * visible without switching apps. The two apps never talk: Maps navigates, this watches.
 *
 * Phone only. An overlay window does not project to Android Auto and no API lets one app draw
 * over another's map there; in the car the same warning arrives as audio and a heads-up
 * notification, which already work over Maps.
 *
 * Idle it shows the speed, dim. Near a camera it turns amber with the limit ("?" when unknown),
 * red over the limit, and pulses while the beeps sound — the same rules as the full screen.
 */
object Bubble {

    private const val PREF = "bubble"
    private var view: TextView? = null
    private var pulse: ObjectAnimator? = null

    /** False while our own screen is in front: the bubble would only cover it. */
    var appVisible = false

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("on", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        update(ctx)
    }

    fun allowed(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** Called on every state change; shows, redraws or removes the bubble as the state requires. */
    fun update(ctx: Context) {
        val want = Radar.running && !appVisible && enabled(ctx) && allowed(ctx)
        if (!want) { hide(ctx); return }
        val v = view ?: create(ctx.applicationContext) ?: return
        val armed = Radar.distanceM >= 0
        v.text = when {
            !armed -> "${Radar.speedKmh}"
            Radar.limitKmh > 0 -> "${Radar.limitKmh}"
            else -> "?"
        }
        v.setTextColor(if (armed) Color.WHITE else Color.rgb(160, 166, 172))
        (v.background as GradientDrawable).setColor(
            when {
                !armed -> Color.argb(210, 7, 8, 10)
                Radar.over -> Color.rgb(200, 36, 28)
                else -> Color.rgb(214, 152, 0)
            }
        )
        if (Radar.beeping) {
            if (pulse == null) pulse = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.45f).apply {
                duration = if (Radar.over) 170 else 350
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            pulse?.cancel(); pulse = null
            v.alpha = 1f
        }
    }

    fun hide(ctx: Context) {
        pulse?.cancel(); pulse = null
        val v = view ?: return
        view = null
        runCatching { ctx.applicationContext.getSystemService(WindowManager::class.java).removeView(v) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create(ctx: Context): TextView? {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val px = (72 * ctx.resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            px, px,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never takes focus or keyboard input from the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            x = p.getInt("x", ctx.resources.displayMetrics.widthPixels - px - px / 4)
            y = p.getInt("y", ctx.resources.displayMetrics.heightPixels / 3)
        }
        val v = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            contentDescription = "SpeedLimitBot"
        }
        // Drag to move it off whatever it covers; a tap without movement opens the app.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - downX) < px / 8 && abs(e.rawY - downY) < px / 8) {
                        runCatching {
                            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    } else {
                        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                            .putInt("x", lp.x).putInt("y", lp.y).apply()
                    }
                }
            }
            true
        }
        return runCatching { wm.addView(v, lp); v }.getOrNull()?.also { view = it }
    }
}
