package com.bobbywasabi.overlayapp

/** Shared monotonic-time limits for capture, tracking, drawing, and gestures. */
object CaptureTiming {
    const val ANALYSIS_INTERVAL_MS = 40L // At most 25 analyses/second; never queue old frames.
    const val STALE_AFTER_MS = 900L
    const val THROW_MAX_AGE_MS = 200L

    fun isFresh(frameTimeMs: Long, nowMs: Long): Boolean =
        nowMs >= frameTimeMs && nowMs - frameTimeMs <= STALE_AFTER_MS

    fun isFreshForThrow(frameTimeMs: Long, nowMs: Long): Boolean =
        nowMs >= frameTimeMs && nowMs - frameTimeMs <= THROW_MAX_AGE_MS
}
