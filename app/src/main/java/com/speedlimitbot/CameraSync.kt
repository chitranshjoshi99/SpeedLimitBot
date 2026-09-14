package com.speedlimitbot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the camera set current from OpenStreetMap.
 *
 * There is no government or Google API that serves speed-camera locations, so this pulls from
 * Overpass, which is the only openly queryable source. It downloads a box around wherever the
 * driver actually is, merges it into the bundled set, and never shrinks coverage.
 *
 * Overpass is donated infrastructure, so the refresh is deliberately rare: only after moving
 * [REFRESH_M] from the last download or once the data is [MAX_AGE_MS] old, only on a validated
 * connection, and never more than one request at a time.
 */
object CameraSync {

    private const val TAG = "RadarSync"
    private const val PREF = "camera_sync"
    private const val SPAN_DEG = 0.45          // ~50 km each way
    const val REFRESH_M = 30_000.0
    const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    private val MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    private val busy = AtomicBoolean(false)
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-sync") }

    /** Cheap enough to call on every location fix; it does nothing almost every time. */
    fun maybeRefresh(ctx: Context, lat: Double, lon: Double) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val stale = System.currentTimeMillis() - p.getLong("at", 0L) > MAX_AGE_MS
        val moved = if (!p.contains("lat")) Double.MAX_VALUE else CameraDb.distance(
            p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble(), lat, lon
        )
        if (!stale && moved < REFRESH_M) return
        if (!online(ctx)) return
        if (!busy.compareAndSet(false, true)) return
        pool.execute {
            try {
                refresh(ctx, lat, lon)
            } catch (e: Exception) {
                Log.i(TAG, "sync failed, keeping what we have: $e")
            } finally {
                busy.set(false)
            }
        }
    }

    /** Shared with [UpdateCheck]: neither should touch the network on a captive-portal Wi-Fi. */
    internal fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun refresh(ctx: Context, lat: Double, lon: Double) {
        val body = "data=" + java.net.URLEncoder.encode(query(lat, lon), "UTF-8")
        for (mirror in MIRRORS) {
            val rows = runCatching { post(mirror, body) }.getOrNull() ?: continue
            val parsed = parse(rows)
            if (parsed.isEmpty()) continue           // a busy mirror answers with an HTML error
            merge(ctx, parsed)
            CameraDb.reload(ctx)
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putLong("at", System.currentTimeMillis())
                .putFloat("lat", lat.toFloat())
                .putFloat("lon", lon.toFloat())
                .apply()
            Log.i(TAG, "synced ${parsed.size} cameras from $mirror, db now ${CameraDb.get(ctx).size}")
            return
        }
        Log.i(TAG, "every mirror was busy")
    }

    private fun query(lat: Double, lon: Double): String {
        val bbox = "%.4f,%.4f,%.4f,%.4f".format(lat - SPAN_DEG, lon - SPAN_DEG, lat + SPAN_DEG, lon + SPAN_DEG)
        return """
            [out:csv(::lat,::lon,"maxspeed","highway";false)][timeout:90];
            (
              node["highway"="speed_camera"]($bbox);
              node["enforcement"="maxspeed"]($bbox);
              node["man_made"="surveillance"]["surveillance:zone"="traffic"]($bbox);
              node["man_made"="surveillance"]["surveillance:type"="ALPR"]($bbox);
            );
            out;
        """.trimIndent()
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("User-Agent", "SpeedLimitBot/1.0 (offline speed camera warner)")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** Overpass CSV is tab separated: lat, lon, maxspeed, highway. */
    private fun parse(text: String): List<String> = text.lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 4) return@mapNotNull null
        val la = p[0].toDoubleOrNull() ?: return@mapNotNull null
        val lo = p[1].toDoubleOrNull() ?: return@mapNotNull null
        val limit = Regex("^(\\d+)").find(p[2].trim())?.value?.toIntOrNull() ?: 0
        val kind = if (p[3].trim() == "speed_camera") "S" else "T"
        "%.6f,%.6f,%d,%s".format(la, lo, limit, kind)
    }.toList()

    /** Union with what is already downloaded — coverage grows, never shrinks. */
    private fun merge(ctx: Context, rows: List<String>) {
        val file = File(ctx.filesDir, CameraDb.CACHE)
        val all = LinkedHashSet<String>()
        if (file.exists()) runCatching { file.forEachLine { if (it.isNotEmpty()) all += it } }
        all += rows
        File(ctx.filesDir, CameraDb.CACHE + ".tmp").apply {
            writeText(all.joinToString("\n"))
            renameTo(file)
        }
    }
}
