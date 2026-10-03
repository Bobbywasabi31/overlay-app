package com.bobbywasabi.overlayapp

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class PracticePipelineTest {
    // Rasterize the shared encounter geometry at the actual 960-pixel capture limit.
    private fun frame(scene: PracticeScene, time: Long, held: Boolean = true): IntArray {
        val g = scene.geometry(432, 960, time)
        return IntArray(432 * 960) { i ->
            val x = (i % 432).toFloat()
            val y = (i / 432).toFloat()
            val distance = hypot(x - g.targetX, y - g.targetY)
            val stroke = if (scene.smallPale) 0.7f else 1.3f
            when {
                !scene.ready(time) -> 0xff404b53.toInt()
                scene.ringVisible(time, held) && abs(distance - g.ringRadius) <= stroke -> if (scene.smallPale) 0xff9ed395.toInt() else 0xff6df28a.toInt()
                hypot(x - g.ballX, y - g.ballY) <= g.ballRadius -> if (y < g.ballY) 0xffd95258.toInt() else 0xfff0f0f0.toInt()
                else -> 0xff85898d.toInt()
            }
        }
    }
    @Test fun practiceDetectionTrackingAndPlannerReachTheVisibleTarget() {
        val scene = PracticeScene()
        val analyzer = ScreenAnalyzer()
        val tracker = RingTracker()
        val gate = AutoThrowController().apply { arm() }
        var tracked: ScreenAnalyzer.Ring? = null
        var ready = false
        for (i in 0..7) {
            val now = i * 67L
            tracked = tracker.update(analyzer.analyze(frame(scene, now), 432, 960), now)
            ready = gate.consider(tracked, now)
        }
        assertTrue(ready)
        val swipe = requireNotNull(ThrowPlanner.plan(requireNotNull(tracked), ThrowPlanner.Ball(0.5f, 0.85f), 1080, 2400, 350))
        assertEquals(PracticeScene.Result.INNER_RING, scene.submit(1080, 2400, swipe.startX, swipe.startY,
            swipe.endX, swipe.endY, 469, 819))
        assertNull(analyzer.analyze(frame(scene, 900), 432, 960))
    }
    @Test fun smallPalePracticeRingSurvivesCaptureScaleWithBallPresent() {
        val scene = PracticeScene().apply { smallPale = true }
        val ring = requireNotNull(ScreenAnalyzer().analyze(frame(scene, 5999), 432, 960))
        assertEquals(0.5f, ring.x, 0.01f)
        assertEquals(0.44f, ring.y, 0.01f)
        assertEquals(scene.geometry(432, 960, 5999).ringRadius / 432, ring.radius, 0.005f)
    }
    @Test fun detectorSeesNoRingUntilBallIsHeld() {
        val scene = PracticeScene()
        val analyzer = ScreenAnalyzer()
        assertNull(analyzer.analyze(frame(scene, 0, held = false), 432, 960))
        assertNotNull(analyzer.analyze(frame(scene, 750, held = true), 432, 960))
        assertNull(analyzer.analyze(frame(scene, 850, held = false), 432, 960))
    }
}
