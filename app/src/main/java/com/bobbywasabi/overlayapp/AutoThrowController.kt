package com.bobbywasabi.overlayapp

import kotlin.math.abs
import kotlin.math.hypot

/** Main-thread policy. A persistent ring can cause only one throw, never a timed swipe loop. */
class AutoThrowController {
    var armed = false
        private set
    var throws = 0
        private set
    var busy = false
        private set
    private var previous: ScreenAnalyzer.Ring? = null
    private var previousTime: Long? = null
    private var stableSince: Long? = null
    private var absentSince: Long? = null
    private var waitingForClear = false
    private var lastThrow: Long? = null

    fun arm() {
        disarm()
        throws = 0
        lastThrow = null
        waitingForClear = false
        armed = true
    }

    fun disarm() {
        armed = false
        resetTracking()
        // An already dispatched gesture must finish before another can be sent.
    }

    fun resetTracking() {
        previous = null
        previousTime = null
        stableSince = null
        absentSince = null
    }

    fun consider(ring: ScreenAnalyzer.Ring?, nowMs: Long): Boolean {
        if (!armed || busy) return false
        val time = previousTime
        if (time != null && !CaptureTiming.isFresh(time, nowMs)) resetTracking()
        previousTime = nowMs
        if (ring == null) {
            previous = null
            stableSince = null
            val absent = absentSince ?: nowMs.also { absentSince = it }
            if (nowMs - absent >= CLEAR_MS) waitingForClear = false
            return false
        }
        absentSince = null
        val old = previous
        if (ring.confidence !in 0.7f..1f) {
            previous = null
            stableSince = null
            return false
        }
        // Compare against the stability anchor, not the last frame: slow drift is still motion.
        if (old == null ||
            hypot(ring.x - old.x, ring.y - old.y) > 0.025f || abs(ring.radius - old.radius) > 0.025f) {
            previous = ring
            stableSince = nowMs
            return false
        }
        val stable = stableSince ?: nowMs.also { stableSince = it }
        val last = lastThrow
        return !waitingForClear && nowMs - stable >= STABLE_MS &&
            (last == null || nowMs - last >= COOLDOWN_MS)
    }

    /** Reserve before dispatch so synchronous/rapid callbacks cannot submit another swipe. */
    fun beginThrow(nowMs: Long) {
        check(armed && !busy)
        busy = true
        waitingForClear = true
        lastThrow = nowMs
        throws++
        if (throws >= MAX_THROWS) disarm()
    }

    fun finishThrow(success: Boolean) {
        busy = false
        resetTracking()
        if (!success) disarm()
    }

    companion object {
        const val STABLE_MS = 350L
        const val CLEAR_MS = 1200L
        const val COOLDOWN_MS = 3000L
        const val MAX_THROWS = 5
    }
}
