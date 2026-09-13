package com.speedlimitbot

import androidx.compose.runtime.getValue
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

    fun idle() {
        limitKmh = 0
        distanceM = -1
        over = false
    }
}
