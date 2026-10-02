package com.bobbywasabi.overlayapp

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PracticeSessionTest {
    @Test fun fullWindowHudLetsSwipesReachTheBall() {
        val lifecycle = Robolectric.buildActivity(DemoActivity::class.java).setup()
        try {
            val activity = lifecycle.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 2400)
            val scene = activity.findViewById<PracticeRingView>(R.id.practiceRing)
            assertEquals(root.width, scene.width)
            assertEquals(root.height, scene.height)
            val down = SystemClock.uptimeMillis()
            for ((action, elapsed, y) in listOf(Triple(MotionEvent.ACTION_DOWN, 0L, 2040f),
                Triple(MotionEvent.ACTION_MOVE, 200L, 1400f), Triple(MotionEvent.ACTION_UP, 350L, 1056f))) {
                val event = MotionEvent.obtain(down, down + elapsed, action, 540f, y, 0)
                try { assertTrue(root.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            assertEquals(1, scene.touches)
            assertEquals(activity.getString(R.string.demo_stats, 1, 1, 1),
                activity.findViewById<TextView>(R.id.tapCount).text.toString())
        } finally { lifecycle.pause().stop().destroy() }
    }
    @Test fun leavingPracticeImmediatelyDisarmsAndRemovesItsAllowlistEntry() {
        val activity = Robolectric.buildActivity(DemoActivity::class.java).setup()
        assertTrue(PracticeSession.visible)
        ThrowState.calibrate(ThrowPlanner.Ball(0.5f, 0.85f), ThrowTarget.PRACTICE)
        ThrowState.controller.arm()
        activity.pause()
        assertFalse(PracticeSession.visible)
        assertFalse(ThrowState.controller.armed)
        activity.stop().destroy()
    }
    @Test fun practiceCalibrationCannotBeUsedAsAGameCalibration() {
        ThrowState.calibrate(ThrowPlanner.Ball(0.5f, 0.85f), ThrowTarget.PRACTICE)
        assertEquals(ThrowTarget.PRACTICE, ThrowState.target)
        assertNotEquals(ThrowTarget.GAME, ThrowState.target)
        ThrowState.resetSession()
        assertNull(ThrowState.ball)
        assertNull(ThrowState.target)
    }
}
