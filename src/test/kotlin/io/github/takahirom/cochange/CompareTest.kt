package io.github.takahirom.cochange

import kotlin.test.Test
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

        val moves = Compare.of(baseline, recent, minCount = 3).associateBy { it.file }
        // A cooled: high baseline share, zero recent.
        assertTrue(moves.getValue("A.kt").delta < 0)
        // C heated up: zero baseline, high recent.
        assertTrue(moves.getValue("C.kt").delta > 0)
    }

    @Test
    fun `files below min-count in both windows are dropped`() {
        val head = setOf("A.kt", "B.kt")
        val ctx = AnalysisContext(List(2) { LogicalChange(listOf(commit("A.kt", "B.kt"))) }, Boundaries(head), head)
        assertTrue(Compare.of(ctx, ctx, minCount = 3).isEmpty())
    }
}
