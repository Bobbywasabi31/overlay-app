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
    private var throwConfidence = 0f
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
        throwConfidence = 0f
    }

    /** Holding reveals a hidden ring, but cannot bypass repeat and cooldown checks. */
    fun canBeginHold(nowMs: Long): Boolean = armed && !busy && !waitingForClear &&
        (lastThrow?.let { nowMs - it >= COOLDOWN_MS } ?: true)

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
        val confident = ring.confidence >= TRACK_CONFIDENCE
        // #5 hysteresis: once a track is acquired, a borderline frame holds the
        // stability timer instead of flapping it; only a weak frame drops it.
        if (!confident && !(ring.confidence >= DROP_CONFIDENCE && stableSince != null)) {
            previous = null
            stableSince = null
            return false
        }
        if (!confident) return false
        throwConfidence = ring.confidence
        // Compare against the stability anchor, not the last frame: slow drift is still motion.
        if (old == null ||
            hypot(ring.x - old.x, ring.y - old.y) > 0.025f || abs(ring.radius - old.radius) > 0.025f) {
            previous = ring
            stableSince = nowMs
            return false
        }
        val stable = stableSince ?: nowMs.also { stableSince = it }
        val last = lastThrow
        // #4 staged gate: track loosely, but throw only while confidence is high.
        return !waitingForClear && nowMs - stable >= STABLE_MS &&
            throwConfidence >= THROW_CONFIDENCE &&
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
        /** #4: rings at least this confident are tracked. */
        const val TRACK_CONFIDENCE = 0.6f
        /** #4: only anchors at least this confident may trigger a throw. */
        const val THROW_CONFIDENCE = 0.75f
        /** #5: below this, a borderline frame drops the track instead of holding it. */
        const val DROP_CONFIDENCE = 0.45f
    }
}
