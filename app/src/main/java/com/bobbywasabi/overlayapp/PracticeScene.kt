package com.bobbywasabi.overlayapp

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Screen-space encounter drill. Scores release position, not Pokémon GO's unknown ball physics. */
class PracticeScene(startMs: Long = 0) {
    enum class Result { INNER_RING, OUTER_TARGET, MISS }
    data class Geometry(
        val targetX: Float, val targetY: Float, val ringRadius: Float, val targetRadius: Float,
        val ballX: Float, val ballY: Float, val ballRadius: Float,
    )
    data class Release(val x: Float, val y: Float, val timeMs: Long, val result: Result)
    var smallPale = false
    var moving = false
    var colorIndex = 0
    var attempts = 0
        private set
    var innerHits = 0
        private set
    var release: Release? = null
        private set
    private var cycleStart = startMs

    fun ready(nowMs: Long): Boolean = release?.let { nowMs - it.timeMs >= RECOVERY_MS } ?: true

    fun geometry(width: Int, height: Int, nowMs: Long): Geometry {
        require(width > 0 && height > 0)
        val short = min(width, height).toFloat()
        val elapsed = (nowMs - cycleStart).coerceAtLeast(0)
        val phase = (elapsed % CYCLE_MS).toFloat() / CYCLE_MS
        val radius = if (smallPale) 0.055f - phase * 0.032f else 0.17f - phase * 0.15f
        val offset = if (moving) sin(elapsed / 2000.0).toFloat() * 0.07f else 0f
        return Geometry(width * (0.5f + offset), height * 0.44f, short * radius, short * 0.18f,
            width * 0.5f, height * 0.85f, short * 0.065f)
    }

    fun submit(width: Int, height: Int, startX: Float, startY: Float, endX: Float, endY: Float,
        downMs: Long, upMs: Long): Result? {
        if (width <= 0 || height <= 0 || !ready(upMs) || upMs - downMs !in 150L..600L ||
            !startX.isFinite() || !startY.isFinite() || !endX.isFinite() || !endY.isFinite()) return null
        val g = geometry(width, height, upMs)
        if (hypot(startX - g.ballX, startY - g.ballY) > g.ballRadius * 1.4f ||
            startY - endY < height * 0.12f) return null
        val distance = hypot(endX - g.targetX, endY - g.targetY)
        val result = when {
            distance <= g.ringRadius -> Result.INNER_RING
            distance <= g.targetRadius -> Result.OUTER_TARGET
            else -> Result.MISS
        }
        release = Release(endX / width, endY / height, upMs, result)
        attempts++
        if (result == Result.INNER_RING) innerHits++
        return result
    }

    fun restart(nowMs: Long) { release = null; cycleStart = nowMs }

    companion object {
        const val CYCLE_MS = 6000L
        // Long enough for the capture pipeline to observe a disappearance before repeat throws.
        const val RECOVERY_MS = 2400L
        const val FLIGHT_MS = 500L
    }
}
