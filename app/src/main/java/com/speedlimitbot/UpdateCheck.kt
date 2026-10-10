package com.speedlimitbot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import androidx.core.content.ContextCompat
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sideloaded builds get no Play updates, so the app asks GitHub itself.
 *
 * At most one request a day, only on a validated connection. A newer tag lights a banner;
 * tapping it downloads and installs through [install], so nothing happens mid-drive unasked.
 * A failed background check is silent — it is never worth interrupting a drive for.
 */
object UpdateCheck {

    private const val TAG = "RadarUpdate"
    private const val PREF = "update_check"
    private const val LATEST =
        "https://api.github.com/repos/chitranshjoshi99/SpeedLimitBot/releases/latest"
    private const val EVERY_MS = 24L * 3600 * 1000

    /** Set once a newer release is found; the UI watches it. */
    var available by mutableStateOf<Release?>(null)
        private set

    /** @param apkUrl the signed APK attached to the release by CI. */
    class Release(val version: String, val apkUrl: String)

    /**
     * Downloads the release APK and hands it to the system installer. The driver sees one
     * "Update?" confirmation instead of a browser, a downloads folder and a file manager.
     *
     * The platform checks the new APK is signed with the installed app's key, so a tampered
     * download is refused rather than installed. Once this app has installed itself it is the
     * installer of record, and on Android 12+ later updates may go through with no prompt.
     */
    fun install(ctx: Context, release: Release, done: (String) -> Unit) {
        if (!CameraSync.online(ctx)) { done("No internet"); return }
        if (!busy.compareAndSet(false, true)) { done("Already downloading"); return }
        val app = ctx.applicationContext
        pool.execute {
            val msg = try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(app.packageName) }
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                // A half-written session would sit on disk until the system reaps it days later.
                installer.openSession(id).use { session ->
                  try {
                    val c = URL(release.apkUrl).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 15_000
                        c.readTimeout = 60_000
                        c.setRequestProperty("User-Agent", "SpeedLimitBot (offline speed camera warner)")
                        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                        session.openWrite("base.apk", 0, c.contentLengthLong).use { out ->
                            c.inputStream.use { it.copyTo(out) }
                            session.fsync(out)
                        }
                    } finally {
                        c.disconnect()
                    }
                    listenForResult(app)
                    session.commit(
                        PendingIntent.getBroadcast(
                            app, id, Intent(ACTION_INSTALL).setPackage(app.packageName),
                            // Mutable: the installer writes the status into the extras.
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        ).intentSender
                    )
                  } catch (e: Exception) {
                    session.abandon()
                    throw e
                  }
                }
                Log.i(TAG, "update ${release.version} handed to the installer")
                "Installing ${release.version}…"
            } catch (e: Exception) {
                Log.i(TAG, "update install failed: $e")
                "Update download failed"
            } finally {
                busy.set(false)
            }
            Handler(Looper.getMainLooper()).post { done(msg) }
        }
    }

    private const val ACTION_INSTALL = "com.speedlimitbot.UPDATE_INSTALL"
    private var listening = false

    /** Registered on first use; the install replaces this process, so it never needs removing. */
    @Synchronized
    private fun listenForResult(app: Context) {
        if (listening) return
        listening = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                        // The system's own confirm screen; it also walks the user through
                        // allowing installs from this app the first time.
                        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> Unit   // this process is about to be replaced
                    else -> {
                        val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        Log.i(TAG, "install ended status=$status $why")
                        Say.text = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled"
                            else "Update failed"
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(ACTION_INSTALL), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Background check on app open: at most one a day, and it says nothing when it fails. */
    fun maybeCheck(ctx: Context) {
        if (available != null) return
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("at", 0L) < EVERY_MS) return
        check(ctx) {}
    }

    /**
     * The same check, asked for from the menu: no daily throttle, and [done] is always called on
     * the main thread with something to show the person who tapped.
     */
    fun check(ctx: Context, done: (String) -> Unit) {
        if (!SELF_UPDATE) { done("Updates come from Play"); return }
        if (!CameraSync.online(ctx)) { done("No internet"); return }
        if (!busy.compareAndSet(false, true)) { done("Already checking"); return }
        val app = ctx.applicationContext
        pool.execute {
            var msg = "Update check failed"
            try {
                val json = JSONObject(get(LATEST))
                app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putLong("at", System.currentTimeMillis()).apply()
                val tag = json.optString("tag_name")
                val here = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
                val assets = json.optJSONArray("assets")
                val apk = (0 until (assets?.length() ?: 0))
                    .map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
                    ?.optString("browser_download_url").orEmpty()
                // CI attaches the APK a minute or two after the release is published; until it
                // lands there is nothing to install, so say nothing yet.
                msg = if (newer(tag, here) && apk.isNotEmpty()) {
                    Log.i(TAG, "update $tag available, running $here")
                    Handler(Looper.getMainLooper()).post {
                        available = Release(tag.trimStart('v', 'V'), apk)
                    }
                    "Update $tag available · tap the banner"
                } else {
                    "Up to date — v$here"
                }
            } catch (e: Exception) {
                Log.i(TAG, "update check failed: $e")
            } finally {
                busy.set(false)
            }
            Handler(Looper.getMainLooper()).post { done(msg) }
        }
    }

    /**
     * True when [remote] is a strictly higher dotted version than [local]. Compared segment by
     * segment as numbers, so 1.0.10 beats 1.0.9 — a string compare would get that backwards.
     * Anything unparseable counts as 0, which can only ever make a release look older.
     */
    fun newer(remote: String, local: String): Boolean {
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parts(v: String): List<Int> = v.trim().trimStart('v', 'V')
        .split('.')
        .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private val busy = AtomicBoolean(false)
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "update-check") }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "SpeedLimitBot (offline speed camera warner)")
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}
