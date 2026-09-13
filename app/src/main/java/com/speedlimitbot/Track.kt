package com.speedlimitbot

/**
 * Turns a stream of raw fixes into speed + heading.
 *
 * Providers lie in three ways this has to survive: they repeat the last position verbatim,
 * they drop out and resume with a jump (tunnel, app resume, emulator), and many of them
 * report neither speed nor bearing. Holding state through a bad fix matters — clearing on
 * every hiccup re-announces the same camera.
 */
class Track {

    class Fix(val speedMps: Float, val heading: Float)

    private var lat = Double.NaN
    private var lon = 0.0
    private var time = 0L
    private var movedAt = 0L
    private var heading = Float.NaN
    private var smoothed = Float.NaN

    /** @return the fix to act on, or null when this sample says nothing (hold the last state). */
    fun update(lat: Double, lon: Double, timeMs: Long, provSpeed: Float?, provBearing: Float?): Fix? {
        val prevLat = this.lat
        val prevLon = this.lon
        val dt = (timeMs - time) / 1000f
        this.lat = lat; this.lon = lon; this.time = timeMs

        if (prevLat.isNaN() || dt <= 0f) { movedAt = timeMs; return null }

        val moved = CameraDb.distance(prevLat, prevLon, lat, lon)

        // Gap or teleport: the pair teaches nothing, so resync silently and keep the
        // current alert state. Clearing here would re-announce a camera already announced.
        if (moved / dt > MAX_MPS) {
            movedAt = timeMs
            smoothed = Float.NaN
            return null
        }

        if (moved >= MIN_TRACK_M) {
            movedAt = timeMs
            heading = provBearing ?: CameraDb.bearing(prevLat, prevLon, lat, lon)
            return Fix(smooth(provSpeed ?: (moved / dt).toFloat()), heading)
        }

        // Not moving: a stale or duplicate fix at first, a genuine stop once it persists.
        if (timeMs - movedAt < STOP_GRACE_MS) return null
        heading = Float.NaN
        smoothed = Float.NaN
        return Fix(0f, Float.NaN)
    }

    /**
     * Fix intervals jitter, so a raw delta-over-dt speed swings wildly between samples and
     * makes the over-limit beep chatter. One pole of low-pass is enough to settle it.
     */
    private fun smooth(v: Float): Float {
        val prev = smoothed
        val next = if (prev.isNaN()) v else prev + ALPHA * (v - prev)
        smoothed = next
        return next
    }

    companion object {
        private const val ALPHA = 0.35f
        /** 252 km/h — above this the pair is a provider jump, not a car. */
        const val MAX_MPS = 70.0
        const val MIN_TRACK_M = 4.0
        const val STOP_GRACE_MS = 5_000L
    }
}
