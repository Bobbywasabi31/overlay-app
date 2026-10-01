package com.bobbywasabi.overlayapp

import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** In-process state: consent is never saved or reused after process death. */
object SessionState {
    enum class Phase { IDLE, STARTING, RUNNING, ERROR }
    data class Status(val phase: Phase, @StringRes val message: Int) {
        val isActive: Boolean get() = phase == Phase.STARTING || phase == Phase.RUNNING
    }
    private val mutable = MutableLiveData(Status(Phase.IDLE, R.string.message_idle))
    val status: LiveData<Status> = mutable
    val current: Status get() = checkNotNull(mutable.value)
    // Activity and service publish on the main thread.
    fun update(phase: Phase, @StringRes message: Int) {
        mutable.value = Status(phase, message)
    }
}
