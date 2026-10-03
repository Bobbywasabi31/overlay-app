package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TosAckTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun notAcknowledgedByDefault() {
        assertFalse(TosAck.isAcknowledged(context))
    }

    @Test fun acknowledgementPersists() {
        TosAck.setAcknowledged(context)
        assertTrue(TosAck.isAcknowledged(context))
    }
}
