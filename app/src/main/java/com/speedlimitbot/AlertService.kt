package com.speedlimitbot

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import androidx.car.app.connection.CarConnection
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Observer

/**
 * Foreground service: 1 Hz GPS -> nearest camera ahead -> voice + beeps.
 * Stops itself when Android Auto projection ends.
 */
class AlertService : android.app.Service(), LocationListener {

    private val engine = AlertEngine()
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var tone: ToneGenerator? = null
    private var beep = AlertEngine.Beep.NONE
    private val track = Track()
    private var carConnection: CarConnection? = null
    private var carObserver: Observer<Int>? = null
    private var sawCar = false

    private val beeper = object : Runnable {
        override fun run() {
            val (dur, gap) = when (beep) {
                AlertEngine.Beep.FAST -> 70 to 180
                AlertEngine.Beep.SLOW -> 120 to 900
                AlertEngine.Beep.NONE -> return
            }
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, dur)
            handler.postDelayed(this, (dur + gap).toLong())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, NOTE_ID, notification("Waiting for GPS"),
            if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        tts = TextToSpeech(this) { st ->
            Log.i(TAG, "TTS init status=$st")
            if (st == TextToSpeech.SUCCESS) tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        CameraDb.get(this)
        startGps()
        watchCarConnection()
        Radar.running = true
    }

    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        val lm = getSystemService(LocationManager::class.java)
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this) }
    }

    /** Android Auto disconnects -> we are done. */
    private fun watchCarConnection() {
        // Only a real projection drop stops us; the initial NOT_CONNECTED is ignored.
        val obs = Observer<Int> { state ->
            if (state != CarConnection.CONNECTION_TYPE_NOT_CONNECTED) sawCar = true
            else if (sawCar) stopSelf()
        }
        carObserver = obs
        carConnection = CarConnection(this).also { it.type.observeForever(obs) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        Radar.hasFix = true

        val fix = track.update(
            loc.latitude, loc.longitude, loc.time,
            if (loc.hasSpeed() && loc.speed > 0f) loc.speed else null,
            if (loc.hasBearing() && loc.hasSpeed() && loc.speed > AlertEngine.MIN_MOVING_MPS) loc.bearing else null
        ) ?: return

        Radar.speedKmh = (fix.speedMps * 3.6f).toInt()

        if (fix.heading.isNaN() || fix.speedMps < AlertEngine.MIN_MOVING_MPS) {
            standDown(); return
        }

        val hit = CameraDb.get(this)
            .nearestAhead(loc.latitude, loc.longitude, fix.heading, AlertEngine.RANGE_M)
        if (hit == null) { standDown(); return }

        Radar.limitKmh = hit.limitKmh
        Radar.distanceM = hit.distance.toInt()
        Radar.over = hit.limitKmh > 0 && Radar.speedKmh > hit.limitKmh + AlertEngine.OVER_TOLERANCE_KMH

        val out = engine.update(hit.id, hit.distance, fix.speedMps, hit.limitKmh)
        if (out.announce) {
            speak(out.limitKmh)
            carAlert(out.limitKmh, hit.distance.toInt())
        }
        setBeep(out.beep)
        Radar.onChange?.invoke()
    }

    private fun standDown() {
        engine.clear()
        setBeep(AlertEngine.Beep.NONE)
        Radar.idle()
        Radar.onChange?.invoke()
    }

    /**
     * The closest thing Android Auto has to an overlay: a navigation heads-up notification,
     * which the car draws over whatever is on screen — Google Maps included. There is no
     * floating-window API on the car display, so this is the whole mechanism.
     */
    private fun carAlert(limit: Int, metres: Int) {
        if (!notificationsAllowed()) return
        val note = NotificationCompat.Builder(this, App.CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(if (limit > 0) "Speed camera · limit $limit" else "Speed camera ahead")
            .setContentText("$metres m ahead")
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOnlyAlertOnce(true)
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(if (limit > 0) "Camera ahead · $limit" else "Camera ahead")
                    .setContentText("$metres m")
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build()
            )
            .build()
        NotificationManagerCompat.from(this).notify(ALERT_ID, note)
    }

    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun speak(limit: Int) {
        val words = if (limit > 0) "Speed camera ahead. Limit $limit" else "Speed camera ahead"
        val r = tts?.speak(words, TextToSpeech.QUEUE_FLUSH, null, "cam")
        Log.i(TAG, "SPEAK limit=$limit dist=${Radar.distanceM} speed=${Radar.speedKmh} result=$r")
    }

    private fun setBeep(b: AlertEngine.Beep) {
        if (b == beep) return
        beep = b
        Log.i(TAG, "BEEP $b dist=${Radar.distanceM} speed=${Radar.speedKmh} limit=${Radar.limitKmh}")
        handler.removeCallbacks(beeper)
        if (b != AlertEngine.Beep.NONE) handler.post(beeper)
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, App.CHANNEL)
            .setContentTitle("SpeedLimitBot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(beeper)
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        carObserver?.let { o -> carConnection?.type?.removeObserver(o) }
        tts?.shutdown()
        tone?.release()
        Radar.running = false
        Radar.hasFix = false
        Radar.idle()
        super.onDestroy()
    }

    // Pre-30 LocationListener stubs.
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { stopSelf() }
    @Deprecated("legacy") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}

    companion object {
        private const val TAG = "Radar"
        private const val NOTE_ID = 1
        private const val ALERT_ID = 2
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, AlertService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AlertService::class.java))
        }
    }
}
