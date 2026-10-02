package com.bobbywasabi.overlayapp

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OverlayServiceTest {
    @Test fun stopKeepsStartDisabledUntilServiceTeardown() {
        SessionState.update(SessionState.Phase.RUNNING, R.string.message_running)
        val lifecycle = Robolectric.buildService(OverlayService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(Intent().setAction(OverlayService.ACTION_STOP), 0, 1)
        assertEquals(SessionState.Phase.STOPPING, SessionState.current.phase)
        assertTrue(SessionState.current.isActive)
        // A second Start cannot overwrite the terminating session or reuse consent.
        service.onStartCommand(Intent().setAction(OverlayService.ACTION_START), 0, 2)
        assertEquals(SessionState.Phase.STOPPING, SessionState.current.phase)
        lifecycle.destroy()
        assertEquals(SessionState.Phase.IDLE, SessionState.current.phase)
        assertFalse(SessionState.current.isActive)
    }
    @Test fun partialStartFailureKeepsFirstReasonAndClearsThrowState() {
        ThrowState.calibrate(ThrowPlanner.Ball(0.5f, 0.85f))
        ThrowState.controller.arm()
        SessionState.update(SessionState.Phase.STARTING, R.string.status_starting)
        val lifecycle = Robolectric.buildService(OverlayService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(Intent().setAction(OverlayService.ACTION_START), 0, 1)
        service.onStartCommand(Intent().setAction(OverlayService.ACTION_STOP), 0, 2)
        assertFalse(ThrowState.controller.armed)
        assertNull(ThrowState.ball)
        lifecycle.destroy()
        assertEquals(SessionState.Phase.ERROR, SessionState.current.phase)
        assertEquals(R.string.message_capture_error, SessionState.current.message)
    }
}
