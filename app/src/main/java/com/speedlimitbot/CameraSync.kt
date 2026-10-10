package com.speedlimitbot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.JsonReader
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
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
    // v2 rows carry the roads beside each camera. A new name makes every install re-download
    // on its next fix instead of waiting out a week-old throttle on rows without them.
    private const val PREF = "camera_sync_v2"
    private const val SPAN_DEG = 0.45          // ~50 km each way
    const val REFRESH_M = 30_000.0
    const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    private val MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    private var lastOfflineLog = 0L
    @Volatile private var lastFailAt = 0L
    private const val RETRY_MS = 10 * 60_000L
    private const val BUSY = "Every mirror was busy, try later"
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
        // Every mirror busy leaves the throttle untouched; without this the next fix asks again.
        if (SystemClock.elapsedRealtime() - lastFailAt < RETRY_MS && lastFailAt > 0) return
        if (!online(ctx)) {
            if (SystemClock.elapsedRealtime() - lastOfflineLog > 60_000L) {
                lastOfflineLog = SystemClock.elapsedRealtime()
                Log.i(TAG, "offline, keeping the cameras already on disk")
            }
            return
        }
        if (!busy.compareAndSet(false, true)) return
        Log.i(TAG, "refreshing around %.4f,%.4f".format(lat, lon))
        pool.execute {
            try {
                if (refresh(ctx, lat, lon) == BUSY) lastFailAt = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                lastFailAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "sync failed, keeping what we have: $e")
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * The same download asked for from the menu: no distance or age throttle, because someone
     * tapped. [done] always runs on the main thread with something worth showing them.
     */
    fun refreshNow(ctx: Context, done: (String) -> Unit) {
        val lat = Radar.lat
        val lon = Radar.lon
        if (lat.isNaN()) { done("No GPS fix yet"); return }
        if (!online(ctx)) { done("No internet"); return }
        if (!busy.compareAndSet(false, true)) { done("Already refreshing"); return }
        val app = ctx.applicationContext
        pool.execute {
            val msg = try {
                refresh(app, lat, lon)
            } catch (e: Exception) {
                Log.i(TAG, "manual refresh failed: $e")
                "Refresh failed"
            } finally {
                busy.set(false)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(msg) }
        }
    }

    /** Shared with [UpdateCheck]: neither should touch the network on a captive-portal Wi-Fi. */
    internal fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** @return a line fit to show a driver who asked for this; the automatic path ignores it. */
    private fun refresh(ctx: Context, lat: Double, lon: Double): String {
        val before = CameraDb.get(ctx).size
        val body = "data=" + java.net.URLEncoder.encode(query(lat, lon), "UTF-8")
        for (mirror in MIRRORS) {
            // A busy mirror answers with an HTML error, which fails the parse.
            val parsed = runCatching { post(mirror, body) }
                .onFailure { Log.i(TAG, "$mirror: $it") }.getOrNull() ?: continue
            if (parsed.isEmpty()) continue
            merge(ctx, parsed)
            CameraDb.reload(ctx)
            OsmAnd.push(ctx, lat, lon)       // keeps OsmAnd's map in step; a no-op without it
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putLong("at", System.currentTimeMillis())
                .putFloat("lat", lat.toFloat())
                .putFloat("lon", lon.toFloat())
                .apply()
            val after = CameraDb.get(ctx).size
            Log.i(TAG, "synced ${parsed.size} cameras from $mirror, db now $after")
            return if (after > before) "${after - before} new cameras · $after total"
                else "Already up to date · $after cameras"
        }
        Log.i(TAG, "every mirror was busy")
        return BUSY
    }

    private fun query(fixLat: Double, fixLon: Double): String {
        // Snapped to a 0.1° (~11 km) grid so the box never reveals where the driver is, only the
        // region — which is what the privacy policy and Play's Data safety form promise. The
        // span still reaches at least 0.4° past the driver on every side.
        val lat = Math.round(fixLat * 10) / 10.0
        val lon = Math.round(fixLon * 10) / 10.0
        val bbox = "%.4f,%.4f,%.4f,%.4f".format(Locale.US, lat - SPAN_DEG, lon - SPAN_DEG, lat + SPAN_DEG, lon + SPAN_DEG)
        // Most Indian surveillance nodes carry no zone or type tag at all — filtering on those
        // missed roughly 40% of them, including whole neighbourhoods. Proximity to a real road
        // is the honest test of whether a camera watches traffic, and it drops building CCTV.
        // The roads beside each camera come back too: their direction and class are what tell
        // a camera on this carriageway from one on the other, and predict an unmapped limit.
        return """
            [out:json][timeout:180];
            way["highway"~"^(motorway|trunk|primary|secondary|tertiary)${'$'}"]($bbox)->.roads;
            (
              node["highway"="speed_camera"]($bbox);
              node["enforcement"="maxspeed"]($bbox);
            )->.cams;
            node["man_made"="surveillance"]["surveillance"!="indoor"]["surveillance:zone"!="building"]($bbox)->.watch;
            (
              .cams;
              node.watch(around.roads:${NEAR_M.toInt()});
            )->.all;
            .all out;
            way.roads(around.all:${NEAR_M.toInt()});
            out tags geom;
        """.trimIndent()
    }

    /** A road beside a camera. [oneway] is 1 along the geometry, -1 against it, 0 both ways. */
    class Road(val cls: Char, val oneway: Int, val maxKmh: Int, val lat: DoubleArray, val lon: DoubleArray)

    private const val NEAR_M = 25.0
    private const val SAME_AXIS = 30f

    /**
     * Which headings drive past a camera, as the CSV field `CameraDb` reads: `87P;267P;175S40`.
     *
     * Every road segment within [NEAR_M] is a candidate, nearest first, and only the nearest per
     * axis is kept — so on a divided road the carriageway the camera stands beside wins, and
     * its one-way direction excludes the other side. `camera:direction` (on 64% of Bengaluru's
     * cameras, and within 15° of the road axis for 72% of those) rules out the cross street at a
     * junction. Mapped with no road nearby, the direction alone still gives the axis.
     */
    fun approaches(camLat: Double, camLon: Double, camDir: Float?, roads: List<Road>): String {
        class Seg(val d: Double, val bearing: Float, val road: Road)
        val kx = M_PER_DEG * kotlin.math.cos(Math.toRadians(camLat))
        val win = NEAR_M / M_PER_DEG * 2
        val segs = ArrayList<Seg>()
        for (r in roads) {
            for (j in 0 until r.lat.size - 1) {
                if (minOf(r.lat[j], r.lat[j + 1]) > camLat + win || maxOf(r.lat[j], r.lat[j + 1]) < camLat - win) continue
                val ax = (r.lon[j] - camLon) * kx; val ay = (r.lat[j] - camLat) * M_PER_DEG
                val bx = (r.lon[j + 1] - camLon) * kx; val by = (r.lat[j + 1] - camLat) * M_PER_DEG
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                if (len2 == 0.0) continue
                val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val d = kotlin.math.hypot(ax + t * dx, ay + t * dy)
                if (d > NEAR_M) continue
                val b = ((Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360) % 360).toFloat()
                if (camDir != null && axisDiff(b, camDir) > SAME_AXIS) continue
                segs += Seg(d, b, r)
            }
        }
        segs.sortBy { it.d }
        val kept = ArrayList<Seg>()
        for (s in segs) if (kept.none { axisDiff(it.bearing, s.bearing) < SAME_AXIS }) kept += s

        val out = ArrayList<String>()
        for (s in kept) {
            val tag = "${s.road.cls}${if (s.road.maxKmh > 0) s.road.maxKmh else ""}"
            val b = Math.round(s.bearing) % 360
            if (s.road.oneway >= 0) out += "$b$tag"
            if (s.road.oneway <= 0) out += "${(b + 180) % 360}$tag"
        }
        if (out.isEmpty() && camDir != null) {
            val b = Math.round(camDir) % 360
            out += "$b"; out += "${(b + 180) % 360}"
        }
        return out.joinToString(";")
    }

    /** Difference between two undirected axes, 0..90. */
    private fun axisDiff(a: Float, b: Float): Float {
        val d = kotlin.math.abs(CameraDb.angleDiff(a, b))
        return if (d > 90f) 180f - d else d
    }

    private const val M_PER_DEG = 111_320.0

    /** @return camera rows; the JSON is streamed, since a city's roads run to megabytes. */
    private fun post(url: String, body: String): List<String> {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 180_000
            c.doOutput = true
            c.setRequestProperty("User-Agent", "SpeedLimitBot/1.0 (offline speed camera warner)")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use(::parse)
        } finally {
            c.disconnect()
        }
    }

    /** Overpass JSON: camera nodes, then the major roads beside them with their geometry. */
    private fun parse(input: java.io.Reader): List<String> {
        class Cam(val lat: Double, val lon: Double, val tags: Map<String, String>)
        val cams = ArrayList<Cam>()
        val roads = ArrayList<Road>()
        JsonReader(input).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "elements") { r.skipValue(); continue }
                r.beginArray()
                while (r.hasNext()) {
                    var type = ""
                    var lat = Double.NaN
                    var lon = Double.NaN
                    val tags = HashMap<String, String>()
                    val gLat = ArrayList<Double>()
                    val gLon = ArrayList<Double>()
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "type" -> type = r.nextString()
                        "lat" -> lat = r.nextDouble()
                        "lon" -> lon = r.nextDouble()
                        "tags" -> {
                            r.beginObject()
                            while (r.hasNext()) tags[r.nextName()] = r.nextString()
                            r.endObject()
                        }
                        "geometry" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginObject()
                                while (r.hasNext()) when (r.nextName()) {
                                    "lat" -> gLat += r.nextDouble()
                                    "lon" -> gLon += r.nextDouble()
                                    else -> r.skipValue()
                                }
                                r.endObject()
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                    r.endObject()
                    if (type == "node" && !lat.isNaN()) cams += Cam(lat, lon, tags)
                    if (type == "way" && gLat.size >= 2 && gLat.size == gLon.size) roads += Road(
                        cls = ROAD_CLASS[tags["highway"]] ?: continue,
                        oneway = when (tags["oneway"]) {
                            "yes", "true", "1" -> 1
                            "-1", "reverse" -> -1
                            // Motorways are one carriageway per way whether tagged or not.
                            else -> if (tags["highway"] == "motorway") 1 else 0
                        },
                        maxKmh = kmh(tags["maxspeed"]),
                        lat = gLat.toDoubleArray(),
                        lon = gLon.toDoubleArray()
                    )
                }
                r.endArray()
            }
        }
        return cams.map { cam ->
            val dir = (cam.tags["camera:direction"] ?: cam.tags["direction"])?.toFloatOrNull()
            String.format(
                Locale.US, "%.6f,%.6f,%d,%s,%s",
                cam.lat, cam.lon, kmh(cam.tags["maxspeed"]),
                if (cam.tags["highway"] == "speed_camera") "S" else "T",
                approaches(cam.lat, cam.lon, dir, roads)
            )
        }
    }

    private val ROAD_CLASS = mapOf(
        "motorway" to 'M', "trunk" to 'T', "primary" to 'P', "secondary" to 'S', "tertiary" to 'R'
    )

    private fun kmh(v: String?): Int = v?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

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
