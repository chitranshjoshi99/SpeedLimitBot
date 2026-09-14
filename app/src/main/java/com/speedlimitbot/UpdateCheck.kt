package com.speedlimitbot

import android.content.Context
import android.os.Handler
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
 * At most one request a day, only on a validated connection, and nothing is downloaded or
 * installed here: a newer tag just lights a banner that opens the release page. Every failure
 * is silent — an update check is never worth interrupting a drive for.
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

    class Release(val version: String, val url: String)

    fun maybeCheck(ctx: Context) {
        if (available != null) return
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("at", 0L) < EVERY_MS) return
        if (!CameraSync.online(ctx)) return
        if (!busy.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        pool.execute {
            try {
                val json = JSONObject(get(LATEST))
                p.edit().putLong("at", System.currentTimeMillis()).apply()
                val tag = json.optString("tag_name")
                val here = app.packageManager.getPackageInfo(app.packageName, 0).versionName
                    ?: return@execute
                if (!newer(tag, here)) return@execute
                val url = json.optString("html_url").ifEmpty { return@execute }
                Log.i(TAG, "update $tag available, running $here")
                Handler(Looper.getMainLooper()).post {
                    available = Release(tag.trimStart('v', 'V'), url)
                }
            } catch (e: Exception) {
                Log.i(TAG, "update check failed: $e")
            } finally {
                busy.set(false)
            }
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
