package com.speedlimitbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    @Test fun higherPatchIsNewer() {
        assertTrue(UpdateCheck.newer("v1.0.2", "1.0.1"))
        assertFalse(UpdateCheck.newer("v1.0.1", "1.0.1"))
        assertFalse(UpdateCheck.newer("v1.0.0", "1.0.1"))
    }

    /** A string compare would call 1.0.10 older than 1.0.9 and strand everyone on 1.0.9. */
    @Test fun comparesSegmentsAsNumbers() {
        assertTrue(UpdateCheck.newer("v1.0.10", "1.0.9"))
        assertFalse(UpdateCheck.newer("v1.0.9", "1.0.10"))
        assertTrue(UpdateCheck.newer("v1.10.0", "1.9.9"))
    }

    @Test fun shorterVersionsPadWithZero() {
        assertTrue(UpdateCheck.newer("v1.1", "1.0.9"))
        assertFalse(UpdateCheck.newer("v1.0", "1.0.0"))
        assertTrue(UpdateCheck.newer("v2", "1.9.9"))
    }

    /** Junk must never offer an update that installs the same build again. */
    @Test fun unparseableTagsCountAsOlder() {
        assertFalse(UpdateCheck.newer("", "1.0.1"))
        assertFalse(UpdateCheck.newer("nightly", "1.0.1"))
        assertFalse(UpdateCheck.newer("v1.0.1-rc1", "1.0.1"))
    }
}
