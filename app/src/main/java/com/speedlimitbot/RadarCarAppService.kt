package com.speedlimitbot

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * The Android Auto entry point. Auto starting this service is what launches the radar when
 * the user plugs in — no Bluetooth guesswork, no battery-optimisation exemption needed.
 */
class RadarCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            AlertService.start(carContext)
            return RadarScreen(carContext)
        }
    }

    override fun onDestroy() {
        AlertService.stop(this)
        super.onDestroy()
    }
}
