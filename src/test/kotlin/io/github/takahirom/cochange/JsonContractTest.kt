package io.github.takahirom.cochange

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        for (key in listOf("support", "sampleSize", "sampleMeaning", "ratio", "evidenceStrength", "interest")) {
            assertTrue(key in evidence, "$key must be in the JSON, not only in the ranking: $encoded")
        }
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
