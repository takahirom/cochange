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

    @Test
    fun `with no roles excluded nothing is hidden and nothing is reported`() {
        val run = Analyzer(minSupport = 5, minConfidence = 0.6).run(context())
        assertTrue(run.hiddenByRole.isEmpty())
        assertTrue(run.findings.any { "CheckoutTest" in it.summary || "CheckoutTest" in it.detail.observation })
    }
}
