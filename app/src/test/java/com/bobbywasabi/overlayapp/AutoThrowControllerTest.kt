package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test

class AutoThrowControllerTest {
    private val controller = AutoThrowController()
    private val ring = ScreenAnalyzer.Ring(0.5f, 0.45f, 0.04f, 0.9f)

    private fun ready(start: Long): Long {
        for (i in 0..5) assertFalse(controller.consider(ring, start + i * 67))
        val time = start + 402
        assertTrue(controller.consider(ring, time))
        return time
    }
    private fun disappear(start: Long) {
        for (i in 0..6) assertFalse(controller.consider(null, start + i * 200))
    }
    @Test fun requiresExplicitArmingAndStableFreshFrames() {
        assertFalse(controller.consider(ring, 1000))
        controller.arm()
        ready(1000)
    }
    @Test fun persistentRingNeverCausesRepeatedThrows() {
        controller.arm()
        controller.beginThrow(ready(1000))
        assertFalse(controller.consider(ring, 1500))
        controller.finishThrow(true)
        for (i in 0..60) assertFalse(controller.consider(ring, 1800 + i * 100L))
        assertEquals(1, controller.throws)
    }
    @Test fun repeatNeedsObservedDisappearanceAndCooldown() {
        controller.arm()
        controller.beginThrow(ready(1000))
        controller.finishThrow(true)
        disappear(1800)
        for (i in 0..13) assertFalse(controller.consider(ring, 3050 + i * 100L))
        assertTrue(controller.consider(ring, 4450))
    }
    @Test fun captureStallDoesNotCountAsRingDisappearance() {
        controller.arm()
        controller.beginThrow(ready(1000))
        controller.finishThrow(true)
        assertFalse(controller.consider(null, 1800))
        assertFalse(controller.consider(null, 5000))
        for (i in 0..10) assertFalse(controller.consider(ring, 5100 + i * 100L))
    }
    @Test fun targetJumpAndAccumulatingDriftRestartStability() {
        controller.arm()
        for (i in 0..10) assertFalse(controller.consider(ring.copy(x = 0.4f + i * 0.01f), 1000 + i * 67L))
        assertFalse(controller.consider(ring.copy(x = 0.7f), 1750))
    }
    @Test fun mediumConfidenceTracksButCannotThrow() {
        controller.arm()
        for (i in 0..7) assertFalse(controller.consider(ring.copy(confidence = 0.65f), 1000 + i * 67L))
        // Confidence recovers on the same stable ring: no re-anchor needed to throw.
        assertTrue(controller.consider(ring.copy(confidence = 0.8f), 1600))
    }
    @Test fun borderlineConfidenceHoldsTheStabilityTimer() {
        controller.arm()
        assertFalse(controller.consider(ring, 1000))
        assertFalse(controller.consider(ring, 1100))
        assertFalse(controller.consider(ring.copy(confidence = 0.5f), 1200))
        assertTrue(controller.consider(ring, 1400))
    }
    @Test fun weakFrameDropsTheTrack() {
        controller.arm()
        assertFalse(controller.consider(ring, 1000))
        assertFalse(controller.consider(ring, 1100))
        assertFalse(controller.consider(ring.copy(confidence = 0.4f), 1200))
        assertFalse(controller.consider(ring, 1300))
        assertFalse(controller.consider(ring, 1400))
        assertTrue(controller.consider(ring, 1700))
    }
    @Test fun lowConfidenceCannotCountTowardConfirmation() {
        controller.arm()
        assertFalse(controller.consider(ring.copy(confidence = 0.4f), 1000))
        assertFalse(controller.consider(ring, 1500))
        assertTrue(controller.consider(ring, 1900))
    }
    @Test fun failedDispatchDisarmsAndRearmRequiresFreshConfirmation() {
        controller.arm()
        controller.beginThrow(ready(1000))
        controller.finishThrow(false)
        assertFalse(controller.armed)
        assertFalse(controller.busy)
        assertFalse(controller.consider(ring, 5000))
        controller.arm()
        assertEquals(0, controller.throws)
        ready(5100)
    }
    @Test fun fiveThrowsDisarmAndSessionStopDoesNotScheduleMore() {
        controller.arm()
        for (attempt in 0..4) {
            val start = 1000 + attempt * 5000L
            controller.beginThrow(ready(start))
            controller.finishThrow(true)
            if (attempt < 4) disappear(start + 1500)
        }
        assertEquals(5, controller.throws)
        assertFalse(controller.armed)
        assertFalse(controller.consider(ring, 40_000))
        controller.arm()
        controller.disarm()
        assertFalse(controller.consider(ring, 50_000))
    }
    @Test fun hiddenRingCanBeRevealedOnlyAfterExplicitArming() {
        assertFalse(controller.canBeginHold(1000))
        controller.arm()
        assertTrue(controller.canBeginHold(1000))
        controller.disarm()
        assertFalse(controller.canBeginHold(1000))
    }
    @Test fun holdingCannotBypassObservedAbsenceOrCooldown() {
        controller.arm()
        controller.beginThrow(ready(1000))
        assertFalse(controller.canBeginHold(8000))
        controller.finishThrow(true)
        assertFalse(controller.canBeginHold(8000))
        disappear(1800)
        assertFalse(controller.canBeginHold(3050))
        assertTrue(controller.canBeginHold(4450))
    }
}
