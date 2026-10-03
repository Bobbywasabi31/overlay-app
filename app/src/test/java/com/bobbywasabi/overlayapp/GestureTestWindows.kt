package com.bobbywasabi.overlayapp

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.robolectric.Shadows.shadowOf

@Suppress("DEPRECATION")
internal fun focusGestureWindow(service: GestureThrowService, packageName: String, width: Int = 1080, height: Int = 2400) {
    val root = AccessibilityNodeInfo.obtain().apply { this.packageName = packageName }
    val window = AccessibilityWindowInfo.obtain()
    shadowOf(window).apply {
        setRoot(root)
        setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        setActive(true)
        setFocused(true)
        setBoundsInScreen(Rect(0, 0, width, height))
    }
    shadowOf(service).setWindows(listOf(window))
}
