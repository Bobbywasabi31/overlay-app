package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PracticeSessionTest {
    @Test fun leavingPracticeImmediatelyDisarmsAndRemovesItsAllowlistEntry() {
        val activity = Robolectric.buildActivity(DemoActivity::class.java).setup()
        assertTrue(PracticeSession.visible)
        ThrowState.calibrate(ThrowPlanner.Ball(0.5f, 0.85f), ThrowTarget.PRACTICE)
        ThrowState.controller.arm()
        activity.pause()
        assertFalse(PracticeSession.visible)
        assertFalse(ThrowState.controller.armed)
        activity.stop().destroy()
    }
    @Test fun practiceCalibrationCannotBeUsedAsAGameCalibration() {
        ThrowState.calibrate(ThrowPlanner.Ball(0.5f, 0.85f), ThrowTarget.PRACTICE)
        assertEquals(ThrowTarget.PRACTICE, ThrowState.target)
        assertNotEquals(ThrowTarget.GAME, ThrowState.target)
        ThrowState.resetSession()
        assertNull(ThrowState.ball)
        assertNull(ThrowState.target)
    }
}
