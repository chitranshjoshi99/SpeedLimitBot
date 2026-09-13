package com.speedlimitbot

/**
 * Pure alert state machine. No Android types so it stays trivially testable.
 *
 * Timeline for one camera, driving towards it:
 *   eta <= ANNOUNCE_S  -> speak the limit once
 *   eta <= CLOSE_S     -> slow beep
 *   over the limit     -> fast beep (wins over slow)
 */
class AlertEngine {

    enum class Beep { NONE, SLOW, FAST }

    /** @param limitKmh 0 when the dataset has no limit for this camera. */
    class Out(val beep: Beep, val announce: Boolean, val limitKmh: Int)

    private var armedId = -1
    private var announced = false

    /** Call when no camera is ahead any more. */
    fun clear() {
        armedId = -1
        announced = false
    }

    /**
     * @param id       camera identity; a change re-arms the announcement
     * @param distance metres to the camera
     * @param speedMps current ground speed
     * @param limitKmh posted limit at that camera, or 0 when the dataset does not know it
     * @return beep state plus whether to speak now
     */
    fun update(id: Int, distance: Double, speedMps: Float, limitKmh: Int): Out {
        if (id != armedId) {
            armedId = id
            announced = false
        }
        if (speedMps < MIN_MOVING_MPS) return Out(Beep.NONE, false, limitKmh)

        val eta = distance / speedMps
        // An unknown limit cannot be exceeded — warn about the camera, stay quiet about speed.
        val over = limitKmh > 0 && speedMps * 3.6f > limitKmh + OVER_TOLERANCE_KMH

        val speak = !announced && eta <= ANNOUNCE_S
        if (speak) announced = true

        // Latched on `announced`, not on eta: a fix that nudges the estimate back over 60 s
        // must not blink the beep off. Once a camera is announced it stays live until passed.
        val beep = when {
            !announced -> Beep.NONE
            over -> Beep.FAST
            eta <= CLOSE_S -> Beep.SLOW
            else -> Beep.NONE
        }
        return Out(beep, speak, limitKmh)
    }

    companion object {
        const val ANNOUNCE_S = 60.0
        const val CLOSE_S = 10.0
        const val OVER_TOLERANCE_KMH = 3f
        const val MIN_MOVING_MPS = 2f
        const val RANGE_M = 3000.0
    }
}
