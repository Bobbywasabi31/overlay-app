package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test

class ThrowTargetResolverTest {
    private val own = "com.bobbywasabi.overlayapp"
    @Test fun practiceIsAllowedOnlyWhileItsActivityIsVisible() {
        assertEquals(ThrowTarget.PRACTICE, ThrowTargetResolver.resolve(own, own, true))
        assertNull(ThrowTargetResolver.resolve(own, own, false))
    }
    @Test fun gameAndPracticeAreSeparateCalibrationTargets() {
        assertEquals(ThrowTarget.GAME, ThrowTargetResolver.resolve(ThrowTargetResolver.GAME_PACKAGE, own, true))
        assertNotEquals(ThrowTarget.GAME, ThrowTargetResolver.resolve(own, own, true))
    }
    @Test fun otherAppsAndUnknownWindowsCannotReceiveThrows() {
        assertNull(ThrowTargetResolver.resolve(null, own, true))
        assertNull(ThrowTargetResolver.resolve("com.android.settings", own, true))
        assertNull(ThrowTargetResolver.resolve("com.bobbywasabi.overlayapp.other", own, true))
    }
}
