package com.speedlimitbot

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val ongoing = NotificationChannel(CHANNEL, "Radar", NotificationManager.IMPORTANCE_LOW)
        ongoing.setShowBadge(false)
        // IMPORTANCE_HIGH is what makes the car show this as a heads-up over the map.
        val alert = NotificationChannel(CHANNEL_ALERT, "Camera alerts", NotificationManager.IMPORTANCE_HIGH)
        alert.setShowBadge(false)
        alert.enableVibration(false)
        alert.setSound(null, null)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(ongoing, alert))
    }

    companion object {
        const val CHANNEL = "radar"
        const val CHANNEL_ALERT = "radar_alert"
    }
}
