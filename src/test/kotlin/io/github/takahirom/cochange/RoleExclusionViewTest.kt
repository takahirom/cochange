package io.github.takahirom.cochange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `--exclude-role` is a view filter. It must never delete raw evidence, because
 * a number that quietly rests on a smaller history is worse than a noisy one:
 * `P(A|B)` computed with tests removed is not the same statistic, and nothing in
 * the output would say so.
 */
class RoleExclusionViewTest {
    private var t = 0L
    private fun commit(vararg files: String): Commit {
        t += 3600 * 24
        return Commit("h$t", "dev", t, "m", files.toList())
    }

    private val head = setOf(
        "app/build.gradle.kts", "app/Checkout.kt", "app/CheckoutTest.kt",
        "core/build.gradle.kts", "core/Pricing.kt",
    )

    private val changes = List(10) {
        LogicalChange(listOf(commit("app/Checkout.kt", "app/CheckoutTest.kt", "core/Pricing.kt")))
    }

    private fun context(vararg roles: String) =
        AnalysisContext(changes, Boundaries(head), head, excludedRoles = roles.toSet())

    @Test
    fun `hiding tests leaves every remaining pair count untouched`() {
        val shown = context()
        val hidden = context(FileRole.TEST)
        fun counts(c: AnalysisContext) = c.pairs()
            .filter { it.a != "app/CheckoutTest.kt" && it.b != "app/CheckoutTest.kt" }
            .associate { setOf(it.a, it.b) to Triple(it.together, it.countA, it.countB) }
        assertEquals(counts(shown), counts(hidden), "the evidence layer must be identical")
        assertEquals(shown.changeCount("app/Checkout.kt"), hidden.changeCount("app/Checkout.kt"))
        // The hidden file is still counted; it is simply not shown.
        assertEquals(10, hidden.changeCount("app/CheckoutTest.kt"))
    }

    @Test
    fun `hidden files are kept out of findings`() {
        val findings = Analyzer(minSupport = 5, minConfidence = 0.6).run(context(FileRole.TEST)).findings
        assertTrue(findings.isNotEmpty(), "the Checkout/Pricing coupling should still be found")
        assertTrue(
            findings.none { "CheckoutTest" in it.summary || "CheckoutTest" in it.detail.observation },
            "a hidden role must not appear in the findings: ${findings.map { it.summary }}",
        )
    }

    @Test
    fun `what was hidden is reported, not silently dropped`() {
        val run = Analyzer(minSupport = 5, minConfidence = 0.6).run(context(FileRole.TEST))
        assertEquals(mapOf(FileRole.TEST to 1), run.hiddenByRole)
    }

    /**
     * The family projection rebuilds the context, and it used to rebuild it without
     * the excluded roles — so `clusters --exclude-role` showed the files it had just
     * reported as hidden.
     */
    @Test
    fun `the family projection keeps hiding what the run hid`() {
        val hidden = context(FileRole.TEST)
        val projected = Families.project(
            hidden,
            Boundaries(head),
            listOf(Families.Family("app/Checkout.kt", listOf("app/Checkout.kt", "core/Pricing.kt"))),
        )
        assertEquals(hidden.excludedRoles, projected.excludedRoles)
        assertTrue(!projected.isVisible("app/CheckoutTest.kt"), "a hidden test must stay hidden after projection")
    }

    /** compare filtered on headFiles alone, so `--exclude-role test` always counted tests. */
    @Test
    fun `compare hides excluded roles from its participation rates`() {
        val shown = Compare.of(context(), context(), minCount = 1).moves.map { it.file }
        val hiddenRun = Compare.of(context(FileRole.TEST), context(FileRole.TEST), minCount = 1).moves.map { it.file }
        assertTrue("app/CheckoutTest.kt" in shown, "sanity: the test file participates")
        assertTrue("app/CheckoutTest.kt" !in hiddenRun, "an excluded role must not be listed: $hiddenRun")
        assertTrue("app/Checkout.kt" in hiddenRun, "the visible files are still compared")
    }

    @Test
    fun `with no roles excluded nothing is hidden and nothing is reported`() {
        val run = Analyzer(minSupport = 5, minConfidence = 0.6).run(context())
        assertTrue(run.hiddenByRole.isEmpty())
        assertTrue(run.findings.any { "CheckoutTest" in it.summary || "CheckoutTest" in it.detail.observation })
    }
}

/**
 * The promise is that `--exclude-role` never shifts a number. It was being broken by
 * aggregate scores whose numerator honoured the filter while their denominator did not:
 * hiding a hub raised `hubFreeRate`, and hiding a hotspot raised `boundaryIntegrity` from
 * 0 to 1. Scores are now computed over the whole population; only listings are filtered.
 */
class AggregateScoresIgnoreRoleFilterTest {
    private var t = 0L
    private fun commit(vararg files: String): Commit {
        t += 3600 * 24
        return Commit("h$t", "dev", t, "m", files.toList())
    }

    private val head = setOf(
        "a/build.gradle.kts", "a/FooTest.kt", "a/Foo.kt",
        "b/build.gradle.kts", "b/BarTest.kt", "b/Bar.kt",
    )

    private fun context(vararg roles: String) = AnalysisContext(
        List(8) { LogicalChange(listOf(commit("a/FooTest.kt", "b/BarTest.kt"))) } +
            List(8) { LogicalChange(listOf(commit("a/Foo.kt", "b/Bar.kt"))) },
        Boundaries(head), head, excludedRoles = roles.toSet(),
    )

    @Test
    fun `hiding tests does not move a single metric`() {
        val shown = Metrics.compute(context())
        val hidden = Metrics.compute(context(FileRole.TEST))
        assertEquals(shown.boundaryIntegrity, hidden.boundaryIntegrity, "boundaryIntegrity moved")
        assertEquals(shown.moduleLocality, hidden.moduleLocality, "moduleLocality moved")
        assertEquals(shown.hubFreeRate, hidden.hubFreeRate, "hubFreeRate moved")
        assertEquals(shown.multiFileUnits, hidden.multiFileUnits)
        assertEquals(shown.declaredMultiFileUnits, hidden.declaredMultiFileUnits)
        assertEquals(shown.crossModuleUnits, hidden.crossModuleUnits)
        assertEquals(shown.boundaryHotspots, hidden.boundaryHotspots, "a hidden hotspot is still a hotspot")
    }

    @Test
    fun `hiding tests does not move a compare rate, only the listing`() {
        val shown = Compare.of(context(), context(), minCount = 1)
        val hidden = Compare.of(context(FileRole.TEST), context(FileRole.TEST), minCount = 1)
        assertEquals(shown.baselineMultiFile, hidden.baselineMultiFile, "the denominator must not move")
        val visibleRate = { c: Compare.Comparison -> c.moves.single { it.file == "a/Foo.kt" }.baselineRate }
        assertEquals(visibleRate(shown), visibleRate(hidden), "a visible file's rate must not move")
        assertTrue(hidden.moves.none { it.file == "a/FooTest.kt" }, "the hidden file is not listed")
        assertTrue(shown.moves.any { it.file == "a/FooTest.kt" })
    }
}
