package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KillSwitchTest {
    @Test fun killLatchesAndDisarms() {
        ThrowState.resetSession()
        ThrowState.controller.arm()
        ThrowState.kill()
        assertTrue(ThrowState.killed)
        assertFalse(ThrowState.controller.armed)
    }

    @Test fun killSurvivesSessionReset() {
        ThrowState.resetSession()
        ThrowState.kill()
        ThrowState.resetSession()
        assertTrue(ThrowState.killed)
    }

    @Test fun reviveClearsTheLatch() {
        ThrowState.resetSession()
        ThrowState.kill()
        ThrowState.revive()
        assertFalse(ThrowState.killed)
    }

    @Test fun reviveIsIdempotent() {
        ThrowState.resetSession()
        ThrowState.revive()
        assertFalse(ThrowState.killed)
    }
}
