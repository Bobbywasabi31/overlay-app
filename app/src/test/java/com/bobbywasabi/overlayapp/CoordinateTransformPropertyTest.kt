package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/**
 * Item 43: property tests for normalized<->pixel coordinate transforms.
 * Randomized inputs with fixed seeds; plain JUnit loops, no new dependencies.
 * ThrowPlanner.plan is the normalized->pixel transform under test:
 * start = ball * dims, end = ring * dims.
 */
class CoordinateTransformPropertyTest {
    private val random = Random(0xC0FFEE)

    private fun validBall(): ThrowPlanner.Ball = ThrowPlanner.Ball(
        0.1f + random.nextFloat() * 0.8f,
        0.65f + random.nextFloat() * 0.3f)

    private fun validRing(ball: ThrowPlanner.Ball): ScreenAnalyzer.Ring {
        val maxY = ball.y - 0.12f
        return ScreenAnalyzer.Ring(
            0.12f + random.nextFloat() * 0.76f,
            0.18f + random.nextFloat() * (maxY - 0.18f),
            0.009f + random.nextFloat() * 0.371f,
            0.7f + random.nextFloat() * 0.3f)
    }

    private fun portraitDims(): Pair<Int, Int> {
        val width = 540 + random.nextInt(1080)
        return width to width + 1 + random.nextInt(1600)
    }

    @Test fun swipeRoundTripsNormalizedCoordinates() {
        repeat(500) {
            val (width, height) = portraitDims()
            val ball = validBall()
            val ring = validRing(ball)
            val swipe = requireNotNull(ThrowPlanner.plan(ring, ball, width, height, 350))
            assertEquals(ball.x, swipe.startX / width, 1e-4f)
            assertEquals(ball.y, swipe.startY / height, 1e-4f)
            // 1.3x overshoot past the ring, clamped to the screen.
            val expectedEndX = (ball.x + (ring.x - ball.x) * 1.3f).coerceIn(0f, 1f)
            val expectedEndY = (ball.y + (ring.y - ball.y) * 1.3f).coerceIn(0f, 1f)
            assertEquals(expectedEndX, swipe.endX / width, 1e-4f)
            assertEquals(expectedEndY, swipe.endY / height, 1e-4f)
        }
    }

    @Test fun swipeAlwaysTravelsUpward() {
        repeat(200) {
            val (width, height) = portraitDims()
            val ball = validBall()
            val swipe = requireNotNull(ThrowPlanner.plan(validRing(ball), ball, width, height, 350))
            assertTrue("end ${swipe.endY} should be above start ${swipe.startY}", swipe.endY < swipe.startY)
        }
    }

    @Test fun swipeDurationPassesThrough() {
        repeat(100) {
            val (width, height) = portraitDims()
            val duration = 150L + random.nextInt(451)
            val ball = validBall()
            val swipe = requireNotNull(ThrowPlanner.plan(validRing(ball), ball, width, height, duration))
            assertEquals(duration, swipe.durationMs)
        }
    }

    @Test fun nonPortraitDimensionsAreAlwaysRejected() {
        val ball = ThrowPlanner.Ball(0.5f, 0.85f)
        val ring = ScreenAnalyzer.Ring(0.5f, 0.4f, 0.05f, 0.9f)
        repeat(200) {
            val height = 540 + random.nextInt(800)
            val width = height + random.nextInt(800) // width >= height: landscape or square
            assertNull(ThrowPlanner.plan(ring, ball, width, height, 350))
        }
    }

    @Test fun degenerateDimensionsAreRejected() {
        val ball = ThrowPlanner.Ball(0.5f, 0.85f)
        val ring = ScreenAnalyzer.Ring(0.5f, 0.4f, 0.05f, 0.9f)
        for ((w, h) in listOf(0 to 2400, -5 to 2400, 1080 to 0, 1080 to -1)) {
            assertNull("dims $w x $h", ThrowPlanner.plan(ring, ball, w, h, 350))
        }
    }

    @Test fun ballBoundaryRectangleIsExact() {
        assertTrue(ThrowPlanner.validBall(ThrowPlanner.Ball(0.1f, 0.65f)))
        assertTrue(ThrowPlanner.validBall(ThrowPlanner.Ball(0.9f, 0.95f)))
        assertTrue(ThrowPlanner.validBall(ThrowPlanner.Ball(0.1f, 0.95f)))
        assertTrue(ThrowPlanner.validBall(ThrowPlanner.Ball(0.9f, 0.65f)))
        assertFalse(ThrowPlanner.validBall(ThrowPlanner.Ball(0.099f, 0.8f)))
        assertFalse(ThrowPlanner.validBall(ThrowPlanner.Ball(0.901f, 0.8f)))
        assertFalse(ThrowPlanner.validBall(ThrowPlanner.Ball(0.5f, 0.649f)))
        assertFalse(ThrowPlanner.validBall(ThrowPlanner.Ball(0.5f, 0.951f)))
    }

    @Test fun ringAcceptanceBoundariesAreExact() {
        val ball = ThrowPlanner.Ball(0.5f, 0.85f)
        fun ringAt(x: Float, y: Float) = ScreenAnalyzer.Ring(x, y, 0.05f, 0.9f)
        // Just inside the accepted center region.
        assertNotNull(ThrowPlanner.plan(ringAt(0.12f, 0.18f), ball, 1080, 2400, 350))
        assertNotNull(ThrowPlanner.plan(ringAt(0.88f, 0.72f), ball, 1080, 2400, 350))
        // Just outside on each edge.
        assertNull(ThrowPlanner.plan(ringAt(0.119f, 0.4f), ball, 1080, 2400, 350))
        assertNull(ThrowPlanner.plan(ringAt(0.881f, 0.4f), ball, 1080, 2400, 350))
        assertNull(ThrowPlanner.plan(ringAt(0.5f, 0.179f), ball, 1080, 2400, 350))
        assertNull(ThrowPlanner.plan(ringAt(0.5f, 0.821f), ball, 1080, 2400, 350))
    }

    @Test fun ringMustSitAboveBallByMinimumGap() {
        val ball = ThrowPlanner.Ball(0.5f, 0.85f)
        val ok = ScreenAnalyzer.Ring(0.5f, 0.73f, 0.05f, 0.9f) // gap exactly 0.12
        val bad = ScreenAnalyzer.Ring(0.5f, 0.731f, 0.05f, 0.9f) // gap just under 0.12
        assertNotNull(ThrowPlanner.plan(ok, ball, 1080, 2400, 350))
        assertNull(ThrowPlanner.plan(bad, ball, 1080, 2400, 350))
    }
}
