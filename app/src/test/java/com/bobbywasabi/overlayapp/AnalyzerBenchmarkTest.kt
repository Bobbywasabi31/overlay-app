package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** #13: benchmark harness for ScreenAnalyzer.analyze(). Prints timing; hard-gates the budget. */
class AnalyzerBenchmarkTest {
    private val width = 540
    private val height = 960
    private val green = 0xff6df28a.toInt()

    private fun frame(): IntArray = IntArray(width * height) { i ->
        val cx = 270.0
        val cy = 528.0
        if (abs(hypot(i % width - cx, i / width - cy) - 88) <= 2.0) green else 0xff10141d.toInt()
    }

    @Test fun analyzeStaysInsideBudget() {
        val analyzer = ScreenAnalyzer()
        val pixels = frame()
        repeat(5) { analyzer.analyze(pixels, width, height) } // warm up
        val runs = 10
        val start = System.nanoTime()
        repeat(runs) { analyzer.analyze(pixels, width, height) }
        val avgMs = (System.nanoTime() - start) / 1_000_000.0 / runs
        println("analyze() avg ${"%.2f".format(avgMs)}ms over $runs runs on ${width}x$height")
        assertTrue("analyze() avg ${avgMs}ms exceeds budget", avgMs < ScreenAnalyzer.ANALYSIS_BUDGET_MS)
    }

    @Test fun grayscaleFrameSkipsAnalysis() {
        val analyzer = ScreenAnalyzer()
        val gray = IntArray(width * height) { 0xff888888.toInt() }
        assertNull(analyzer.analyze(gray, width, height))
        assertEquals(mapOf("grayscale" to 1), analyzer.lastStats.rejected)
    }
}
