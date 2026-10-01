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
}
