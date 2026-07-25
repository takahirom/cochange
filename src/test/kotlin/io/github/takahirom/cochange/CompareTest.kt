package io.github.takahirom.cochange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompareTest {
    private var t = 0L
    private fun commit(vararg files: String): Commit { t += 3600 * 24; return Commit("h$t", "a", t, "m", files.toList()) }

    @Test
    fun `flags files whose participation share shifts between windows`() {
        val head = setOf("A.kt", "B.kt", "C.kt", "D.kt")
        // Baseline: A is central. Recent: C is central, A is quiet.
        val baseline = AnalysisContext(List(10) { LogicalChange(listOf(commit("A.kt", "B.kt"))) }, Boundaries(head), head)
        val recent = AnalysisContext(List(10) { LogicalChange(listOf(commit("C.kt", "D.kt"))) }, Boundaries(head), head)

        val moves = Compare.of(baseline, recent, minCount = 3).moves.associateBy { it.file }
        // A cooled: high baseline share, zero recent.
        assertTrue(moves.getValue("A.kt").delta < 0)
        // C heated up: zero baseline, high recent.
        assertTrue(moves.getValue("C.kt").delta > 0)
    }

    @Test
    fun `summary counts movers in each direction`() {
        val head = setOf("A.kt", "B.kt", "C.kt", "D.kt")
        val baseline = AnalysisContext(List(10) { LogicalChange(listOf(commit("A.kt", "B.kt"))) }, Boundaries(head), head)
        val recent = AnalysisContext(List(10) { LogicalChange(listOf(commit("C.kt", "D.kt"))) }, Boundaries(head), head)
        val summary = Compare.summarize(Compare.of(baseline, recent, minCount = 3).moves)
        assertTrue(summary.heating >= 1)
        assertTrue(summary.cooling >= 1)
        assertTrue(summary.totalAbsShift > 0.0)
    }

    @Test
    fun `the reported denominator is the one the rates were divided by`() {
        val head = setOf("A.kt", "B.kt", "Solo.kt")
        // 10 multi-file units plus 5 single-file ones: only the former can be a rate denominator.
        val changes = List(10) { LogicalChange(listOf(commit("A.kt", "B.kt"))) } +
            List(5) { LogicalChange(listOf(commit("Solo.kt"))) }
        val ctx = AnalysisContext(changes, Boundaries(head), head)
        val comparison = Compare.of(ctx, ctx, minCount = 3)
        assertEquals(10, comparison.baselineMultiFile, "15 logical changes, but only 10 are multi-file")
        // A file in all 10 multi-file units has rate 1.0 — which only holds against 10, not 15.
        assertEquals(1.0, comparison.moves.single { it.file == "A.kt" }.baselineRate)
    }

    @Test
    fun `mean shift is per listed file so it does not grow with min-count`() {
        val head = setOf("A.kt", "B.kt", "C.kt", "D.kt")
        val baseline = AnalysisContext(List(10) { LogicalChange(listOf(commit("A.kt", "B.kt"))) }, Boundaries(head), head)
        val recent = AnalysisContext(List(10) { LogicalChange(listOf(commit("C.kt", "D.kt"))) }, Boundaries(head), head)
        val summary = Compare.summarize(Compare.of(baseline, recent, minCount = 3).moves)
        assertEquals(4, summary.heating + summary.cooling)
        assertEquals(summary.totalAbsShift / 4, summary.meanAbsShift)
    }

    @Test
    fun `nesting is reported, because overlap is unconditional`() {
        val wide = Compare.Window("180d", "2025-01-01T00:00:00Z", 100, 80)
        val narrow = Compare.Window("30d", "2025-06-01T00:00:00Z", 20, 15)
        assertTrue(
            Compare.recentIsInsideBaseline(wide, narrow),
            "the intended usage: the samples are not independent, and that must be stated",
        )
        // Swapping them does not make the windows independent — both still end at HEAD.
        // The old field was named `windowsOverlap` and returned false here, which was
        // simply untrue; this reports the swap instead.
        assertTrue(!Compare.recentIsInsideBaseline(narrow, wide), "--baseline 30d --recent 180d is reversed")
    }

    @Test
    fun `files below min-count in both windows are dropped`() {
        val head = setOf("A.kt", "B.kt")
        val ctx = AnalysisContext(List(2) { LogicalChange(listOf(commit("A.kt", "B.kt"))) }, Boundaries(head), head)
        assertTrue(Compare.of(ctx, ctx, minCount = 3).moves.isEmpty())
    }
}
