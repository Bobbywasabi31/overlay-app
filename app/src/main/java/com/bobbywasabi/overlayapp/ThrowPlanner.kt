package com.bobbywasabi.overlayapp

/** A configurable straight swipe, not a calibrated ballistic/gameplay model. */
object ThrowPlanner {
    data class Ball(val x: Float, val y: Float)
    data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float, val durationMs: Long)

    fun validBall(ball: Ball): Boolean = ball.x in 0.1f..0.9f && ball.y in 0.65f..0.95f

    fun plan(ring: ScreenAnalyzer.Ring, ball: Ball, width: Int, height: Int, durationMs: Long): Swipe? {
        if (width <= 0 || height <= width || !validBall(ball) || durationMs !in 150L..600L) return null
        if (ring.x !in 0.12f..0.88f || ring.y !in 0.18f..0.82f ||
            ring.radius !in 0.009f..0.38f || ring.confidence !in 0.7f..1f || ball.y - ring.y < 0.12f) return null
        // Overshoot past the ring for a stronger throw; clamp to the screen.
        // A swipe that ends exactly at the ring falls short in-game.
        val overshoot = 1.3f
        val endX = (ball.x + (ring.x - ball.x) * overshoot).coerceIn(0f, 1f) * width
        val endY = (ball.y + (ring.y - ball.y) * overshoot).coerceIn(0f, 1f) * height
        return Swipe(ball.x * width, ball.y * height, endX, endY, durationMs)
    }
}
