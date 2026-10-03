package com.bobbywasabi.overlayapp

import org.junit.Assert.*
import org.junit.Test

class DebugHudTest {
    @Test fun formatsBasicStats() {
        val text = DebugHud.format(ScreenAnalyzer.Stats(12, 3, mapOf("aspect" to 2, "fill" to 1)))
        assertTrue(text.contains("analysis 12ms"))
        assertTrue(text.contains("candidates 3"))
        assertTrue(text.contains("aspect:2"))
        assertTrue(text.contains("fill:1"))
        assertFalse(text.contains("OVER BUDGET"))
    }

    @Test fun flagsOverBudget() {
        val stats = ScreenAnalyzer.Stats(ScreenAnalyzer.ANALYSIS_BUDGET_MS + 1, 0, emptyMap())
        assertTrue(DebugHud.isOverBudget(stats))
        assertTrue(DebugHud.format(stats).contains("OVER BUDGET"))
    }

    @Test fun emptyRejectionsRenderCleanly() {
        val text = DebugHud.format(ScreenAnalyzer.Stats(5, 1, emptyMap()))
        assertTrue(text.contains("rejected none"))
    }
}
