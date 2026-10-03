package com.bobbywasabi.overlayapp

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GesturePermissionTest {
    private fun enabledService(id: String) = AccessibilityServiceInfo().also {
        ReflectionHelpers.setField(it, "mId", id)
    }
    @Test fun anotherAccessibilityServiceDoesNotGrantThisAppsGesturePermission() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(
            enabledService("com.example.other/.GestureThrowService"),
            enabledService("${context.packageName}/.OtherService")))
        assertFalse(GesturePermission.enabled(context))
        assertEquals(R.string.auto_enable_service, GesturePermission.unavailable(context))
    }
    @Test fun enabledPermissionAndConnectedServiceAreRenderedSeparately() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val component = ComponentName(context, GestureThrowService::class.java)
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(enabledService(component.flattenToShortString())))
        assertTrue(GesturePermission.enabled(context))
        assertEquals(R.string.auto_service_connecting, GesturePermission.unavailable(context))
        SessionState.update(SessionState.Phase.IDLE, R.string.message_idle)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val service = Robolectric.buildService(GestureThrowService::class.java).create()
        try {
            val label = activity.get().findViewById<TextView>(R.id.throwerStatus)
            assertEquals(context.getString(R.string.thrower_connecting), label.text.toString())
            ReflectionHelpers.callInstanceMethod<Any>(service.get(), "onServiceConnected")
            assertEquals(context.getString(R.string.thrower_connected), label.text.toString())
            service.get().onUnbind(null)
            assertEquals(context.getString(R.string.thrower_connecting), label.text.toString())
            assertTrue(GesturePermission.enabled(context))
        } finally { activity.pause().stop().destroy(); service.destroy() }
    }
}
