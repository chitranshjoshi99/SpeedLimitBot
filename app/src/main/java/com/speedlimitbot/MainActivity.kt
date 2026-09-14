package com.speedlimitbot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF07080A)
private val Chalk = Color(0xFFF2F3F5)
private val Dim = Color(0xFF6B7076)
private val Danger = Color(0xFFFF3B30)
private val Calm = Color(0xFF3ED598)
private val Caution = Color(0xFFFFC300)

class MainActivity : ComponentActivity() {

    /**
     * Permissions asked in one chain rather than one per app launch. The old version returned
     * after each request, so reaching the battery-optimisation prompt took three separate
     * launches and most people never saw it — which silently broke Bluetooth auto-start.
     */
    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { askPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPermissions()
        setContent {
            Screen(onQuit = {
                if (Radar.running) AlertService.stop(this) else AlertService.start(this)
            })
        }
    }

    override fun onStart() {
        super.onStart()
        // Opening the app means the driver wants it watching. Nothing to tap.
        if (!Radar.running && hasLocation()) AlertService.start(this)
        UpdateCheck.maybeCheck(this)
    }

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) need += Manifest.permission.BLUETOOTH_CONNECT
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            perms.launch(missing.toTypedArray())
            return
        }

        // Background location can only be requested once the foreground one is granted.
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED && !askedBackground
        ) {
            askedBackground = true
            perms.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            return
        }

        // Without this exemption Android 12+ blocks the Bluetooth-triggered auto-start.
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !askedBattery) {
            askedBattery = true
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        if (hasLocation()) AlertService.start(this)
    }

    private var askedBackground = false
    private var askedBattery = false
}

@Composable
private fun Screen(onQuit: () -> Unit) {
    val armed = Radar.distanceM >= 0
    val over = Radar.over

    val speed by animateFloatAsState(Radar.speedKmh.toFloat(), tween(420, easing = FastOutSlowInEasing), label = "speed")
    val closeness by animateFloatAsState(
        if (armed) (1f - Radar.distanceM / AlertEngine.RANGE_M.toFloat()).coerceIn(0f, 1f) else 0f,
        tween(900, easing = FastOutSlowInEasing), label = "closeness"
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = if (over) 1.08f else 1.02f,
        animationSpec = infiniteRepeatable(tween(if (over) 380 else 1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale"
    )
    val speedColor by androidx.compose.animation.animateColorAsState(
        if (over) Danger else Chalk, tween(300), label = "speedColor"
    )

    // The whole window washes amber on approach and red when speeding: at a glance, from a
    // driving position, colour carries further than any number on the screen.
    val wash by androidx.compose.animation.animateColorAsState(
        when {
            !armed -> Ink
            over -> Danger
            else -> Caution
        },
        tween(260), label = "wash"
    )
    val flash by rememberInfiniteTransition(label = "flash").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (over) 340 else 700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "flashAlpha"
    )
    val ground = if (!armed) Ink else lerp(Ink, wash, 0.34f + 0.46f * flash)

    Box(
        Modifier
            .fillMaxSize()
            .background(ground),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            BasicText(
                speed.toInt().toString(),
                style = TextStyle(color = speedColor, fontSize = 116.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
            )
            BasicText("km/h", style = TextStyle(color = Dim, fontSize = 15.sp, letterSpacing = 4.sp))

            androidx.compose.foundation.layout.Spacer(Modifier.height(52.dp))

            AnimatedVisibility(
                visible = armed,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(220)) + shrinkVertically(tween(220))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    LimitSign(Radar.limitKmh, closeness, over, Modifier.scale(pulse))
                    androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))
                    BasicText(
                        if (Radar.distanceM >= 1000) "%.1f km".format(Radar.distanceM / 1000f) else "${Radar.distanceM} m",
                        style = TextStyle(color = Dim, fontSize = 22.sp, letterSpacing = 1.sp)
                    )
                }
            }
        }

        LimitPicker(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
        )

        UpdateBanner(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
        ) {
            BasicText(
                when {
                    !Radar.running -> "STOPPED"
                    !Radar.hasFix -> "ACQUIRING GPS"
                    armed -> "CAMERA AHEAD"
                    else -> "WATCHING"
                },
                style = TextStyle(
                    color = if (armed) Danger else Calm,
                    fontSize = 12.sp,
                    letterSpacing = 5.sp
                )
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))
            BasicText(
                if (Radar.running) "QUIT" else "START",
                style = TextStyle(color = Dim, fontSize = 13.sp, letterSpacing = 3.sp),
                modifier = Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        onQuit()
                    }
                    .padding(horizontal = 28.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * Sideloaded builds never get a Play update prompt, so a newer GitHub release says so here.
 * Tapping opens the release page — the driver installs it when parked, not mid-drive.
 */
@Composable
private fun UpdateBanner(modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val release = UpdateCheck.available
    AnimatedVisibility(
        visible = release != null,
        enter = fadeIn(tween(400)),
        exit = fadeOut(tween(200)),
        modifier = modifier
    ) {
        BasicText(
            "UPDATE ${release?.version.orEmpty()} AVAILABLE",
            style = TextStyle(color = Caution, fontSize = 11.sp, letterSpacing = 4.sp),
            modifier = Modifier
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    release?.let {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.url))) }
                    }
                }
                .padding(horizontal = 24.dp, vertical = 10.dp)
        )
    }
}

/**
 * Offered after passing a camera the dataset has no limit for. Five fixed choices, no keyboard:
 * whatever the driver picks is saved for that camera and used from the next pass on.
 */
@Composable
private fun LimitPicker(modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val unknown = Radar.pending
    AnimatedVisibility(
        visible = unknown != null && Radar.distanceM < 0,
        enter = fadeIn(tween(260)) + expandVertically(tween(260)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                "LIMIT AT THAT CAMERA?",
                style = TextStyle(color = Dim, fontSize = 11.sp, letterSpacing = 4.sp)
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                LimitOverrides.CHOICES.forEach { kmh ->
                    Box(
                        Modifier
                            .size(52.dp)
                            .background(Color(0xFF15181C), androidx.compose.foundation.shape.CircleShape)
                            .clickable {
                                unknown?.let {
                                    LimitOverrides.save(ctx, it.lat, it.lon, kmh, it.speedCamera)
                                    Radar.pending = null
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(kmh.toString(), style = TextStyle(color = Chalk, fontSize = 19.sp))
                    }
                }
                Box(
                    Modifier
                        .size(52.dp)
                        .clickable { Radar.pending = null },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText("skip", style = TextStyle(color = Dim, fontSize = 13.sp))
                }
            }
        }
    }
}

/** Speed-limit disc; the ring fills as the camera gets closer. */
@Composable
private fun LimitSign(limit: Int, closeness: Float, @Suppress("UNUSED_PARAMETER") over: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(150.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.085f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawCircle(Color(0xFF15181C), radius = size.minDimension / 2 - stroke)
            drawArc(
                color = Color(0xFF23272C), startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke)
            )
            // White, not amber or red: the window behind it already carries the urgency, and a
            // coloured ring on its own colour disappears.
            drawArc(
                color = Chalk, startAngle = -90f, sweepAngle = 360f * closeness, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
        }
        BasicText(
            if (limit > 0) limit.toString() else "?",   // the dataset does not know every limit
            style = TextStyle(color = Chalk, fontSize = 54.sp, fontWeight = FontWeight.Medium)
        )
    }
}
