package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class ScreenAnalyzerTest {
    private val analyzer = ScreenAnalyzer()
    private val width = 240
    private val height = 400
    private val green = 0xff6df28a.toInt()
    private fun frame(color: Int = green, cx: Double = 120.0, cy: Double = 220.0, radius: Double = 44.0): IntArray =
        IntArray(width * height) { i ->
            if (abs(hypot(i % width - cx, i / width - cy) - radius) <= 2.0) color else 0xff10141d.toInt()
        }
    @Test fun detectsRedYellowAndGreenRings() {
        for (color in intArrayOf(green, 0xffffd35a.toInt(), 0xfff84856.toInt())) {
            val ring = requireNotNull(analyzer.analyze(frame(color), width, height))
            assertEquals(0.5f, ring.x, 0.01f)
            assertEquals(0.55f, ring.y, 0.01f)
            assertEquals(44f / width, ring.radius, 0.01f)
            assertTrue(ring.confidence > 0.7f)
        }
    }
    @Test fun cyanGuidanceCannotDetectItself() {
        assertNull(analyzer.analyze(frame(0xff64d8eb.toInt()), width, height))
        assertNull(analyzer.analyze(frame(0xffffffff.toInt()), width, height))
    }
    @Test fun blankFrameClearsPreviousResult() {
        assertNotNull(analyzer.analyze(frame(), width, height))
        assertNull(analyzer.analyze(IntArray(width * height), width, height))
    }
    @Test fun rejectsSolidDisk() {
        val pixels = IntArray(width * height) { i -> if (hypot(i % width - 120.0, i / width - 220.0) <= 44) green else 0 }
        assertNull(analyzer.analyze(pixels, width, height))
    }
    @Test fun rejectsSquareOutline() {
        val pixels = IntArray(width * height) { i ->
            val x = abs(i % width - 120)
            val y = abs(i / width - 220)
            if ((x in 42..46 && y <= 46) || (y in 42..46 && x <= 46)) green else 0
        }
        assertNull(analyzer.analyze(pixels, width, height))
    }
    @Test fun rejectsIncompleteArc() {
        val pixels = frame()
        for (y in 0 until height) for (x in 0 until 120) pixels[y * width + x] = 0
        assertNull(analyzer.analyze(pixels, width, height))
    }
    @Test fun rejectsRingClippedAtEdge() {
        assertNull(analyzer.analyze(frame(cx = 20.0), width, height))
    }
    @Test fun ignoresSmallInterfaceIcons() {
        assertNull(analyzer.analyze(frame(cy = 20.0, radius = 10.0), width, height))
    }
    @Test fun findsRingAmongScatteredColoredNoise() {
        val pixels = frame()
        val random = Random(17)
        repeat(200) { pixels[random.nextInt(pixels.size)] = green }
        val result = requireNotNull(analyzer.analyze(pixels, width, height))
        assertEquals(0.5f, result.x, 0.02f)
        assertEquals(0.55f, result.y, 0.02f)
    }
    @Test fun normalizesLandscapeCoordinatesAndResizesBuffers() {
        analyzer.analyze(frame(), width, height)
        val landscapeWidth = 400
        val landscapeHeight = 300
        val pixels = IntArray(landscapeWidth * landscapeHeight) { i ->
            if (abs(hypot(i % landscapeWidth - 240.0, i / landscapeWidth - 150.0) - 32) <= 2) green else 0
        }
        val ring = requireNotNull(analyzer.analyze(pixels, landscapeWidth, landscapeHeight))
        assertEquals(0.6f, ring.x, 0.01f)
        assertEquals(0.5f, ring.y, 0.01f)
        assertEquals(32f / landscapeHeight, ring.radius, 0.01f)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidFrameDimensions() { analyzer.analyze(IntArray(4), 3, 3) }
}
