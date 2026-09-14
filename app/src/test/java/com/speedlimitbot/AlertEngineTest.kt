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

class CameraDbTest {

    private val asset = sequenceOf(
        "# comment",
        "12.971599,77.594566,50,S",
        "12.980000,77.600000,0,T",
        "bad line",
        "13.000000,77.700000,80"        // no kind column: treated as a speed camera
    )

    @Test fun parsesKindsAndTolerantOfJunk() {
        val db = CameraDb.build(asset)
        assertEquals(3, db.size)
        val hit = db.nearestAhead(12.960000, 77.594566, 0f, 3000.0)!!
        assertEquals(50, hit.limitKmh)
        assertTrue(hit.speedCamera)
    }

    /** A download must be able to fill in a limit the bundled asset does not have. */
    @Test fun downloadFillsGapsWithoutDuplicating() {
        val downloaded = sequenceOf("12.980000,77.600000,60,S")
        val db = CameraDb.build(asset, downloaded)
        assertEquals(3, db.size)                       // same camera, not a second one
        val hit = db.nearestAhead(12.970000, 77.600000, 0f, 1500.0)!!
        assertEquals(60, hit.limitKmh)
        assertTrue(hit.speedCamera)                    // upgraded from traffic camera
    }

    @Test fun downloadAddsNewCameras() {
        val db = CameraDb.build(asset, sequenceOf("20.000000,75.000000,40,S"))
        assertEquals(4, db.size)
    }

    @Test fun trafficCameraIsFlagged() {
        val db = CameraDb.build(sequenceOf("12.980000,77.600000,0,T"))
        assertFalse(db.nearestAhead(12.970000, 77.600000, 0f, 2000.0)!!.speedCamera)
    }
}

class CameraDbKeyTest {

    /** Two cameras 900 km apart in eastern India must stay two cameras. */
    @Test fun distantCamerasInEasternIndiaDoNotCollide() {
        val db = CameraDb.build(
            sequenceOf(
                "26.140000,91.770000,50,S",   // Guwahati
                "22.570000,88.360000,50,S",   // Kolkata
                "13.080000,80.270000,50,S"    // Chennai
            )
        )
        assertEquals(3, db.size)
    }

    /** The same camera listed twice at metre precision is still one camera. */
    @Test fun sameCameraTwiceIsOne() {
        val db = CameraDb.build(sequenceOf("26.140000,91.770000,0,T", "26.140001,91.770000,50,S"))
        assertEquals(1, db.size)
    }
}

class LimitOverrideMergeTest {

    private val osm = sequenceOf(
        "13.198438,77.698776,0,T",     // camera OSM has no limit for
        "12.971599,77.594566,50,S"
    )

    /** What the driver picks must beat what the dataset says, and not add a camera. */
    @Test fun driverLimitWinsAndDoesNotDuplicate() {
        val user = sequenceOf("13.198438,77.698776,80,S")
        val db = CameraDb.build(osm, user)
        assertEquals(2, db.size)
        val hit = db.nearestAhead(13.188438, 77.698776, 0f, 2000.0)!!
        assertEquals(80, hit.limitKmh)
        assertTrue(hit.speedCamera)
    }

    /** A camera the driver added by hand is a camera OSM has never heard of. */
    @Test fun manuallyAddedCameraIsWarnedAbout() {
        val user = sequenceOf("28.443877,77.058127,80,S")
        val db = CameraDb.build(osm, user)
        assertEquals(3, db.size)
        val hit = db.nearestAhead(28.433877, 77.058127, 0f, 2000.0)!!
        assertEquals(80, hit.limitKmh)
        assertTrue(hit.speedCamera)
    }

    /** A later sync carrying no limit must not wipe the driver's answer. */
    @Test fun syncWithoutLimitDoesNotClobberDriverLimit() {
        val user = sequenceOf("13.198438,77.698776,80,S")
        val laterSync = sequenceOf("13.198438,77.698776,0,T")
        val db = CameraDb.build(osm, laterSync, user)
        assertEquals(80, db.nearestAhead(13.188438, 77.698776, 0f, 2000.0)!!.limitKmh)
    }
}

class TrackSpeedResponseTest {

    private fun lat(step: Int) = 12.971599 - step * 0.00013475   // 15 m per step

    /** A Doppler speed from the provider must be reported as-is, not lagged by a filter. */
    @Test fun providerSpeedIsNotSmoothed() {
        val t = Track()
        t.update(lat(6), 77.594566, 1000L, 5f, 0f)
        t.update(lat(5), 77.594566, 2000L, 8f, 0f)
        val f = t.update(lat(4), 77.594566, 3000L, 12f, 0f)!!
        assertEquals(12f, f.speedMps, 0.01f)
    }

    /** Accelerating 0 to 30 km/h must read 30, not something short of it. */
    @Test fun rampReachesItsTarget() {
        val t = Track()
        var time = 1000L
        var last = 0f
        listOf(2f, 4f, 6f, 8f, 8.33f, 8.33f).forEachIndexed { i, mps ->
            time += 1000L
            t.update(lat(6 - i), 77.594566, time, mps, 0f)?.let { last = it.speedMps }
        }
        assertEquals(30f, last * 3.6f, 0.5f)
    }
}
