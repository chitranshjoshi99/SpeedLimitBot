package com.speedlimitbot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

/**
 * Everything the driver might want that is not the drive itself, behind one hamburger.
 *
 * Nothing here is needed while moving, so it is deliberately out of the way: a small icon in the
 * corner, and a panel that dims the speed readout while it is open.
 *
 * @param onExport hands the user CSV to the system file picker; only the Activity can do that.
 */
@Composable
fun Menu(onExport: () -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    fun say(msg: String) { Say.text = msg }

    Box(Modifier.fillMaxSize()) {

        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .size(48.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) { open = !open },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(22.dp)) {
                val stroke = size.height * 0.1f
                listOf(0.2f, 0.5f, 0.8f).forEach { f ->
                    drawLine(
                        Dim, Offset(0f, size.height * f), Offset(size.width, size.height * f),
                        strokeWidth = stroke, cap = StrokeCap.Round
                    )
                }
            }
        }

        // Tapping anywhere else closes the panel, so no item needs its own dismiss.
        if (open) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xCC07080A))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { open = false }
            )
        }

        AnimatedVisibility(
            visible = open,
            // Fade only: a panel that is still growing moves its own rows out from under the
            // finger, and the tap lands on the scrim behind it instead.
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 64.dp, end = 16.dp)
        ) {
            Column(
                Modifier
                    .background(Color(0xFF15181C), RoundedCornerShape(14.dp))
                    .padding(vertical = 8.dp)
            ) {
                Item("CHECK FOR UPDATES") {
                    open = false
                    say("Checking…")
                    UpdateCheck.check(ctx, ::say)
                }
                Item("REFRESH CAMERA DATA") {
                    open = false
                    say("Refreshing…")
                    CameraSync.refreshNow(ctx, ::say)
                }
                Item("ADD CAMERA HERE") {
                    open = false
                    adding = true
                }
                Item("DOWNLOAD MY CAMERAS") {
                    open = false
                    val f = File(ctx.filesDir, LimitOverrides.FILE)
                    if (!f.exists() || f.length() == 0L) say("No cameras added yet") else onExport()
                }
            }
        }

        AddCamera(visible = adding, onDone = { adding = false }, say = ::say)

        // Last, so the answer to a tap is readable over the panel and the add sheet alike.
        Say.Line(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp)
        )
    }
}

/**
 * What a menu action has to say back. A Toast would be the obvious choice and is the wrong one:
 * car displays swallow them, so the answer to a tap would vanish exactly where it matters.
 */
object Say {

    var text by mutableStateOf("")

    @Composable
    fun Line(modifier: Modifier = Modifier) {
        val msg = text
        LaunchedEffect(msg) {
            // A message ending in an ellipsis is waiting on something slow — an Overpass query
            // can take minutes — so it stays up until whatever it is waiting for replaces it.
            if (msg.isEmpty() || msg.endsWith("…")) return@LaunchedEffect
            delay(5_000)
            if (text == msg) text = ""
        }
        AnimatedVisibility(
            visible = msg.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(300)),
            modifier = modifier
        ) {
            BasicText(msg, style = TextStyle(color = Caution, fontSize = 13.sp, letterSpacing = 1.sp))
        }
    }
}

@Composable
private fun Item(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = Chalk, fontSize = 13.sp, letterSpacing = 3.sp),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp)
    )
}

/**
 * Adding a camera means standing next to one: the position is wherever the phone is, and the
 * only thing the driver supplies is the limit. The row lands in the same file as the limits
 * picked after a pass, so it survives every sync and every app update.
 */
@Composable
private fun AddCamera(visible: Boolean, onDone: () -> Unit, say: (String) -> Unit) {
    val ctx = LocalContext.current
    var limit by remember { mutableIntStateOf(-1) }
    val fixed = !Radar.lat.isNaN()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xF207080A))
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    "ADD CAMERA HERE",
                    style = TextStyle(color = Dim, fontSize = 11.sp, letterSpacing = 4.sp)
                )
                Spacer(Modifier.height(10.dp))
                BasicText(
                    if (fixed) "%.5f, %.5f".format(Radar.lat, Radar.lon) else "waiting for a fix",
                    style = TextStyle(color = if (fixed) Chalk else Danger, fontSize = 15.sp)
                )

                Spacer(Modifier.height(28.dp))
                BasicText("SPEED LIMIT", style = TextStyle(color = Dim, fontSize = 11.sp, letterSpacing = 4.sp))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LimitOverrides.CHOICES.forEach { kmh -> Chip(kmh.toString(), limit == kmh) { limit = kmh } }
                    // An unknown limit is still worth recording: the warning fires either way.
                    Chip("?", limit == 0) { limit = 0 }
                }

                Spacer(Modifier.height(36.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BasicText(
                        "ADD",
                        style = TextStyle(
                            color = if (fixed && limit >= 0) Calm else Dim,
                            fontSize = 14.sp, letterSpacing = 4.sp
                        ),
                        modifier = Modifier
                            .clickable {
                                when {
                                    !fixed -> say("No GPS fix yet")
                                    limit < 0 -> say("Pick a limit first")
                                    else -> {
                                        LimitOverrides.save(ctx, Radar.lat, Radar.lon, limit, true)
                                        say("Camera added")
                                        limit = -1
                                        onDone()
                                    }
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                    BasicText(
                        "CANCEL",
                        style = TextStyle(color = Dim, fontSize = 14.sp, letterSpacing = 4.sp),
                        modifier = Modifier
                            .clickable { limit = -1; onDone() }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, picked: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .background(if (picked) Chalk else Color(0xFF15181C), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = TextStyle(color = if (picked) Ink else Chalk, fontSize = 18.sp))
    }
}
