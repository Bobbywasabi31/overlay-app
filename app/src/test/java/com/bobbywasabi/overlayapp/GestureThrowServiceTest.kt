package com.bobbywasabi.overlayapp

import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class GestureThrowServiceTest {
    private lateinit var lifecycle: ServiceController<GestureThrowService>
    private lateinit var service: GestureThrowService
    private val ball = ThrowPlanner.Ball(0.5f, 0.85f)

    @Before fun startSession() {
        ThrowState.resetSession()
        lifecycle = Robolectric.buildService(GestureThrowService::class.java).create()
        service = lifecycle.get()
        ReflectionHelpers.callInstanceMethod<Any>(service, "onServiceConnected")
        PracticeSession.resume()
        focusGestureWindow(service, service.packageName)
        ThrowState.calibrate(ball, ThrowTarget.PRACTICE)
        SessionState.update(SessionState.Phase.RUNNING, R.string.message_running)
        ThrowState.controller.arm()
    }
    @After fun stopSession() {
        ThrowState.disarm()
        lifecycle.destroy()
        PracticeSession.pause()
        SessionState.update(SessionState.Phase.IDLE, R.string.message_idle)
    }
    private fun hold() {
        assertTrue(service.holdBall(ball, 1080, 2400, SystemClock.elapsedRealtime()))
        assertFalse(service.canContinueHold)
    }
    private fun completePress() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GestureThrowService.HOLD_MS))
        val press = shadowOf(service).gesturesDispatched.first()
        press.callback().onCompleted(press.description())
    }
    private fun stroke(index: Int) = shadowOf(service).gesturesDispatched[index].description().getStroke(0)
    private fun assertContinuation(index: Int) {
        val original: Int = ReflectionHelpers.callInstanceMethod(stroke(0), "getId")
        val continued: Int = ReflectionHelpers.callInstanceMethod(stroke(index), "getContinuedStrokeId")
        assertEquals(original, continued)
        assertFalse(stroke(index).willContinue())
    }
    private fun swipe() = ThrowPlanner.Swipe(540f, 2040f, 540f, 1056f, 350)

    @Test fun holdRevealsRingThenThrowContinuesTheSamePointer() {
        hold()
        assertTrue(stroke(0).willContinue())
        assertEquals(GestureThrowService.HOLD_MS, stroke(0).duration)
        completePress()
        assertTrue(service.canContinueHold)
        val controller = ThrowState.controller
        controller.beginThrow(SystemClock.elapsedRealtime())
        assertTrue(service.throwBall(swipe(), SystemClock.elapsedRealtime()) { controller.finishThrow(it) })
        assertContinuation(1)
        assertEquals(350L, stroke(1).duration)
        assertFalse(service.holding)
        val move = shadowOf(service).gesturesDispatched[1]
        move.callback().onCompleted(move.description())
        assertFalse(service.busy)
        assertFalse(controller.busy)
        assertEquals(1, controller.throws)
    }
    @Test fun stopDuringPressReleasesWithoutAThrowAfterPressCompletes() {
        hold()
        ThrowState.disarm()
        assertEquals(1, shadowOf(service).gesturesDispatched.size)
        completePress()
        assertContinuation(1)
        assertEquals(1L, stroke(1).duration)
        assertEquals(0, ThrowState.controller.throws)
        assertFalse(ThrowState.controller.armed)
    }
    @Suppress("DEPRECATION")
    @Test fun foregroundLossOnlyLiftsTheExistingPointer() {
        hold()
        focusGestureWindow(service, "com.example.unrelated")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        try { service.onAccessibilityEvent(event) } finally { event.recycle() }
        completePress()
        assertContinuation(1)
        assertEquals(1L, stroke(1).duration)
        assertFalse(ThrowState.controller.armed)
    }
    @Test fun timeoutReleasesEvenIfPressCallbackWasLost() {
        hold()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GestureThrowService.HOLD_TIMEOUT_MS))
        assertContinuation(1)
        assertEquals(1L, stroke(1).duration)
        assertFalse(ThrowState.controller.armed)
        assertEquals(0, ThrowState.controller.throws)
        val press = shadowOf(service).gesturesDispatched[0]
        press.callback().onCompleted(press.description())
        assertFalse(service.canContinueHold)
        val release = shadowOf(service).gesturesDispatched[1]
        release.callback().onCompleted(release.description())
        assertFalse(service.busy)
    }
    @Test fun staleDecisionOrChangedCalibrationCannotContinueIntoAThrow() {
        hold()
        completePress()
        val controller = ThrowState.controller
        controller.beginThrow(SystemClock.elapsedRealtime())
        assertFalse(service.throwBall(swipe(), SystemClock.elapsedRealtime() - 201) {})
        ThrowState.calibrate(ball, ThrowTarget.GAME)
        assertFalse(service.throwBall(swipe(), SystemClock.elapsedRealtime()) {})
        assertContinuation(1)
        assertEquals(1L, stroke(1).duration)
    }
    @Test fun dispatchRejectionDoesNotLeaveTheServiceBusy() {
        shadowOf(service).setCanDispatchGestures(false)
        assertFalse(service.holdBall(ball, 1080, 2400, SystemClock.elapsedRealtime()))
        assertFalse(service.busy)
        assertFalse(service.holding)
    }
    @Test fun latePressCancellationCannotDisarmANewCaptureSession() {
        hold()
        val press = shadowOf(service).gesturesDispatched[0]
        ThrowState.resetSession()
        ThrowState.calibrate(ball, ThrowTarget.PRACTICE)
        ThrowState.controller.arm()
        press.callback().onCancelled(press.description())
        assertTrue(ThrowState.controller.armed)
        assertFalse(service.busy)
    }
    @Test fun rejectedContinuationReleasesStateAndDisarmsTheAttempt() {
        hold()
        completePress()
        shadowOf(service).setCanDispatchGestures(false)
        val controller = ThrowState.controller
        controller.beginThrow(SystemClock.elapsedRealtime())
        assertFalse(service.throwBall(swipe(), SystemClock.elapsedRealtime()) {})
        controller.finishThrow(false)
        ThrowState.changed()
        assertFalse(controller.armed)
        assertFalse(service.busy)
        assertFalse(service.holding)
    }
    @Test fun fifthThrowStillFinishesItsHeldPointerAfterBatchDisarms() {
        val controller = ThrowState.controller
        repeat(4) {
            val start = SystemClock.elapsedRealtime()
            controller.beginThrow(start)
            controller.finishThrow(true)
            for (i in 0..6) controller.consider(null, start + 500 + i * 200L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000))
        }
        hold()
        completePress()
        controller.beginThrow(SystemClock.elapsedRealtime())
        assertFalse(controller.armed)
        assertTrue(service.throwBall(swipe(), SystemClock.elapsedRealtime()) { controller.finishThrow(it) })
        ThrowState.changed()
        assertEquals(2, shadowOf(service).gesturesDispatched.size)
        assertContinuation(1)
        assertEquals(5, controller.throws)
    }
}
