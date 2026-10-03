package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Item 2: configuration-change recreation must not crash and must preserve
 * what ThrowState actually guarantees. ThrowState is a process-scoped
 * singleton: Activity recreation never clears it, while a service teardown
 * (the documented "capture restart") resets the session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RotationRecreationTest {
    private val ball = ThrowPlanner.Ball(0.5f, 0.85f)

    @Test fun activityRecreationPreservesCalibrationAndArming() {
        TosAck.setAcknowledged(RuntimeEnvironment.getApplication())
        ThrowState.resetSession()
        ThrowState.calibrate(ball, ThrowTarget.GAME)
        ThrowState.controller.arm()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.recreate()
        assertEquals(ball, ThrowState.ball)
        assertEquals(ThrowTarget.GAME, ThrowState.target)
        assertTrue(ThrowState.controller.armed)
        controller.destroy()
    }

    @Test fun activityRecreationWithoutCalibrationDoesNotCrash() {
        TosAck.setAcknowledged(RuntimeEnvironment.getApplication())
        ThrowState.resetSession()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.recreate()
        assertNull(ThrowState.ball)
        assertNull(ThrowState.target)
        controller.destroy()
    }

    @Test fun overlayServiceRebuildResetsSessionState() {
        ThrowState.resetSession()
        ThrowState.calibrate(ball, ThrowTarget.GAME)
        ThrowState.controller.arm()
        val first = Robolectric.buildService(OverlayService::class.java).create()
        first.destroy()
        // onDestroy documents the session guarantee: calibration and arming
        // never survive a capture restart.
        assertNull(ThrowState.ball)
        assertNull(ThrowState.target)
        assertFalse(ThrowState.controller.armed)
        val second = Robolectric.buildService(OverlayService::class.java).create()
        second.destroy()
    }

    @Test fun gestureServiceRebuildClearsStaticCurrent() {
        ThrowState.resetSession()
        val first = Robolectric.buildService(GestureThrowService::class.java).create()
        ReflectionHelpers.callInstanceMethod<Any>(first.get(), "onServiceConnected")
        assertNotNull(GestureThrowService.current)
        first.destroy()
        assertNull(GestureThrowService.current)
        val second = Robolectric.buildService(GestureThrowService::class.java).create()
        ReflectionHelpers.callInstanceMethod<Any>(second.get(), "onServiceConnected")
        assertSame(second.get(), GestureThrowService.current)
        second.destroy()
        assertNull(GestureThrowService.current)
    }
}
