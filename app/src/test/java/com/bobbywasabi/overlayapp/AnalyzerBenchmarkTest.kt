package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test

/**
 * #13: analyzer correctness checks that gate the build. The wall-clock benchmark used
 * to live here too, but its hard mean-time gate flaked on shared CI runners (task 21),
 * so it moved to AnalyzerTimingBenchmarkTest, which runs in the separate informational
 * `benchmark` Gradle task instead of gating the build.
 */
class AnalyzerBenchmarkTest {
    private val width = 540
    private val height = 960

    @Test fun grayscaleFrameSkipsAnalysis() {
        val analyzer = ScreenAnalyzer()
        val gray = IntArray(width * height) { 0xff888888.toInt() }
        assertNull(analyzer.analyze(gray, width, height))
        assertEquals(mapOf("grayscale" to 1), analyzer.lastStats.rejected)
    }
}
