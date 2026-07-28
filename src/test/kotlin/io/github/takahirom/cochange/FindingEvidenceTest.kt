package io.github.takahirom.cochange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A consumer that can only see `confidence` and list order cannot tell a 5/5
 * fluke from a 20/25 coupling — both report 1.0 and 0.8, in that order. These
 * tests pin the numbers the ranker itself uses into the output.
 */
class FindingEvidenceTest {
    private var t = 0L
    private fun commit(vararg files: String): Commit {
        t += 3600 * 24
        return Commit("h$t", "dev", t, "m", files.toList())
    }

    private val head = setOf(
        "app/build.gradle.kts", "app/Checkout.kt", "app/Tiny.kt",
        "core/build.gradle.kts", "core/Pricing.kt", "core/Rare.kt",
    )

    private fun analyze() = Analyzer(minSupport = 5, minConfidence = 0.6).analyze(
        buildList {
            repeat(20) { add(LogicalChange(listOf(commit("app/Checkout.kt", "core/Pricing.kt")))) }
            repeat(5) { add(LogicalChange(listOf(commit("app/Checkout.kt")))) }
            repeat(5) { add(LogicalChange(listOf(commit("core/Pricing.kt")))) }
            // A 5-of-5 pair: perfect ratio, negligible evidence.
            repeat(5) { add(LogicalChange(listOf(commit("app/Tiny.kt", "core/Rare.kt")))) }
        },
        Boundaries(head), head,
    )

    @Test
    fun `a perfect ratio on a tiny sample is exposed as weak evidence`() {
        val fluke = analyze().single { "Tiny.kt" in it.summary }
        val solid = analyze().single { "Checkout.kt" in it.summary && it.type == "boundary_mismatch" }
        assertEquals(1.0, fluke.confidence, "the raw ratio really is 5/5")
        assertTrue(
            solid.evidence!!.evidenceStrength!! > fluke.evidence!!.evidenceStrength!!,
            "the sample-corrected strength must say what the ratio can't",
        )
    }

    @Test
    fun `the ranking score is in the output, not only in the list order`() {
        val findings = analyze().filter { it.type == "boundary_mismatch" }
        assertTrue(findings.all { it.ranking != null }, "boundary_mismatch publishes its ranking")
        assertTrue(
            findings.all { it.ranking!!.tier == EvidenceTier.INTERPRETATION },
            "the ranking is a judgement call and must not be labelled evidence",
        )
        assertEquals(
            findings.sortedByDescending { it.ranking!!.interest }.map { it.id },
            findings.map { it.id },
            "list order must be reproducible from the published score",
        )
    }

    @Test
    fun `evidence names its own denominator`() {
        val f = analyze().single { "Checkout.kt" in it.summary && it.type == "boundary_mismatch" }
        val e = f.evidence!!
        assertEquals(20, e.support)
        assertEquals(25, e.sampleSize, "25 change units touched the rarer file")
        assertEquals(0.8, e.ratio)
        assertTrue(e.sampleMeaning.isNotEmpty(), "the denominator differs per type, so it must be stated")
        assertEquals(EvidenceTier.EVIDENCE, e.tier)
    }

    @Test
    fun `a predictable name pairing is reported as such instead of only being demoted`() {
        val f = analyze().single { "Checkout.kt" in it.summary && it.type == "boundary_mismatch" }
        assertEquals(0.0, f.ranking!!.nameSimilarity, "Checkout.kt and Pricing.kt share no token")
    }

    @Test
    fun `unstable_hub confidence is a share of real changes, not count over fifty`() {
        val mods = listOf("a", "b", "c", "d", "e")
        val head = buildSet {
            add("core/build.gradle.kts"); add("core/Wiring.kt")
            for (m in mods) { add("$m/build.gradle.kts"); add("$m/${m.uppercase()}.kt") }
        }
        val changes = buildList {
            for (m in mods) repeat(10) { add(LogicalChange(listOf(commit("core/Wiring.kt", "$m/${m.uppercase()}.kt")))) }
            repeat(50) { add(LogicalChange(listOf(commit("a/A.kt", "b/B.kt")))) }
        }
        val hub = Analyzer(minSupport = 1000, minConfidence = 0.99)
            .analyze(changes, Boundaries(head), head)
            .single { it.type == "unstable_hub" }
        val e = hub.evidence!!
        assertEquals(50, e.support)
        assertEquals(100, e.sampleSize)
        // count/50 would have reported 1.0 here regardless of repository size.
        assertEquals(0.5, hub.confidence)
    }
}

/**
 * `files` mixes the finding's subject with the context it was found against — for a
 * split candidate, the candidate plus every partner. "The candidate is first" was an
 * unwritten convention, so a consumer had to guess which file to act on.
 */
class FindingSubjectTest {
    private var t = 0L
    private fun commit(vararg files: String): Commit {
        t += 3600 * 24
        return Commit("h$t", "dev", t, "m", files.toList())
    }

    @Test
    fun `a split candidate names its subject apart from its partners`() {
        val head = setOf("app/God.kt", "app/A1.kt", "app/A2.kt", "app/B1.kt", "app/B2.kt")
        val changes = List(8) { LogicalChange(listOf(commit("app/God.kt", "app/A1.kt", "app/A2.kt"))) } +
            List(8) { LogicalChange(listOf(commit("app/God.kt", "app/B1.kt", "app/B2.kt"))) }
        val findings = SplitCandidateDetector(minSupport = 5)
            .detect(AnalysisContext(changes, Boundaries(head), head))
        val split = findings.singleOrNull { it.subjects == listOf("app/God.kt") }
        assertTrue(split != null, "expected one split candidate: ${findings.map { it.summary }}")
        assertTrue(split.files.size > split.subjects.size, "files also carries the partners")
        assertTrue(split.subjects.all { it in split.files }, "subjects must be a subset of files")
    }

    @Test
    fun `a boundary mismatch treats both sides as subjects`() {
        val head = setOf("app/build.gradle.kts", "app/A.kt", "core/build.gradle.kts", "core/B.kt")
        val changes = List(10) { LogicalChange(listOf(commit("app/A.kt", "core/B.kt"))) }
        val finding = Analyzer(minSupport = 5, minConfidence = 0.6)
            .analyze(AnalysisContext(changes, Boundaries(head), head))
            .single { it.type == "boundary_mismatch" }
        assertEquals(setOf("app/A.kt", "core/B.kt"), finding.subjects.toSet())
    }
}
