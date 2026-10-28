package com.bobbywasabi.overlayapp

/**
 * Shared monotonic-time limits for capture, tracking, drawing, and gestures.
 *
 * Hold and throw decisions use the stale bound, not a tighter throw budget:
 * a frame's age includes the analysis itself, and on hard frames (arc
 * fallback) that alone can exceed a few hundred milliseconds. A tight budget
 * vetoed every hold and throw on exactly those frames. The ring's motion
 * during the admitted age is compensated in AutoThrowController's prediction
 * lead instead of discarding the frame.
 */
object CaptureTiming {
    const val ANALYSIS_INTERVAL_MS = 16L // At most 60 analyses/second; never queue old frames.
    const val STALE_AFTER_MS = 900L

    fun isFresh(frameTimeMs: Long, nowMs: Long): Boolean =
        nowMs >= frameTimeMs && nowMs - frameTimeMs <= STALE_AFTER_MS
}
