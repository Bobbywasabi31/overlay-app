package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test

class RingTrackerTest {
    private val tracker = RingTracker()
    private val ring = ScreenAnalyzer.Ring(0.5f, 0.55f, 0.15f, 0.9f)
    @Test fun waitsForTwoConsistentFrames() {
        assertNull(tracker.update(ring))
        assertNotNull(tracker.update(ring))
    }
    @Test fun missingFrameClearsAndRequiresReconfirmation() {
        tracker.update(ring)
        tracker.update(ring)
        assertNull(tracker.update(null))
        assertNull(tracker.update(ring))
        assertNotNull(tracker.update(ring))
    }
    @Test fun abruptTargetJumpRequiresReconfirmation() {
        tracker.update(ring)
        val moved = ring.copy(x = 0.75f)
        assertNull(tracker.update(moved))
        assertNotNull(tracker.update(moved))
    }
    @Test fun smoothsCenterWithoutDelayingShrinkingRadius() {
        tracker.update(ring)
        val next = ring.copy(x = 0.52f, radius = 0.12f)
        val tracked = requireNotNull(tracker.update(next))
        assertTrue(tracked.x > ring.x && tracked.x < next.x)
        assertEquals(next.radius, tracked.radius, 0f)
    }
    @Test fun longGapRequiresTwoFreshFrames() {
        assertNull(tracker.update(ring, 1000))
        assertNotNull(tracker.update(ring, 1067))
        assertNull(tracker.update(ring, 1968))
        assertNotNull(tracker.update(ring, 2035))
    }
    @Test fun displayTargetHoldsLastConfirmedRingBriefly() {
        assertNull(tracker.displayTarget(1000))
        tracker.update(ring, 1000)
        tracker.update(ring, 1067)
        assertNotNull(tracker.displayTarget(1100))
        assertNotNull(tracker.displayTarget(1067 + RingTracker.DISPLAY_HOLD_MS))
        assertNull(tracker.displayTarget(1067 + RingTracker.DISPLAY_HOLD_MS + 1))
    }
    @Test fun displayTargetSurvivesSingleMissedFrame() {
        tracker.update(ring, 1000)
        tracker.update(ring, 1067)
        assertNull(tracker.update(null, 1134))
        assertNotNull(tracker.displayTarget(1200))
        assertNull(tracker.displayTarget(1300))
    }
    @Test fun expiryBoundaryPreservesNormalTracking() {
        tracker.update(ring, 1000)
        assertNotNull(tracker.update(ring, 1900))
        assertNull(tracker.update(ring, 1899))
        assertNotNull(tracker.update(ring, 1966))
    }
}
