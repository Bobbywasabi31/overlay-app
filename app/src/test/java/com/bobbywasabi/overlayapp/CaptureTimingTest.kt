package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test

class CaptureTimingTest {
    @Test fun freshBoundaryAndExpiredDelivery() {
        assertTrue(CaptureTiming.isFresh(1000, 1900))
        assertFalse(CaptureTiming.isFresh(1000, 1901))
        assertFalse(CaptureTiming.isFresh(1000, 999))
    }
    @Test fun holdAndThrowDecisionsShareTheStaleBound() {
        // A frame older than the removed 200 ms throw budget is still usable
        // for hold/throw decisions; only a genuinely stale frame is rejected.
        assertTrue(CaptureTiming.isFresh(1000, 1201))
        assertTrue(CaptureTiming.isFresh(1000, 1500))
        assertFalse(CaptureTiming.isFresh(1000, 1901))
    }
}
