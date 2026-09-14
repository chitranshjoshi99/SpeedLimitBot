package com.speedlimitbot

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
    private var audio: AlertAudio? = null
    private var beep = AlertEngine.Beep.NONE
    private val track = Track()
    private var noteText = ""
    private var bestProvider: String? = null
    private var bestRank = 0
    private var bestAt = 0L

    /** Runtime-registered so the Quit action needs no exported component. */
    private val quitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "QUIT from notification")
            stopSelf()
        }
    }
    private var armed: CameraDb.Hit? = null
    private var carConnection: CarConnection? = null
    private var carObserver: Observer<Int>? = null
    private var sawCar = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, NOTE_ID, notification("Waiting for GPS"),
            if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        registerReceiver(
            quitReceiver, IntentFilter(ACTION_QUIT),
            if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        )
        audio = AlertAudio(this)
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

    /**
     * GPS alone was a mistake: it needs open sky and a cold start can take minutes, so the
     * service sat on "Waiting for GPS" indefinitely indoors. Prefer the fused provider, which
     * merges GPS, wifi, cell and sensors; fall back to GPS plus network on older phones. Seed
     * from the last known fix so the app says something truthful immediately.
     */
    private fun startGps() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { stopSelf(); return }

        val lm = getSystemService(LocationManager::class.java)
        val available = runCatching { lm.allProviders }.getOrDefault(emptyList())
        // Every provider that will have us, rather than betting on one. Fused is the best
        // source when it works, but it is backed by Play services and returns nothing on some
        // devices and emulators; GPS is the reliable floor. Network covers the cold start.
        val wanted = buildList {
            if (fine && Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { available.contains(it) }

        if (wanted.isEmpty()) { Log.i(TAG, "no usable location provider"); return }
        Log.i(TAG, "providers=$wanted")

        // A recent last-known fix means the notification never has to lie about waiting.
        wanted.firstNotNullOfOrNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }?.let { seed ->
            if (System.currentTimeMillis() - seed.time < SEED_MAX_AGE_MS) onLocationChanged(seed)
        }

        wanted.forEach { p ->
            runCatching { lm.requestLocationUpdates(p, 1000L, 0f, this) }
                .onFailure { Log.i(TAG, "requestLocationUpdates($p) failed: $it") }
        }
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
        // Several providers are registered so one of them is always working, but their output
        // must not be mixed: fused and GPS disagree by metres, network by hundreds, and the
        // zig-zag inflates speed. Rank them and ignore anything worse than what is currently
        // arriving. "First to speak wins" was tried and is wrong — a provider can republish one
        // stale cached fix every second and lock out the one actually tracking the car.
        val now = SystemClock.elapsedRealtime()
        val rank = when (loc.provider) {
            LocationManager.GPS_PROVIDER -> 3
            LocationManager.FUSED_PROVIDER -> 2
            LocationManager.NETWORK_PROVIDER -> 1
            else -> 0
        }
        if (rank < bestRank && now - bestAt < LEADER_STALE_MS) return
        if (loc.provider != bestProvider) {
            Log.i(TAG, "following ${loc.provider} acc=${loc.accuracy}m")
            bestProvider = loc.provider
        }
        bestRank = rank
        bestAt = now

        Radar.hasFix = true
        // Refresh on any position at all — waiting for a valid heading would mean a phone
        // sitting still in traffic never updates its map.
        CameraSync.maybeRefresh(this, loc.latitude, loc.longitude)

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
        rememberIfUnknown(hit)

        Radar.limitKmh = hit.limitKmh
        Radar.distanceM = hit.distance.toInt()
        Radar.over = hit.limitKmh > 0 && Radar.speedKmh > hit.limitKmh + AlertEngine.OVER_TOLERANCE_KMH

        val out = engine.update(hit.id, hit.distance, fix.speedMps, hit.limitKmh)
        if (out.announce) {
            speak(out.limitKmh, hit.speedCamera)
            carAlert(out.limitKmh, hit.distance.toInt(), hit.speedCamera)
        }
        setBeep(out.beep)
        updateNotification()
        Radar.onChange?.invoke()
    }

    /** Passing a camera with no known limit is the moment worth asking the driver about it. */
    private fun rememberIfUnknown(hit: CameraDb.Hit?) {
        val was = armed
        armed = hit
        if (was == null || was.id == hit?.id) return
        if (was.limitKmh == 0) Radar.pending = Radar.Unknown(was.lat, was.lon, was.speedCamera)
    }

    private fun standDown() {
        rememberIfUnknown(null)
        engine.clear()
        setBeep(AlertEngine.Beep.NONE)
        Radar.idle()
        updateNotification()
        Radar.onChange?.invoke()
    }

    /**
     * The closest thing Android Auto has to an overlay: a navigation heads-up notification,
     * which the car draws over whatever is on screen — Google Maps included. There is no
     * floating-window API on the car display, so this is the whole mechanism.
     */
    private fun carAlert(limit: Int, metres: Int, speedCamera: Boolean) {
        if (!notificationsAllowed()) return
        val what = if (speedCamera) "Speed camera" else "Traffic camera"
        val note = NotificationCompat.Builder(this, App.CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(if (limit > 0) "$what · limit $limit" else "$what ahead")
            .setContentText("$metres m ahead")
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOnlyAlertOnce(true)
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(if (limit > 0) "$what · $limit" else "$what ahead")
                    .setContentText("$metres m")
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build()
            )
            .build()
        NotificationManagerCompat.from(this).notify(ALERT_ID, note)
    }

    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun speak(limit: Int, speedCamera: Boolean) {
        // Duck the music for the announcement even when no beep is running yet.
        audio?.takeFocus()
        val what = if (speedCamera) "Speed camera ahead" else "Traffic camera ahead"
        val words = if (limit > 0) "$what. Limit $limit" else what
        val r = tts?.speak(words, TextToSpeech.QUEUE_FLUSH, null, "cam")
        Log.i(TAG, "SPEAK limit=$limit dist=${Radar.distanceM} speed=${Radar.speedKmh} speedCam=$speedCamera result=$r")
    }

    private fun setBeep(b: AlertEngine.Beep) {
        if (b == beep) return
        beep = b
        Log.i(TAG, "BEEP $b dist=${Radar.distanceM} speed=${Radar.speedKmh} limit=${Radar.limitKmh}")
        audio?.setPattern(b)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val quit = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_QUIT).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, App.CHANNEL)
            .setContentTitle("SpeedLimitBot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Quit", quit).build())
            .setOngoing(true)
            .build()
    }

    /** Keeps the shade honest about what the service is doing, without redrawing every fix. */
    private fun updateNotification() {
        val text = when {
            !Radar.hasFix -> "Acquiring GPS"
            Radar.distanceM < 0 -> "Watching — no camera ahead"
            Radar.limitKmh > 0 -> "Camera ${Radar.distanceM} m · limit ${Radar.limitKmh}"
            else -> "Camera ${Radar.distanceM} m"
        }
        if (text == noteText) return
        noteText = text
        if (notificationsAllowed()) {
            NotificationManagerCompat.from(this).notify(NOTE_ID, notification(text))
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(quitReceiver) }
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        carObserver?.let { o -> carConnection?.type?.removeObserver(o) }
        tts?.shutdown()
        audio?.release()
        Radar.running = false
        Radar.hasFix = false
        Radar.idle()
        super.onDestroy()
    }

    // Pre-30 LocationListener stubs.
    override fun onProviderEnabled(provider: String) {}

    /**
     * A provider dropping is a tunnel or a momentary glitch, not a reason to quit: stopping here
     * and being restarted by START_STICKY makes the service flap. Report no fix and wait.
     */
    override fun onProviderDisabled(provider: String) {
        Radar.hasFix = false
        standDown()
    }
    @Deprecated("legacy") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}

    companion object {
        private const val TAG = "Radar"
        private const val ACTION_QUIT = "com.speedlimitbot.QUIT"
        private const val NOTE_ID = 1
        private const val LEADER_STALE_MS = 10_000L
        private const val SEED_MAX_AGE_MS = 5 * 60_000L
        private const val ALERT_ID = 2
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, AlertService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AlertService::class.java))
        }
    }
}
