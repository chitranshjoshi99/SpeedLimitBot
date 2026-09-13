package com.speedlimitbot

import android.content.Context
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable camera set: the bundled asset merged with whatever [CameraSync] has downloaded.
 * Rebuilt off the main thread by [reload] whenever a sync lands.
 */
class CameraDb private constructor(
    private val lat: DoubleArray,
    private val lon: DoubleArray,
    private val limit: IntArray,
    private val speedCam: BooleanArray
) {
    val size get() = limit.size

    /**
     * Nearest camera within [maxMeters] that lies ahead of a vehicle at (myLat, myLon)
     * travelling on [heading] degrees. Returns null when nothing is ahead.
     */
    fun nearestAhead(myLat: Double, myLon: Double, heading: Float, maxMeters: Double): Hit? {
        val dLatWin = maxMeters / M_PER_DEG
        val cosLat = cos(Math.toRadians(myLat))
        val dLonWin = dLatWin / (if (cosLat < 0.01) 0.01 else cosLat)
        var best = -1
        var bestDist = maxMeters

        // ponytail: linear scan with a bbox reject. Sub-millisecond into the tens of
        // thousands of rows; a lat-banded index only pays off past a few hundred thousand.
        for (i in limit.indices) {
            if (abs(lat[i] - myLat) > dLatWin) continue
            if (abs(lon[i] - myLon) > dLonWin) continue
            val d = distance(myLat, myLon, lat[i], lon[i])
            if (d >= bestDist) continue
            if (abs(angleDiff(bearing(myLat, myLon, lat[i], lon[i]), heading)) > AHEAD_CONE) continue
            best = i
            bestDist = d
        }
        return if (best < 0) null else Hit(best, bestDist, limit[best], speedCam[best])
    }

    /** @param speedCamera false for a traffic camera, which may not enforce speed at all. */
    class Hit(val id: Int, val distance: Double, val limitKmh: Int, val speedCamera: Boolean)

    companion object {
        private const val M_PER_DEG = 111_320.0
        private const val AHEAD_CONE = 55f
        private const val EARTH_R = 6_371_000.0
        const val CACHE = "cameras_downloaded.csv"

        @Volatile private var instance: CameraDb? = null

        fun get(ctx: Context): CameraDb = instance ?: synchronized(this) {
            instance ?: load(ctx).also { instance = it }
        }

        /** Rebuilds from disk. Call off the main thread — it parses the whole set. */
        fun reload(ctx: Context) {
            val fresh = load(ctx)
            synchronized(this) { instance = fresh }
        }

        private fun load(ctx: Context): CameraDb {
            val asset = runCatching { ctx.assets.open("cameras.csv").bufferedReader().readLines() }
                .getOrDefault(emptyList())
            val cache = File(ctx.filesDir, CACHE)
            val downloaded = if (!cache.exists()) emptyList()
                else runCatching { cache.readLines() }.getOrDefault(emptyList())
            return build(asset.asSequence(), downloaded.asSequence())
        }

        /** Pure builder: later sources add cameras and fill in gaps, never remove any. */
        fun build(vararg sources: Sequence<String>): CameraDb {
            // A camera present in both the asset and the download is one camera.
            val seen = LinkedHashMap<Long, IntArray>(4096)
            fun parse(lines: Sequence<String>) {
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val p = line.split(',')
                    if (p.size < 3) continue
                    val la = p[0].trim().toDoubleOrNull() ?: continue
                    val lo = p[1].trim().toDoubleOrNull() ?: continue
                    val li = p[2].trim().toIntOrNull() ?: continue
                    val speed = p.size < 4 || p[3].trim() != "T"
                    // 1e5 is ~1 m, which is the same camera. Pack into separate halves of a
                    // long: an xor of overlapping bits silently merges distinct cameras once
                    // longitude passes 83.9 degrees, which is most of eastern India.
                    val key = (Math.round(la * 1e5) shl 32) or (Math.round(lo * 1e5) and 0xFFFFFFFFL)
                    val prev = seen[key]
                    if (prev == null) {
                        seen[key] = intArrayOf(
                            Math.round(la * 1e6).toInt(), Math.round(lo * 1e6).toInt(),
                            li, if (speed) 1 else 0
                        )
                    } else {
                        if (prev[2] == 0 && li > 0) prev[2] = li      // a known limit beats none
                        if (speed) prev[3] = 1                        // speed camera beats traffic
                    }
                }
            }
            sources.forEach(::parse)

            val n = seen.size
            val la = DoubleArray(n); val lo = DoubleArray(n)
            val li = IntArray(n); val sc = BooleanArray(n)
            seen.values.forEachIndexed { i, v ->
                la[i] = v[0] / 1e6; lo[i] = v[1] / 1e6; li[i] = v[2]; sc[i] = v[3] == 1
            }
            return CameraDb(la, lo, li, sc)
        }

        /** Great-circle distance in metres (haversine). */
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val s = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_R * atan2(sqrt(s), sqrt(1 - s))
        }

        /** Initial bearing in degrees, 0..360. */
        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
        }

        /** Signed smallest difference between two bearings, -180..180. */
        fun angleDiff(a: Float, b: Float): Float {
            var d = (a - b + 540f) % 360f - 180f
            if (d == -180f) d = 180f
            return d
        }
    }
}
