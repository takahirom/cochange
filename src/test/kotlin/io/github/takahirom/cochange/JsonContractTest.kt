package io.github.takahirom.cochange

import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JSON is a contract for AI agents, which cannot ask a follow-up question
 * about what a field means. So the fields that carry the contract itself —
 * version and trust tier — must be present even when they hold their default
 * value, and the ranking scores must be readable rather than only implied by
 * list order.
 */
class JsonContractTest {
    private val reader = Json { ignoreUnknownKeys = true }

    private var t = 0L
    private fun commit(vararg files: String): Commit {
        t += 3600 * 24
        return Commit("h$t", "dev", t, "m", files.toList())
    }

    private val head = setOf(
        "app/build.gradle.kts", "app/Checkout.kt",
        "core/build.gradle.kts", "core/Pricing.kt",
    )

    private fun findings() = Analyzer(minSupport = 5, minConfidence = 0.6).analyze(
        List(10) { LogicalChange(listOf(commit("app/Checkout.kt", "core/Pricing.kt"))) },
        Boundaries(head), head,
    )

    @Test
    fun `every finding states its tier and its evidence, even at default values`() {
        val encoded = Store.encode(findings().first())
        val obj = reader.parseToJsonElement(encoded).jsonObject
        assertEquals(EvidenceTier.INTERPRETATION, obj["tier"]!!.jsonPrimitive.content)
        val evidence = obj["evidence"]!!.jsonObject
        assertEquals(EvidenceTier.EVIDENCE, evidence["tier"]!!.jsonPrimitive.content)
        for (key in listOf("support", "sampleSize", "sampleMeaning", "ratio", "evidenceStrength")) {
            assertTrue(key in evidence, "$key must be in the JSON, not only in the ranking: $encoded")
        }
        // The ranking is a separate block: it is interpretation, and labelling
        // `interest` as evidence invited exactly the wrong reading.
        val ranking = obj["ranking"]!!.jsonObject
        assertEquals(EvidenceTier.INTERPRETATION, ranking["tier"]!!.jsonPrimitive.content)
        for (key in listOf("interest", "nameSimilarity")) {
            assertTrue(key in ranking, "$key must be in the JSON: $encoded")
        }
        assertTrue("interest" !in evidence, "the ranking heuristic must not sit in the evidence block")
    }

    @Test
    fun `the result carries a schema version and the gate decision`() {
        val result = AnalysisResult(
            schemaVersion = SCHEMA_VERSION,
            repo = "/tmp/x", branch = "HEAD", analyzedCommits = 1, logicalChanges = 1,
            findings = findings(),
            moduleDetection = ModuleGate.report(
                AnalysisContext(emptyList(), Boundaries(head), head).moduleDetection,
            ),
        )
        val obj = reader.parseToJsonElement(Store.encode(result)).jsonObject
        assertEquals(SCHEMA_VERSION, obj["schemaVersion"]!!.jsonPrimitive.content.toInt())
        val modules = obj["moduleDetection"]!!.jsonObject
        assertTrue("moduleFindingsEnabled" in modules, "the gate decision must be machine-readable")
        assertTrue("coverage" in modules && "methods" in modules, "provenance must be machine-readable")
        assertEquals(EvidenceTier.DERIVED, modules["tier"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a snapshot written by an older schema is refused, not reinterpreted`() {
        // Field meanings changed between v1 and v2 (confidence scales, SplitGroup.support),
        // so silently reading a v1 cache would report wrong numbers under right names.
        val old = reader.decodeFromString(
            AnalysisResult.serializer(),
            """{"repo":"/tmp/x","branch":"HEAD","analyzedCommits":1,"logicalChanges":1,"findings":[]}""",
        )
        assertEquals(1, old.schemaVersion, "an unversioned snapshot must read as v1, not as current")
        assertTrue(SCHEMA_VERSION > 1)
    }
}

/**
 * "Every `--json` output carries the envelope" was a README claim, not a fact:
 * `metrics --json` and `compare --json` had ad-hoc headers with no schema version,
 * no pinned options, and no module provenance — so a consumer could not see that a
 * locality score rested on guessed folders.
 */
class EnvelopeContractTest {
    private val repo = File.createTempFile("cochange-envelope", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    private fun git(vararg args: String) = GitLog.runGit(repo, args.toList())

    private fun initRepo() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "T")
        for (i in 1..6) {
            File(repo, "app/build.gradle.kts").apply { parentFile.mkdirs() }.writeText("// app")
            File(repo, "core/build.gradle.kts").apply { parentFile.mkdirs() }.writeText("// core")
            File(repo, "app/A.kt").writeText("a$i\n")
            File(repo, "core/B.kt").writeText("b$i\n")
            git("add", "-A")
            git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "change $i")
        }
    }

    private fun envelopeOf(stdout: String): JsonObject =
        reader.parseToJsonElement(stdout).jsonObject["context"]!!.jsonObject

    @Test
    fun `pairs, clusters, metrics and compare all carry the same envelope`() {
        initRepo()
        val outputs = mapOf(
            "pairs" to Pairs().test(listOf(repo.path, "--min-support", "1", "--json")),
            "clusters" to ClustersCommand().test(listOf(repo.path, "--min-support", "1", "--json")),
            "metrics" to MetricsCommand().test(listOf(repo.path, "--json")),
            "compare" to CompareCommand().test(
                listOf(repo.path, "--baseline", "365 days ago", "--recent", "30 days ago", "--min-count", "1", "--json"),
            ),
        )
        for ((name, result) in outputs) {
            assertEquals(0, result.statusCode, "$name failed: ${result.output}")
            val context = envelopeOf(result.stdout)
            for (key in listOf("schemaVersion", "repo", "headCommit", "requestedOptions", "options", "changeUnit", "moduleDetection", "warnings", "tiers")) {
                assertTrue(key in context, "$name's envelope is missing $key: ${result.stdout.take(300)}")
            }
        }
    }

    @Test
    fun `a pair separates its counted numbers from its inferred structure`() {
        initRepo()
        val result = Pairs().test(listOf(repo.path, "--min-support", "1", "--json"))
        assertEquals(0, result.statusCode, result.output)
        val pair = reader.parseToJsonElement(result.stdout).jsonObject["pairs"]!!.jsonArray.first().jsonObject
        assertEquals(EvidenceTier.EVIDENCE, pair["evidence"]!!.jsonObject["tier"]!!.jsonPrimitive.content)
        val derived = pair["derived"]!!.jsonObject
        assertEquals(EvidenceTier.DERIVED, derived["tier"]!!.jsonPrimitive.content)
        // Provenance per endpoint: a module name alone cannot say whether it is real.
        assertTrue("moduleADeclared" in derived && "moduleBDeclared" in derived, "was $derived")
    }

    private companion object {
        val reader = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
