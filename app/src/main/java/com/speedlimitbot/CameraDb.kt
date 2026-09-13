package com.speedlimitbot

import android.content.Context
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable speed-camera set, loaded once from assets/cameras.csv (lat,lon,limitKmh). */
class CameraDb private constructor(
    private val lat: DoubleArray,
    private val lon: DoubleArray,
    private val limit: IntArray
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

        // ponytail: linear scan with a bbox reject. ~50k rows at 1 Hz is sub-millisecond.
        // Swap in a lat-banded index only if the dataset grows past a few hundred thousand.
        for (i in limit.indices) {
            if (abs(lat[i] - myLat) > dLatWin) continue
            if (abs(lon[i] - myLon) > dLonWin) continue
            val d = distance(myLat, myLon, lat[i], lon[i])
            if (d >= bestDist) continue
            if (abs(angleDiff(bearing(myLat, myLon, lat[i], lon[i]), heading)) > AHEAD_CONE) continue
            best = i
            bestDist = d
        }
        return if (best < 0) null else Hit(best, bestDist, limit[best])
    }

    fun distanceTo(id: Int, myLat: Double, myLon: Double) = distance(myLat, myLon, lat[id], lon[id])

    class Hit(val id: Int, val distance: Double, val limitKmh: Int)

    companion object {
        private const val M_PER_DEG = 111_320.0
        private const val AHEAD_CONE = 55f
        private const val EARTH_R = 6_371_000.0

        @Volatile private var instance: CameraDb? = null

        fun get(ctx: Context): CameraDb = instance ?: synchronized(this) {
            instance ?: load(ctx).also { instance = it }
        }

        private fun load(ctx: Context): CameraDb {
            val la = ArrayList<Double>(1024)
            val lo = ArrayList<Double>(1024)
            val li = ArrayList<Int>(1024)
            runCatching {
                ctx.assets.open("cameras.csv").bufferedReader().forEachLine { line ->
                    if (line.isEmpty() || line[0] == '#') return@forEachLine
                    val p = line.split(',')
                    if (p.size < 3) return@forEachLine
                    val a = p[0].trim().toDoubleOrNull() ?: return@forEachLine
                    val b = p[1].trim().toDoubleOrNull() ?: return@forEachLine
                    val c = p[2].trim().toIntOrNull() ?: return@forEachLine
                    la += a; lo += b; li += c
                }
            }
            return CameraDb(la.toDoubleArray(), lo.toDoubleArray(), li.toIntArray())
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
