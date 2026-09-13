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

    class Out(val beep: Beep, val announceLimit: Int)

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
     * @param limitKmh posted limit at that camera
     * @return beep state plus the limit to speak (0 = say nothing)
     */
    fun update(id: Int, distance: Double, speedMps: Float, limitKmh: Int): Out {
        if (id != armedId) {
            armedId = id
            announced = false
        }
        if (speedMps < MIN_MOVING_MPS) return Out(Beep.NONE, 0)

        val eta = distance / speedMps
        val over = speedMps * 3.6f > limitKmh + OVER_TOLERANCE_KMH

        var speak = 0
        if (!announced && eta <= ANNOUNCE_S) {
            announced = true
            speak = limitKmh
        }

        val beep = when {
            eta > ANNOUNCE_S -> Beep.NONE
            over -> Beep.FAST
            eta <= CLOSE_S -> Beep.SLOW
            else -> Beep.NONE
        }
        return Out(beep, speak)
    }

    companion object {
        const val ANNOUNCE_S = 60.0
        const val CLOSE_S = 10.0
        const val OVER_TOLERANCE_KMH = 3f
        const val MIN_MOVING_MPS = 2f
        const val RANGE_M = 3000.0
    }
}
