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

class MainActivity : ComponentActivity() {

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPermissions()
        setContent { Screen(onToggle = ::toggle) }
    }

    private fun toggle() {
        if (Radar.running) AlertService.stop(this) else AlertService.start(this)
    }

    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) need += Manifest.permission.BLUETOOTH_CONNECT
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { perms.launch(missing.toTypedArray()); return }

        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            return
        }
        // Without this exemption Android blocks the Bluetooth-triggered auto-start.
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        }
    }
}

@Composable
private fun Screen(onToggle: () -> Unit) {
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
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

        BasicText(
            when {
                !Radar.running -> "TAP TO START"
                !Radar.hasFix -> "ACQUIRING GPS"
                armed -> "CAMERA AHEAD"
                else -> "CLEAR ROAD"
            },
            style = TextStyle(color = if (armed) Danger else Calm, fontSize = 12.sp, letterSpacing = 5.sp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)
        )
    }
}

/** Speed-limit disc; the ring fills as the camera gets closer. */
@Composable
private fun LimitSign(limit: Int, closeness: Float, over: Boolean, modifier: Modifier = Modifier) {
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
            drawArc(
                color = if (over) Danger else Calm, startAngle = -90f, sweepAngle = 360f * closeness, useCenter = false,
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
