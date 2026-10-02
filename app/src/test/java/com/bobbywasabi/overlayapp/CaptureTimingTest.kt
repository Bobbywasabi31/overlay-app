package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test

class CaptureTimingTest {
    @Test fun freshBoundaryAndExpiredDelivery() {
        assertTrue(CaptureTiming.isFresh(1000, 1900))
        assertFalse(CaptureTiming.isFresh(1000, 1901))
        assertFalse(CaptureTiming.isFresh(1000, 999))
    }
    @Test fun throwRequiresMoreRecentFrameThanVisualGuidance() {
        assertTrue(CaptureTiming.isFreshForThrow(1000, 1200))
        assertFalse(CaptureTiming.isFreshForThrow(1000, 1201))
        assertTrue(CaptureTiming.isFresh(1000, 1201))
        assertFalse(CaptureTiming.isFreshForThrow(1000, 999))
    }
}
