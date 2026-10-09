package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Wall-clock benchmark for ScreenAnalyzer.analyze(), quarantined out of the gating
 * unit-test suite (task 21): a hard mean-time gate flaked twice on shared CI runners
 * (failed, then green on rerun with no code change), crying red without a real
 * regression. This class runs only in the separate `benchmark` Gradle task, which the
 * workflow executes with continue-on-error, so a noisy runner can never fail the build.
 *
 * The gate uses the median over many runs, which resists one-off stalls (GC pauses,
 * noisy neighbors); the mean and p95 are still printed for trend-watching.
 */
class AnalyzerTimingBenchmarkTest {
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
        repeat(20) { analyzer.analyze(pixels, width, height) } // warm up: JIT, caches, GC settle
        val runs = 30
        val samples = DoubleArray(runs)
        repeat(runs) { r ->
            val start = System.nanoTime()
            analyzer.analyze(pixels, width, height)
            samples[r] = (System.nanoTime() - start) / 1_000_000.0
        }
        samples.sort()
        val medianMs = samples[runs / 2]
        val p95Ms = samples[(runs * 0.95).toInt().coerceAtMost(runs - 1)]
        val meanMs = samples.average()
        println(
            "analyze() median ${"%.2f".format(medianMs)}ms, " +
                "p95 ${"%.2f".format(p95Ms)}ms, mean ${"%.2f".format(meanMs)}ms " +
                "over $runs runs on ${width}x$height (budget ${ScreenAnalyzer.ANALYSIS_BUDGET_MS}ms)"
        )
        assertTrue(
            "analyze() median ${medianMs}ms exceeds budget",
            medianMs < ScreenAnalyzer.ANALYSIS_BUDGET_MS
        )
    }
}
