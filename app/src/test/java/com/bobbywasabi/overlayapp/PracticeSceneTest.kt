package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test

class PracticeSceneTest {
    @Test fun displayGeometryMatchesCalibratedThrowPlanner() {
        val scene = PracticeScene()
        for ((w, h) in listOf(1080 to 2400, 720 to 1600, 432 to 960)) {
            val g = scene.geometry(w, h, 1000)
            val ring = ScreenAnalyzer.Ring(g.targetX / w, g.targetY / h, g.ringRadius / w, 0.9f)
            val ball = ThrowPlanner.Ball(g.ballX / w, g.ballY / h)
            val swipe = requireNotNull(ThrowPlanner.plan(ring, ball, w, h, 350))
            assertEquals(g.ballX, swipe.startX, 0.01f)
            assertEquals(g.ballY, swipe.startY, 0.01f)
            // 1.3x overshoot past the target, clamped to the screen.
            val bx = g.ballX / w; val by = g.ballY / h
            val rx = g.targetX / w; val ry = g.targetY / h
            assertEquals((bx + (rx - bx) * 1.3f).coerceIn(0f, 1f) * w, swipe.endX, 0.01f)
            assertEquals((by + (ry - by) * 1.3f).coerceIn(0f, 1f) * h, swipe.endY, 0.01f)
        }
    }
    @Test fun scoresRingSizeAtReleaseTime() {
        val early = PracticeScene()
        val late = PracticeScene()
        assertEquals(PracticeScene.Result.INNER_RING, early.submit(1000, 2000, 500f, 1700f, 570f, 880f, 0, 350))
        assertEquals(PracticeScene.Result.OUTER_TARGET, late.submit(1000, 2000, 500f, 1700f, 570f, 880f, 5500, 5850))
    }
    @Test fun missesAreCountedWithoutInventingGameThrowGrades() {
        val scene = PracticeScene()
        assertEquals(PracticeScene.Result.MISS, scene.submit(1000, 2000, 500f, 1700f, 900f, 880f, 0, 350))
        assertEquals(1, scene.attempts)
        assertEquals(0, scene.innerHits)
    }
    @Test fun tapsWrongOriginsAndInvalidTimesAreNotThrows() {
        val scene = PracticeScene()
        assertNull(scene.submit(1000, 2000, 500f, 1700f, 500f, 1700f, 0, 350))
        assertNull(scene.submit(1000, 2000, 20f, 20f, 500f, 880f, 0, 350))
        assertNull(scene.submit(1000, 2000, 500f, 1700f, 500f, 880f, 0, 149))
        assertNull(scene.submit(1000, 2000, 500f, 1700f, 500f, 880f, 0, 601))
        assertNull(scene.submit(1000, 2000, 500f, 1700f, Float.NaN, 880f, 0, 350))
        assertEquals(0, scene.attempts)
    }
    @Test fun recoveryProvidesObservedAbsenceBeforeAnotherThrow() {
        val scene = PracticeScene()
        assertEquals(PracticeScene.Result.INNER_RING, scene.submit(1000, 2000, 500f, 1700f, 500f, 880f, 0, 350))
        assertFalse(scene.ready(350 + PracticeScene.RECOVERY_MS - 1))
        assertTrue(scene.ready(350 + PracticeScene.RECOVERY_MS))
        assertNull(scene.submit(1000, 2000, 500f, 1700f, 500f, 880f, 1000, 1350))
        assertTrue(PracticeScene.RECOVERY_MS > AutoThrowController.CLEAR_MS)
    }
    @Test fun ringShrinksThenResetsAndPaleModeStaysDetectable() {
        val scene = PracticeScene()
        val large = scene.geometry(1080, 2400, 0)
        val small = scene.geometry(1080, 2400, 5999)
        assertTrue(small.ringRadius < large.ringRadius / 4)
        assertEquals(large.ringRadius, scene.geometry(1080, 2400, 6000).ringRadius, 0.01f)
        scene.smallPale = true
        assertTrue(scene.geometry(432, 960, 5999).ringRadius > 4f)
        scene.moving = true
        assertNotEquals(scene.geometry(1080, 2400, 0).targetX, scene.geometry(1080, 2400, 1000).targetX)
    }
    @Test fun ringAppearsWhileHoldingAndStaysHiddenDuringRecovery() {
        val scene = PracticeScene()
        assertFalse(scene.ringVisible(0, false))
        assertTrue(scene.ringVisible(0, true))
        scene.ringOnHold = false
        assertTrue(scene.ringVisible(0, false))
        scene.submit(1000, 2000, 500f, 1700f, 500f, 880f, 0, 350)
        assertFalse(scene.ringVisible(400, true))
    }
    @Test fun movementTimingDoesNotCountTheStationaryHold() {
        val scene = PracticeScene()
        assertEquals(PracticeScene.Result.INNER_RING,
            scene.submit(1000, 2000, 500f, 1700f, 500f, 880f, 0, 1100, 750))
        scene.restart(1200)
        assertNull(scene.submit(1000, 2000, 500f, 1700f, 500f, 880f, 1200, 2500, 1850))
    }
    @Test fun drillOffLeavesNoTimingVerdict() {
        val scene = PracticeScene()
        assertEquals(PracticeScene.Result.INNER_RING,
            scene.submit(1000, 2000, 500f, 1700f, 500f, 880f, 3850, 4200))
        assertNull(scene.lastTiming)
        assertEquals(0, scene.drillAttempts)
    }
    /** Landing-time radius = radius at upMs + FLIGHT_MS, scored against [0.03, 0.07]. */
    private fun drillThrow(scene: PracticeScene, upMs: Long): PracticeScene.Timing? {
        scene.timingDrill = true
        scene.submit(1000, 2000, 500f, 1700f, 500f, 880f, upMs - 350, upMs)
        return scene.lastTiming
    }
    @Test fun drillEarlyWhenRingStillWideAtLanding() {
        val scene = PracticeScene()
        assertEquals(PracticeScene.Timing.EARLY, drillThrow(scene, 2000))
        assertEquals(1, scene.drillAttempts)
        assertEquals(0, scene.drillExcellents)
    }
    @Test fun drillExcellentInsideWindowIncludingEdge() {
        val scene = PracticeScene()
        assertEquals(PracticeScene.Timing.EXCELLENT, drillThrow(scene, 4200))
        // landing at exactly phase 2/3: radius 0.07, the wide edge of the window.
        val edge = PracticeScene()
        assertEquals(PracticeScene.Timing.EXCELLENT, drillThrow(edge, 3500))
    }
    @Test fun drillLateWhenWindowClosedOrRingReset() {
        val shrunk = PracticeScene()
        assertEquals(PracticeScene.Timing.LATE, drillThrow(shrunk, 5200))
        val reset = PracticeScene()
        assertEquals(PracticeScene.Timing.LATE, drillThrow(reset, 5700))
    }
    @Test fun drillCountersAccumulateAndReset() {
        val scene = PracticeScene()
        scene.timingDrill = true
        scene.submit(1000, 2000, 500f, 1700f, 500f, 880f, 3850, 4200)
        scene.submit(1000, 2000, 500f, 1700f, 500f, 880f, 6650, 7000)
        assertEquals(2, scene.drillAttempts)
        assertEquals(1, scene.drillExcellents)
        scene.resetDrill()
        assertEquals(0, scene.drillAttempts)
        assertEquals(0, scene.drillExcellents)
        assertNull(scene.lastTiming)
    }
}
