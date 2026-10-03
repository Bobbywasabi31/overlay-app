package com.bobbywasabi.overlayapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlin.math.abs

/** Reads only the focused application's package and bounds; never reads or stores UI text. */
class GestureThrowService : AccessibilityService() {
    var busy = false
        private set
    private val handler = Handler(Looper.getMainLooper())
    private data class HeldBall(
        val stroke: GestureDescription.StrokeDescription, val ball: ThrowPlanner.Ball,
        val width: Int, val height: Int, val target: ThrowTarget,
        val controller: AutoThrowController, val epoch: Long,
        var ready: Boolean = false, var cancelled: Boolean = false, var releasing: Boolean = false,
    )
    private var held: HeldBall? = null
    val holding: Boolean get() = held != null
    val canContinueHold: Boolean get() = held?.let { it.ready && !it.cancelled && !it.releasing } == true
    private val stateObserver = Observer<Int> {
        held?.let { if (!holdMatchesSession(it) || !it.controller.armed) cancelHold() }
    }
    private val holdTimeout = Runnable {
        val ball = held ?: return@Runnable
        if (!ball.releasing) {
            val currentSession = holdMatchesSession(ball) && ball.controller.armed
            if (currentSession) ThrowState.disarm()
            // The original 750 ms press has ended even if its callback was lost.
            if (Build.VERSION.SDK_INT >= 26) releaseHold(ball)
            if (currentSession) Toast.makeText(this, R.string.auto_hold_timeout, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ThrowState.changes.observeForever(stateObserver)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        ThrowState.disarm()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (targetWindow()?.target != ThrowState.target) ThrowState.disarm()
    }

    val targetIsForeground: Boolean get() = targetWindow() != null
    data class TargetWindow(val target: ThrowTarget, val bounds: Rect)

    @Suppress("DEPRECATION")
    fun targetWindow(): TargetWindow? {
        val window = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive && it.isFocused
        } ?: return null
        val root = window.root ?: return null
        return try {
            val target = ThrowTargetResolver.resolve(root.packageName?.toString(), packageName, PracticeSession.visible)
            if (target == null) null
            else TargetWindow(target, Rect().also { window.getBoundsInScreen(it) })
        } finally { root.recycle() }
    }

    fun holdBall(ball: ThrowPlanner.Ball, width: Int, height: Int, frameTime: Long): Boolean {
        if (Build.VERSION.SDK_INT < 26 || busy || width >= height || !ThrowPlanner.validBall(ball) ||
            !ThrowState.controller.canBeginHold(frameTime) || ThrowState.ball != ball ||
            !CaptureTiming.isFreshForThrow(frameTime, SystemClock.elapsedRealtime())) return false
        val target = ThrowState.target ?: return false
        if (!targetMatches(target, width, height)) return false
        val stroke = GestureDescription.StrokeDescription(point(ball.x * width, ball.y * height), 0, HOLD_MS, true)
        val capture = HeldBall(stroke, ball, width, height, target, ThrowState.controller, ThrowState.inputEpoch)
        held = capture
        busy = true
        handler.postDelayed(holdTimeout, HOLD_TIMEOUT_MS)
        return try {
            val accepted = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (held !== capture || capture.releasing) return
                    capture.ready = true
                    if (capture.cancelled || !holdMatchesSession(capture) || !capture.controller.armed ||
                        !targetMatches(capture.target, width, height)) {
                        if (Build.VERSION.SDK_INT >= 26) releaseHold(capture)
                    }
                    ThrowState.changed()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (held === capture && !capture.releasing) {
                        val currentSession = holdMatchesSession(capture)
                        clearHold(capture)
                        if (currentSession) ThrowState.disarm()
                    }
                }
            }, handler)
            if (!accepted) clearHold(capture)
            if (accepted) ThrowState.changed()
            accepted
        } catch (error: RuntimeException) { clearHold(capture); throw error }
    }

    /** Continue the same pointer; a second independent swipe would release the revealed ring. */
    fun throwBall(swipe: ThrowPlanner.Swipe, frameTime: Long, completed: (Boolean) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val capture = held ?: return false
        if (!canContinueHold || !holdMatchesSession(capture) || !capture.controller.busy ||
            !targetMatches(capture.target, capture.width, capture.height) ||
            !CaptureTiming.isFreshForThrow(frameTime, SystemClock.elapsedRealtime()) ||
            abs(swipe.startX - capture.ball.x * capture.width) > 1f ||
            abs(swipe.startY - capture.ball.y * capture.height) > 1f) return false
        val path = Path().apply {
            moveTo(swipe.startX, swipe.startY)
            lineTo(swipe.endX, swipe.endY)
        }
        val stroke = capture.stroke.continueStroke(path, 0, swipe.durationMs, false)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke).build()
        capture.releasing = true
        handler.removeCallbacks(holdTimeout)
        return try {
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { busy = false; completed(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { busy = false; completed(false) }
            }, handler)
            if (dispatched) held = null
            else { capture.releasing = false; releaseHold(capture) }
            dispatched
        } catch (error: RuntimeException) {
            capture.releasing = false
            releaseHold(capture)
            throw error
        }
    }

    fun cancelHold() {
        val capture = held ?: return
        capture.cancelled = true
        if (capture.ready && Build.VERSION.SDK_INT >= 26) releaseHold(capture)
    }
    private fun holdMatchesSession(capture: HeldBall): Boolean = SessionState.current.isRunning &&
        ThrowState.controller === capture.controller && ThrowState.inputEpoch == capture.epoch &&
        ThrowState.target == capture.target && ThrowState.ball == capture.ball
    private fun targetMatches(target: ThrowTarget, width: Int, height: Int): Boolean {
        val window = targetWindow() ?: return false
        return SessionState.current.isRunning && window.target == target &&
            window.bounds.width() >= width * 0.9 && window.bounds.height() >= height * 0.85
    }
    private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y) }
    @RequiresApi(26)
    private fun releaseHold(capture: HeldBall) {
        if (held !== capture || capture.releasing) return
        capture.releasing = true
        handler.removeCallbacks(holdTimeout)
        // A stationary continuation lifts the existing pointer, including after foreground loss.
        // Never use a new tap as a cleanup fallback in an unrelated application.
        val stroke = capture.stroke.continueStroke(
            point(capture.ball.x * capture.width, capture.ball.y * capture.height), 0, 1, false)
        try {
            val accepted = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { clearHold(capture) }
                override fun onCancelled(gestureDescription: GestureDescription?) { clearHold(capture) }
            }, handler)
            if (!accepted) clearHold(capture)
        } catch (error: RuntimeException) {
            Log.w("GestureThrowService", "Unable to release held ball", error)
            clearHold(capture)
        }
    }
    private fun clearHold(capture: HeldBall) {
        if (held !== capture) return
        handler.removeCallbacks(holdTimeout)
        held = null
        busy = false
        ThrowState.changed()
    }

    override fun onInterrupt() { ThrowState.disarm() }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        disconnect()
        ThrowState.changes.removeObserver(stateObserver)
        super.onDestroy()
    }
    private fun disconnect() {
        if (current === this) { current = null; ThrowState.disarm() }
        else cancelHold()
    }

    companion object {
        const val HOLD_MS = 750L
        const val HOLD_TIMEOUT_MS = 1500L
        var current: GestureThrowService? = null
            private set
    }
}
