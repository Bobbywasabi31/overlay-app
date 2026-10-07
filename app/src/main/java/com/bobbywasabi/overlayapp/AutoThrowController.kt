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
    /** Maximum ring radius (normalized) that we'll throw at. Defaults to excellent size. */
    var maxThrowRadius = EXCELLENT_RADIUS_MAX
    /**
     * Lead time (ms) between the throw decision and the gesture's release.
     * The ring keeps shrinking while the swipe runs, so we predict the radius
     * at release time. Kept in sync with ThrowState.durationMs by the caller.
     */
    var throwLeadMs = 200L
    private var previous: ScreenAnalyzer.Ring? = null
    private var previousTime: Long? = null
    private var stableSince: Long? = null
    private var absentSince: Long? = null
    private var throwConfidence = 0f
    private var waitingForClear = false
    private var lastThrow: Long? = null
    /** Timestamped radius samples of the currently tracked ring, oldest first. */
    private val radiusHistory = ArrayDeque<RadiusSample>()
    private data class RadiusSample(val timeMs: Long, val radius: Float)

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
        radiusHistory.clear()
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
            // Tolerate brief flicker: only drop the stability timer if the ring
            // stays absent past the grace period.
            val absent = absentSince ?: nowMs.also { absentSince = it }
            if (nowMs - absent >= NULL_GRACE_MS) stableSince = null
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
            // Anchor moved or the ring cycle reset: the old shrink trend no longer applies.
            radiusHistory.clear()
            recordRadiusSample(nowMs, ring.radius)
            previous = ring
            stableSince = nowMs
            return false
        }
        recordRadiusSample(nowMs, ring.radius)
        val anchored = stableSince != null
        val stable = stableSince ?: nowMs.also { stableSince = it }
        val last = lastThrow
        if (waitingForClear || throwConfidence < THROW_CONFIDENCE ||
            (last != null && nowMs - last < COOLDOWN_MS)) return false
        // #4 staged gate: track loosely, but throw only while confidence is high.
        // Shrink prediction: the ring is still shrinking, so start the throw now
        // when the predicted radius at gesture release will be inside the
        // excellent window — waiting for the radius gate would let the window close first.
        if (ring.radius > maxThrowRadius && anchored && predictThrow(ring.radius)) return true
        // Fallback: the ring is already at excellent size and holding steady.
        return nowMs - stable >= STABLE_MS && ring.radius <= maxThrowRadius
    }

    /**
     * True when the recorded radius history shows a steady shrink whose linear
     * extrapolation lands inside the excellent window [EXCELLENT_RADIUS_MIN,
     * maxThrowRadius] at gesture-release time (now + throwLeadMs).
     */
    private fun predictThrow(radius: Float): Boolean {
        val shrinkPerMs = estimateShrinkRate() ?: return false
        val predicted = radius + shrinkPerMs * throwLeadMs
        return predicted <= maxThrowRadius && predicted >= EXCELLENT_RADIUS_MIN
    }

    /**
     * Least-squares shrink rate (normalized radius per ms, negative when
     * shrinking), or null when the history is too short, too gappy, too noisy,
     * or the rate is outside the plausible range for a real ring cycle.
     */
    private fun estimateShrinkRate(): Double? {
        if (radiusHistory.size < MIN_PREDICT_SAMPLES) return null
        val first = radiusHistory.first().timeMs
        val last = radiusHistory.last().timeMs
        if (last - first < MIN_PREDICT_SPAN_MS) return null
        var sumT = 0.0
        var sumR = 0.0
        var sumTR = 0.0
        var sumT2 = 0.0
        for (sample in radiusHistory) {
            sumT += sample.timeMs
            sumR += sample.radius
            sumTR += sample.timeMs * sample.radius
            sumT2 += sample.timeMs * sample.timeMs
        }
        val n = radiusHistory.size.toDouble()
        val denominator = n * sumT2 - sumT * sumT
        if (denominator <= 0.0) return null
        val slope = (n * sumTR - sumT * sumR) / denominator
        if (slope >= -MIN_SHRINK_RATE_PER_MS || slope <= -MAX_SHRINK_RATE_PER_MS) return null
        val intercept = (sumR - slope * sumT) / n
        for (sample in radiusHistory) {
            if (abs(slope * sample.timeMs + intercept - sample.radius) > MAX_PREDICT_RESIDUAL) return null
        }
        return slope
    }

    private fun recordRadiusSample(nowMs: Long, radius: Float) {
        val last = radiusHistory.lastOrNull()
        if (last != null && nowMs <= last.timeMs) return
        radiusHistory.addLast(RadiusSample(nowMs, radius))
        while (radiusHistory.size > MAX_HISTORY_SAMPLES) radiusHistory.removeFirst()
        val cutoff = nowMs - MAX_HISTORY_AGE_MS
        while (radiusHistory.size > 1 && radiusHistory.first().timeMs < cutoff) radiusHistory.removeFirst()
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
        const val STABLE_MS = 250L
        const val CLEAR_MS = 1200L
        const val COOLDOWN_MS = 3000L
        const val MAX_THROWS = 5
        /** Only throw when the ring has shrunk to excellent size (or smaller).
         * 0.07 gives the detector margin — the ring moves fast and a tight
         * 0.05 means the window closes before the stability timer finishes. */
        const val EXCELLENT_RADIUS_MAX = 0.07f
        /** Smallest radius still plausibly inside the excellent window; a
         * prediction below this means the ring cycle has reset past it. */
        const val EXCELLENT_RADIUS_MIN = 0.03f
        /** Brief analyzer flicker does not reset the stability timer. */
        const val NULL_GRACE_MS = 200L
        /** #4: rings at least this confident are tracked. */
        const val TRACK_CONFIDENCE = 0.5f
        /** #4: only anchors at least this confident may trigger a throw. */
        const val THROW_CONFIDENCE = 0.75f
        /** #5: below this, a borderline frame drops the track instead of holding it. */
        const val DROP_CONFIDENCE = 0.45f
        /** Prediction needs this many radius samples spanning this much time. */
        private const val MIN_PREDICT_SAMPLES = 4
        private const val MIN_PREDICT_SPAN_MS = 150L
        /** History window bounds for the shrink-rate regression. */
        private const val MAX_HISTORY_SAMPLES = 16
        private const val MAX_HISTORY_AGE_MS = 600L
        /** Plausible ring shrink rates, normalized radius per ms: 0.02/s..0.5/s. */
        private const val MIN_SHRINK_RATE_PER_MS = 0.00002
        private const val MAX_SHRINK_RATE_PER_MS = 0.0005
        /** A sample further than this from the regression line vetoes prediction. */
        private const val MAX_PREDICT_RESIDUAL = 0.006f
    }
}
