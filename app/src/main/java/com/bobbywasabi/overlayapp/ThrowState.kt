package com.bobbywasabi.overlayapp

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** Session-only settings. Arming and calibration never survive a capture restart. */
object ThrowState {
    var controller = AutoThrowController()
        private set
    var ball: ThrowPlanner.Ball? = null
        private set
    var durationMs = 350L
        private set
    private val mutable = MutableLiveData(0)
    val changes: LiveData<Int> = mutable

    fun changed() { mutable.value = (mutable.value ?: 0) + 1 }
    fun disarm() { controller.disarm(); changed() }
    fun resetSession() {
        controller.disarm()
        controller = AutoThrowController()
        ball = null
        changed()
    }
    fun calibrate(value: ThrowPlanner.Ball) {
        require(ThrowPlanner.validBall(value))
        controller.disarm()
        ball = value
        changed()
    }
    fun setDuration(value: Long) {
        require(value in 150L..600L)
        controller.disarm()
        durationMs = value
        changed()
    }
}
