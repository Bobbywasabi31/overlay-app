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
            assertEquals(g.targetX, swipe.endX, 0.01f)
            assertEquals(g.targetY, swipe.endY, 0.01f)
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
}
