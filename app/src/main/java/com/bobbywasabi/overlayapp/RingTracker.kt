package com.bobbywasabi.overlayapp

import kotlin.math.abs
import kotlin.math.hypot

/** Require two consistent frames and clear immediately on a miss. */
class RingTracker {
    private var previous: ScreenAnalyzer.Ring? = null
    private var previousTime: Long? = null
    private var displayHold: ScreenAnalyzer.Ring? = null
    private var displayHoldTime: Long = 0L
    fun update(candidate: ScreenAnalyzer.Ring?, nowMs: Long = System.nanoTime() / 1_000_000): ScreenAnalyzer.Ring? {
        val time = previousTime
        if (time != null && !CaptureTiming.isFresh(time, nowMs)) previous = null
        val old = previous
        previous = candidate
        previousTime = nowMs
        if (candidate == null || old == null) return null
        if (hypot(candidate.x - old.x, candidate.y - old.y) > 0.08f || abs(candidate.radius - old.radius) > 0.07f) return null
        val confirmed = candidate.copy(x = old.x * 0.35f + candidate.x * 0.65f, y = old.y * 0.35f + candidate.y * 0.65f)
        displayHold = confirmed
        displayHoldTime = nowMs
        return confirmed
    }

    /**
     * Last confirmed target, held briefly for display so the marker does not
     * flicker on a single dropped frame. Throw decisions must keep using
     * [update], which stays strict.
     */
    fun displayTarget(nowMs: Long = System.nanoTime() / 1_000_000): ScreenAnalyzer.Ring? {
        val held = displayHold
        return if (held != null && nowMs >= displayHoldTime && nowMs - displayHoldTime <= DISPLAY_HOLD_MS) held else null
    }

    companion object {
        const val DISPLAY_HOLD_MS = 150L
    }
}
