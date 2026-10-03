package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThrowSetupTest {
    private fun display(service: OverlayService) {
        val type = Class.forName("com.bobbywasabi.overlayapp.OverlayService\$DisplaySize")
        val constructor = type.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        ReflectionHelpers.setField(service, "initialDisplay", constructor.newInstance(1080, 2400, 0))
        ReflectionHelpers.setField(service, "active", true)
    }
    @Test fun missingBallCalibrationDoesNotReportMissingGesturePermission() {
        ThrowState.resetSession()
        val gesture = Robolectric.buildService(GestureThrowService::class.java).create()
        val capture = Robolectric.buildService(OverlayService::class.java).create()
        try {
            ReflectionHelpers.callInstanceMethod<Any>(gesture.get(), "onServiceConnected")
            PracticeSession.resume()
            focusGestureWindow(gesture.get(), gesture.get().packageName)
            display(capture.get())
            ReflectionHelpers.callInstanceMethod<Any>(capture.get(), "toggleAuto")
            assertEquals(capture.get().getString(R.string.auto_set_ball), ShadowToast.getTextOfLatestToast())
            assertFalse(ThrowState.controller.armed)
        } finally { capture.destroy(); gesture.destroy(); PracticeSession.pause() }
    }
    @Test fun controlScreenRejectionExplainsTheRequiredEncounter() {
        ThrowState.resetSession()
        val gesture = Robolectric.buildService(GestureThrowService::class.java).create()
        val capture = Robolectric.buildService(OverlayService::class.java).create()
        try {
            ReflectionHelpers.callInstanceMethod<Any>(gesture.get(), "onServiceConnected")
            PracticeSession.pause()
            focusGestureWindow(gesture.get(), gesture.get().packageName)
            display(capture.get())
            ReflectionHelpers.callInstanceMethod<Any>(capture.get(), "startCalibration")
            assertEquals(capture.get().getString(R.string.auto_open_target), ShadowToast.getTextOfLatestToast())
            ReflectionHelpers.callInstanceMethod<Any>(capture.get(), "toggleAuto")
            assertEquals(capture.get().getString(R.string.auto_open_target), ShadowToast.getTextOfLatestToast())
            assertFalse(ThrowState.controller.armed)
        } finally { capture.destroy(); gesture.destroy() }
    }
    @Config(sdk = [24])
    @Test fun androidSevenExplainsThatAutomaticHoldingNeedsANewerApi() {
        ThrowState.resetSession()
        val gesture = Robolectric.buildService(GestureThrowService::class.java).create()
        val capture = Robolectric.buildService(OverlayService::class.java).create()
        try {
            ReflectionHelpers.callInstanceMethod<Any>(gesture.get(), "onServiceConnected")
            display(capture.get())
            ReflectionHelpers.callInstanceMethod<Any>(capture.get(), "toggleAuto")
            assertEquals(capture.get().getString(R.string.auto_hold_unsupported), ShadowToast.getTextOfLatestToast())
            assertFalse(ThrowState.controller.armed)
        } finally { capture.destroy(); gesture.destroy() }
    }
}
