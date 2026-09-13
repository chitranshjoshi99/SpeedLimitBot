package com.speedlimitbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {

    /** 20 m/s = 72 km/h. */
    @Test fun announcesOnceAtOneMinute() {
        val e = AlertEngine()
        assertFalse(e.update(1, 1400.0, 20f, 80).announce)   // eta 70 s
        assertTrue(e.update(1, 1100.0, 20f, 80).announce)    // eta 55 s
        assertFalse(e.update(1, 900.0, 20f, 80).announce)    // already spoken
    }

    @Test fun slowBeepInsideTenSeconds() {
        val e = AlertEngine()
        assertEquals(AlertEngine.Beep.NONE, e.update(1, 1000.0, 20f, 80).beep)  // eta 50 s
        assertEquals(AlertEngine.Beep.SLOW, e.update(1, 180.0, 20f, 80).beep)   // eta 9 s
    }

    /** A jittery estimate crossing back over 60 s must not blink the beep off. */
    @Test fun beepDoesNotFlapAtTheWindowEdge() {
        val e = AlertEngine()
        assertEquals(AlertEngine.Beep.FAST, e.update(1, 2000.0, 35f, 80).beep)   // eta 57 s
        assertEquals(AlertEngine.Beep.FAST, e.update(1, 2217.0, 35f, 80).beep)   // eta 63 s
        assertEquals(AlertEngine.Beep.FAST, e.update(1, 2100.0, 35f, 80).beep)
    }

    @Test fun fastBeepWhenOverLimit() {
        val e = AlertEngine()
        assertEquals(AlertEngine.Beep.FAST, e.update(1, 1000.0, 25f, 50).beep)  // 90 in a 50
    }

    @Test fun stoppedIsSilent() {
        val e = AlertEngine()
        assertEquals(AlertEngine.Beep.NONE, e.update(1, 50.0, 0f, 50).beep)
    }

    @Test fun newCameraReArms() {
        val e = AlertEngine()
        assertTrue(e.update(1, 500.0, 20f, 50).announce)
        assertTrue(e.update(2, 500.0, 20f, 60).announce)
    }

    /** 81 of the 522 shipped cameras have no known limit; they must still warn. */
    @Test fun unknownLimitWarnsButNeverCallsYouSpeeding() {
        val e = AlertEngine()
        val out = e.update(1, 1000.0, 40f, 0)   // 144 km/h past an unknown limit
        assertTrue(out.announce)
        assertEquals(0, out.limitKmh)
        assertEquals(AlertEngine.Beep.NONE, out.beep)
        assertEquals(AlertEngine.Beep.SLOW, e.update(1, 200.0, 40f, 0).beep)
    }

    @Test fun geoMathIsSane() {
        val d = CameraDb.distance(12.971599, 77.594566, 12.978000, 77.600000)
        assertTrue("expected ~900 m, got $d", d > 800 && d < 1000)
        val b = CameraDb.bearing(0.0, 0.0, 1.0, 0.0)
        assertEquals(0f, b, 0.5f)
        assertEquals(-90f, CameraDb.angleDiff(10f, 100f), 0.01f)
    }
}

class TrackTest {

    /** ~15 m north each second at 12.97 N. */
    private fun north(step: Int) = 12.971599 - step * 0.00013475

    @Test fun derivesSpeedAndHeadingWhenProviderGivesNeither() {
        val t = Track()
        assertNull(t.update(north(3), 77.594566, 1000L, null, null))
        val f = t.update(north(2), 77.594566, 2000L, null, null)!!
        assertEquals(15f, f.speedMps, 1f)
        assertEquals(0f, f.heading, 1f)     // due north
    }

    /** A 50 km jump in one second is a provider gap; it must not produce a speed at all. */
    @Test fun teleportIsIgnoredNotReported() {
        val t = Track()
        t.update(12.5, 77.7, 1000L, null, null)
        assertNull(t.update(12.97, 77.59, 2000L, null, null))
        // ...and the track resumes normally from the new position.
        val f = t.update(12.97 + 0.00013475, 77.59, 3000L, null, null)!!
        assertEquals(15f, f.speedMps, 1f)
    }

    @Test fun duplicateFixHoldsState() {
        val t = Track()
        t.update(north(3), 77.594566, 1000L, null, null)
        t.update(north(2), 77.594566, 2000L, null, null)
        assertNull(t.update(north(2), 77.594566, 3000L, null, null))
        assertNull(t.update(north(2), 77.594566, 5000L, null, null))
    }

    @Test fun sustainedStillnessReportsStopped() {
        val t = Track()
        t.update(north(3), 77.594566, 1000L, null, null)
        t.update(north(2), 77.594566, 2000L, null, null)
        assertNull(t.update(north(2), 77.594566, 4000L, null, null))
        val f = t.update(north(2), 77.594566, 9000L, null, null)!!
        assertEquals(0f, f.speedMps, 0.01f)
    }

    @Test fun providerValuesWin() {
        val t = Track()
        t.update(north(3), 77.594566, 1000L, null, null)
        val f = t.update(north(2), 77.594566, 2000L, 27.5f, 91f)!!
        assertEquals(27.5f, f.speedMps, 0.01f)
        assertEquals(91f, f.heading, 0.01f)
    }
}

class TrackSmoothingTest {

    private fun lat(step: Int) = 12.971599 - step * 0.00013475  // 15 m per step

    /** Alternating 15 m and 30 m steps must not swing the reported speed 2:1. */
    @Test fun jitteryIntervalsDoNotSwingSpeed() {
        val t = Track()
        var step = 20
        var time = 1000L
        var last = 0f
        var maxJump = 0f
        repeat(12) { i ->
            step -= if (i % 2 == 0) 1 else 2
            time += 1000L
            t.update(lat(step), 77.594566, time, null, null)?.let {
                if (last > 0f) maxJump = maxOf(maxJump, kotlin.math.abs(it.speedMps - last))
                last = it.speedMps
            }
        }
        assertTrue("speed jumped by $maxJump m/s between samples", maxJump < 8f)
    }
}
