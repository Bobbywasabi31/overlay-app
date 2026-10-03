package com.bobbywasabi.overlayapp

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** Session-only settings. Arming and calibration never survive a capture restart. */
object ThrowState {
    var controller = AutoThrowController()
        private set
    var ball: ThrowPlanner.Ball? = null
        private set
    var target: ThrowTarget? = null
        private set
    var calibrationBounds: android.graphics.Rect? = null
        private set
    var durationMs = 350L
        private set
    var dryRun = false
        private set
    var killed = false
        private set
    var inputEpoch = 0L
        private set
    private val mutable = MutableLiveData(0)
    val changes: LiveData<Int> = mutable

    fun changed() { mutable.value = (mutable.value ?: 0) + 1 }
    fun disarm() { inputEpoch++; controller.disarm(); changed() }
    fun resetSession() {
        inputEpoch++
        controller.disarm()
        controller = AutoThrowController()
        ball = null
        target = null
        calibrationBounds = null
        changed()
    }
    fun calibrate(
        value: ThrowPlanner.Ball,
        destination: ThrowTarget = ThrowTarget.GAME,
        bounds: android.graphics.Rect? = null,
    ) {
        require(ThrowPlanner.validBall(value))
        inputEpoch++
        controller.disarm()
        ball = value
        target = destination
        calibrationBounds = bounds?.let { android.graphics.Rect(it) }
        changed()
    }
    /** #82: kill switch. Latches until explicitly revived; survives session resets. */
    fun kill() {
        killed = true
        disarm()
        changed()
    }
    fun revive() {
        if (!killed) return
        killed = false
        changed()
    }
    /** Dry-run exercises the full Auto pipeline but sends no gestures. */
    fun setDryRun(value: Boolean) {
        if (dryRun == value) return
        dryRun = value
        disarm()
        changed()
    }
    fun setDuration(value: Long) {
        require(value in 150L..600L)
        inputEpoch++
        controller.disarm()
        durationMs = value
        changed()
    }
}
