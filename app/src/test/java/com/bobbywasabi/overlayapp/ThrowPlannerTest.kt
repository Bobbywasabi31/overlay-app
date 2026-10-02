package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test

class ThrowPlannerTest {
    private val ring = ScreenAnalyzer.Ring(0.6f, 0.45f, 0.04f, 0.9f)
    private val ball = ThrowPlanner.Ball(0.5f, 0.85f)

    @Test fun mapsCalibratedOriginAndTargetToDisplayPixels() {
        val swipe = requireNotNull(ThrowPlanner.plan(ring, ball, 1080, 2400, 350))
        assertEquals(540f, swipe.startX, 0.01f)
        assertEquals(2040f, swipe.startY, 0.01f)
        assertEquals(648f, swipe.endX, 0.01f)
        assertEquals(1080f, swipe.endY, 0.01f)
        assertEquals(350L, swipe.durationMs)
    }
    @Test fun rejectsLandscapeAndInvalidCalibration() {
        assertNull(ThrowPlanner.plan(ring, ball, 2400, 1080, 350))
        assertNull(ThrowPlanner.plan(ring, ball.copy(y = 0.4f), 1080, 2400, 350))
        assertNull(ThrowPlanner.plan(ring, ball.copy(x = Float.NaN), 1080, 2400, 350))
    }
    @Test fun rejectsUnsafeOrUncertainTargets() {
        for (bad in listOf(ring.copy(y = 0.8f), ring.copy(x = Float.NaN),
            ring.copy(confidence = 0.5f), ring.copy(radius = Float.POSITIVE_INFINITY))) {
            assertNull(ThrowPlanner.plan(bad, ball, 1080, 2400, 350))
        }
    }
    @Test fun requiresSupportedSwipeDuration() {
        assertNull(ThrowPlanner.plan(ring, ball, 1080, 2400, 149))
        assertNull(ThrowPlanner.plan(ring, ball, 1080, 2400, 601))
        assertNotNull(ThrowPlanner.plan(ring, ball, 1080, 2400, 150))
        assertNotNull(ThrowPlanner.plan(ring, ball, 1080, 2400, 600))
    }
}
