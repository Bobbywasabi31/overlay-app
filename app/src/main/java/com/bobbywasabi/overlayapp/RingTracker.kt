package com.bobbywasabi.overlayapp

import kotlin.math.abs
import kotlin.math.hypot

/** Require two consistent frames and clear immediately on a miss. */
class RingTracker {
    private var previous: ScreenAnalyzer.Ring? = null
    fun update(candidate: ScreenAnalyzer.Ring?): ScreenAnalyzer.Ring? {
        val old = previous
        previous = candidate
        if (candidate == null || old == null) return null
        if (hypot(candidate.x - old.x, candidate.y - old.y) > 0.08f || abs(candidate.radius - old.radius) > 0.07f) return null
        return candidate.copy(x = old.x * 0.35f + candidate.x * 0.65f, y = old.y * 0.35f + candidate.y * 0.65f)
    }
}
