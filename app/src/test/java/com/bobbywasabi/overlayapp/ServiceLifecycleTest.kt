package com.bobbywasabi.overlayapp

import android.content.Intent
import android.os.HandlerThread
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Item 41: service-lifecycle unit tests. Start/stop/destroy paths must remove
 * overlays, clear the static service reference, drop LiveData observers, and
 * stop the worker thread — none of which may crash when called repeatedly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ServiceLifecycleTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun overlayDestroyClearsViewsObserversAndWorker() {
        ThrowState.resetSession()
        ThrowState.calibrate(ThrowPlanner.Ball(0.5f, 0.85f), ThrowTarget.GAME)
        ThrowState.controller.arm()
        val lifecycle = Robolectric.buildService(OverlayService::class.java).create()
        val service = lifecycle.get()
        // Pretend a capture session attached its overlays.
        ReflectionHelpers.setField(service, "guidance", GuidanceView(context, 1080, 2400))
        ReflectionHelpers.setField(service, "controls", LinearLayout(context))
        ReflectionHelpers.setField(service, "debugHud", TextView(context))
        assertTrue(ThrowState.changes.hasObservers())
        lifecycle.destroy()
        assertNull(ReflectionHelpers.getField<Any>(service, "guidance"))
        assertNull(ReflectionHelpers.getField<Any>(service, "controls"))
        assertNull(ReflectionHelpers.getField<Any>(service, "debugHud"))
        assertFalse(ThrowState.changes.hasObservers())
        val thread = ReflectionHelpers.getField<HandlerThread>(service, "workerThread")
        thread.join(2000)
        assertFalse(thread.isAlive)
        // The session guarantee: nothing stays armed or calibrated.
        assertNull(ThrowState.ball)
        assertFalse(ThrowState.controller.armed)
    }

    @Test fun overlayRepeatedDestroyIsSafe() {
        val service = Robolectric.buildService(OverlayService::class.java).create().get()
        repeat(3) { ReflectionHelpers.callInstanceMethod<Any>(service, "onDestroy") }
    }

    @Test fun overlayStopWithoutStartCleansUp() {
        ThrowState.resetSession()
        val lifecycle = Robolectric.buildService(OverlayService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(Intent().setAction(OverlayService.ACTION_STOP), 0, 1)
        lifecycle.destroy()
        assertEquals(SessionState.Phase.IDLE, SessionState.current.phase)
        assertFalse(ThrowState.changes.hasObservers())
    }

    @Test fun gestureDestroyRemovesObserverAndClearsCurrent() {
        ThrowState.resetSession()
        val lifecycle = Robolectric.buildService(GestureThrowService::class.java).create()
        val service = lifecycle.get()
        ReflectionHelpers.callInstanceMethod<Any>(service, "onServiceConnected")
        assertNotNull(GestureThrowService.current)
        assertTrue(ThrowState.changes.hasObservers())
        lifecycle.destroy()
        assertNull(GestureThrowService.current)
        assertFalse(ThrowState.changes.hasObservers())
        assertFalse(ThrowState.controller.armed)
    }

    @Test fun gestureUnbindDisconnectsWithoutDestroy() {
        ThrowState.resetSession()
        val service = Robolectric.buildService(GestureThrowService::class.java).create().get()
        ReflectionHelpers.callInstanceMethod<Any>(service, "onServiceConnected")
        assertNotNull(GestureThrowService.current)
        service.onUnbind(null)
        assertNull(GestureThrowService.current)
        assertFalse(ThrowState.controller.armed)
    }

    @Test fun gestureInterruptDisarmsController() {
        ThrowState.resetSession()
        val service = Robolectric.buildService(GestureThrowService::class.java).create().get()
        ThrowState.controller.arm()
        service.onInterrupt()
        assertFalse(ThrowState.controller.armed)
    }
}
