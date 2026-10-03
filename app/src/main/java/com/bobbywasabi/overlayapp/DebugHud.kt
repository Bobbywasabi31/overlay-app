package com.bobbywasabi.overlayapp

/** Pure formatting for the debug HUD (items 21, 85); no Android dependencies. */
object DebugHud {
    fun format(stats: ScreenAnalyzer.Stats, budgetMs: Long = ScreenAnalyzer.ANALYSIS_BUDGET_MS): String {
        val sb = StringBuilder()
        sb.append("analysis ").append(stats.analysisMs).append("ms")
        if (stats.analysisMs > budgetMs) sb.append(" OVER BUDGET")
        sb.append('\n')
        sb.append("candidates ").append(stats.candidates).append('\n')
        if (stats.rejected.isEmpty()) {
            sb.append("rejected none")
        } else {
            sb.append("rejected ")
            sb.append(stats.rejected.entries.sortedByDescending { it.value }
                .joinToString(" ") { "${it.key}:${it.value}" })
        }
        return sb.toString()
    }

    fun isOverBudget(stats: ScreenAnalyzer.Stats, budgetMs: Long = ScreenAnalyzer.ANALYSIS_BUDGET_MS): Boolean =
        stats.analysisMs > budgetMs
}
