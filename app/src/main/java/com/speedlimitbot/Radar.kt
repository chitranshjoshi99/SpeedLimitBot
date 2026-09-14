package com.speedlimitbot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Live state shared between the service and the UI. Compose snapshot state, no flow plumbing. */
object Radar {
    var running by mutableStateOf(false)
    var hasFix by mutableStateOf(false)
    var speedKmh by mutableIntStateOf(0)
    var limitKmh by mutableIntStateOf(0)
    var distanceM by mutableIntStateOf(-1)
    var over by mutableStateOf(false)

    /** Last known position. NaN until the first fix; the menu needs it to place a new camera. */
    var lat by mutableDoubleStateOf(Double.NaN)
    var lon by mutableDoubleStateOf(Double.NaN)

    /** Set by the car screen, which is not Compose and has to be told to redraw. */
    var onChange: (() -> Unit)? = null

    /** A camera just passed whose limit the dataset does not know, so the driver can fill it in. */
    var pending by mutableStateOf<Unknown?>(null)

    class Unknown(val lat: Double, val lon: Double, val speedCamera: Boolean)

    fun idle() {
        limitKmh = 0
        distanceM = -1
        over = false
    }
}
