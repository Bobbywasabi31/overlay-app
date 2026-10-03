package com.bobbywasabi.overlayapp

import android.graphics.Canvas
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.Random
import kotlin.math.min

/**
 * Item 42: GuidanceView unit tests. The marker positioning math lives in
 * onDraw, so tests drive it through a recording Canvas subclass and assert
 * the exact pixels the code computes from normalized ring coordinates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GuidanceViewTest {
    private val context = RuntimeEnvironment.getApplication()

    private class RecordingCanvas : Canvas() {
        data class Circle(val cx: Float, val cy: Float, val radius: Float)
        data class Line(val startX: Float, val startY: Float, val stopX: Float, val stopY: Float)
        data class Text(val text: String, val x: Float, val y: Float)
        val circles = mutableListOf<Circle>()
        val lines = mutableListOf<Line>()
        val texts = mutableListOf<Text>()
        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
            circles.add(Circle(cx, cy, radius))
        }
        override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
            lines.add(Line(startX, startY, stopX, stopY))
        }
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts.add(Text(text, x, y))
        }
    }

    private fun draw(view: GuidanceView): RecordingCanvas {
        val canvas = RecordingCanvas()
        ReflectionHelpers.callInstanceMethod<Any>(
            view, "onDraw", ReflectionHelpers.ClassParameter(Canvas::class.java, canvas))
        return canvas
    }

    private fun view(width: Int = 1080, height: Int = 2400): GuidanceView =
        GuidanceView(context, width, height).also { it.layout(0, 0, width, height) }

    private val density: Float get() = context.resources.displayMetrics.density

    private fun viewLocation(view: GuidanceView): IntArray =
        IntArray(2).also { view.getLocationOnScreen(it) }

    @Test fun ringMarkerIsCenteredOnNormalizedTarget() {
        val view = view()
        view.showRing(ScreenAnalyzer.Ring(0.5f, 0.4f, 0.05f, 0.9f))
        val canvas = draw(view)
        val loc = viewLocation(view)
        // The dashed circle is drawn outside the source ring (+9dp) so capture keeps seeing it.
        val expectedRadius = 0.05f * 1080f + 9f * density
        val circle = canvas.circles.single()
        assertEquals(540f - loc[0], circle.cx, 0.01f)
        assertEquals(960f - loc[1], circle.cy, 0.01f)
        assertEquals(expectedRadius, circle.radius, 0.01f)
        // Crosshair arms are min(8dp, 40% of radius).
        val arm = min(8f * density, 0.05f * 1080f * 0.4f)
        val cx = 540f - loc[0]
        val cy = 960f - loc[1]
        assertEquals(2, canvas.lines.size)
        val horizontal = canvas.lines[0]
        assertEquals(cx - arm, horizontal.startX, 0.01f)
        assertEquals(cx + arm, horizontal.stopX, 0.01f)
        assertEquals(cy, horizontal.startY, 0.01f)
        assertEquals(cy, horizontal.stopY, 0.01f)
        val vertical = canvas.lines[1]
        assertEquals(cx, vertical.startX, 0.01f)
        assertEquals(cy - arm, vertical.startY, 0.01f)
        assertEquals(cy + arm, vertical.stopY, 0.01f)
        // "Found" label sits above the marker.
        val label = canvas.texts.single()
        assertEquals(context.getString(R.string.overlay_found), label.text)
        assertEquals(cy - 0.05f * 1080f - 28f * density, label.y, 0.01f)
    }

    @Test fun showRingNullClearsMarker() {
        val view = view()
        view.showRing(ScreenAnalyzer.Ring(0.5f, 0.4f, 0.05f, 0.9f))
        assertEquals(1, draw(view).circles.size)
        view.showRing(null)
        val canvas = draw(view)
        assertTrue(canvas.circles.isEmpty())
        assertTrue(canvas.lines.isEmpty())
        val label = canvas.texts.single()
        assertEquals(context.getString(R.string.overlay_searching), label.text)
        assertEquals(52f * density, label.y, 0.01f)
    }

    @Test fun labelClampsAboveTopEdge() {
        val view = view()
        // Marker near the top: y - radius - 28dp would go off-screen for any sane density.
        view.showRing(ScreenAnalyzer.Ring(0.5f, 0.01f, 0.02f, 0.9f))
        val label = draw(view).texts.single()
        assertEquals(52f * density, label.y, 0.01f)
    }

    @Test fun radiusScalesWithShorterSide() {
        val view = GuidanceView(context, 2400, 1080).also { it.layout(0, 0, 2400, 1080) }
        view.showRing(ScreenAnalyzer.Ring(0.5f, 0.5f, 0.1f, 0.9f))
        val loc = viewLocation(view)
        val circle = draw(view).circles.single()
        assertEquals(1200f - loc[0], circle.cx, 0.01f)
        assertEquals(540f - loc[1], circle.cy, 0.01f)
        assertEquals(0.1f * 1080f + 9f * density, circle.radius, 0.01f)
    }

    @Test fun randomizedNormalizedToPixelMapping() {
        val random = Random(0x6D1E)
        repeat(100) {
            val width = 540 + random.nextInt(1080)
            val height = width + 1 + random.nextInt(1200)
            val ring = ScreenAnalyzer.Ring(
                0.12f + random.nextFloat() * 0.76f,
                0.18f + random.nextFloat() * 0.64f,
                0.01f + random.nextFloat() * 0.3f,
                0.9f)
            val view = GuidanceView(context, width, height).also { it.layout(0, 0, width, height) }
            view.showRing(ring)
            val loc = viewLocation(view)
            val circle = draw(view).circles.single()
            assertEquals(ring.x * width - loc[0], circle.cx, 0.05f)
            assertEquals(ring.y * height - loc[1], circle.cy, 0.05f)
            assertEquals(ring.radius * min(width, height) + 9f * density, circle.radius, 0.05f)
        }
    }

    @Test fun practiceSessionRendersMarkerIdentically() {
        // GuidanceView has no practice branch; document that rendering is mode-independent.
        PracticeSession.resume()
        try {
            val view = view()
            view.showRing(ScreenAnalyzer.Ring(0.5f, 0.4f, 0.05f, 0.9f))
            val loc = viewLocation(view)
            val circle = draw(view).circles.single()
            assertEquals(540f - loc[0], circle.cx, 0.01f)
            assertEquals(960f - loc[1], circle.cy, 0.01f)
        } finally {
            PracticeSession.pause()
        }
    }
}
