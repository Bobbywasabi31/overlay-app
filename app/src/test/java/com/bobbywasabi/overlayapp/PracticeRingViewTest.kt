package com.bobbywasabi.overlayapp

import android.os.SystemClock
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PracticeRingViewTest {
    private fun touch(view: PracticeRingView, down: Long, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, time, action, x, y, 0)
        try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
    }
    @Test fun injectedSwipeEventsProduceHitFeedback() {
        val view = PracticeRingView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 1080, 2400)
        var result: PracticeScene.Result? = null
        var throws = 0
        var hits = 0
        view.feedback = { value, _, count, inner -> result = value; throws = count; hits = inner }
        val down = SystemClock.uptimeMillis()
        touch(view, down, down, MotionEvent.ACTION_DOWN, 540f, 2040f)
        touch(view, down, down + 200, MotionEvent.ACTION_MOVE, 540f, 1400f)
        touch(view, down, down + 350, MotionEvent.ACTION_UP, 540f, 1056f)
        assertEquals(PracticeScene.Result.INNER_RING, result)
        assertEquals(1, view.touches)
        assertEquals(1, throws)
        assertEquals(1, hits)
    }
    @Test fun cancelledSwipeCannotScoreOrConsumeAnAttempt() {
        val view = PracticeRingView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 1080, 2400)
        var throws = 0
        view.feedback = { _, _, count, _ -> throws = count }
        val down = SystemClock.uptimeMillis()
        touch(view, down, down, MotionEvent.ACTION_DOWN, 540f, 2040f)
        touch(view, down, down + 150, MotionEvent.ACTION_CANCEL, 540f, 1400f)
        touch(view, down, down + 350, MotionEvent.ACTION_UP, 540f, 1056f)
        assertEquals(0, throws)
    }
}
