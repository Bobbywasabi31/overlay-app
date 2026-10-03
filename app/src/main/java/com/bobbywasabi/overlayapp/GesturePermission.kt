package com.bobbywasabi.overlayapp

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.annotation.StringRes

/** An enabled Android permission is distinct from a connected gesture service. */
object GesturePermission {
    fun enabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val expected = ComponentName(context, GestureThrowService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.id?.let(ComponentName::unflattenFromString) == expected
        }
    }

    @StringRes fun status(context: Context): Int = when {
        GestureThrowService.current != null -> R.string.thrower_connected
        enabled(context) -> R.string.thrower_connecting
        else -> R.string.thrower_disconnected
    }

    @StringRes fun unavailable(context: Context): Int =
        if (enabled(context)) R.string.auto_service_connecting else R.string.auto_enable_service
}
