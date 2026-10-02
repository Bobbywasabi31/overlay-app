package com.bobbywasabi.overlayapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/** Reads only the focused application's package and bounds; never reads or stores UI text. */
class GestureThrowService : AccessibilityService() {
    var busy = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        ThrowState.disarm()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!gameIsForeground) ThrowState.disarm()
    }

    val gameIsForeground: Boolean get() = gameWindowBounds() != null

    @Suppress("DEPRECATION")
    fun gameWindowBounds(): Rect? {
        val window = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive && it.isFocused
        } ?: return null
        val root = window.root ?: return null
        return try {
            if (root.packageName?.toString() != GAME_PACKAGE) null
            else Rect().also { window.getBoundsInScreen(it) }
        } finally { root.recycle() }
    }

    fun throwBall(swipe: ThrowPlanner.Swipe, completed: (Boolean) -> Unit): Boolean {
        if (busy || !gameIsForeground || !SessionState.current.isRunning) return false
        val path = Path().apply {
            moveTo(swipe.startX, swipe.startY)
            lineTo(swipe.endX, swipe.endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, swipe.durationMs)).build()
        busy = true
        return try {
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { busy = false; completed(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { busy = false; completed(false) }
            }, null)
            if (!dispatched) busy = false
            dispatched
        } catch (error: RuntimeException) { busy = false; throw error }
    }

    override fun onInterrupt() { ThrowState.disarm() }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() {
        if (current === this) current = null
        ThrowState.disarm()
    }

    companion object {
        const val GAME_PACKAGE = "com.nianticlabs.pokemongo"
        var current: GestureThrowService? = null
            private set
    }
}
