package io.github.takahirom.cochange

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private val repo = File.createTempFile("cochange-store", "").apply { delete(); mkdirs() }
    private val base = File.createTempFile("cochange-cache", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
        base.deleteRecursively()
    }

    private fun result(n: Int) = AnalysisResult(
        repo = repo.path, branch = "HEAD", analyzedCommits = n, logicalChanges = n, findings = emptyList(),
    )

    @Test
    fun `named snapshots are kept side by side, not overwritten`() {
        Store.save(repo, result(200), "long-term", base)
        Store.save(repo, result(10), "recent", base)
        assertEquals(200, Store.load(repo, "long-term", base)!!.analyzedCommits)
        assertEquals(10, Store.load(repo, "recent", base)!!.analyzedCommits)
        assertEquals(setOf("long-term", "recent"), Store.list(repo, base).toSet())
    }

    @Test
    fun `default name round-trips`() {
        Store.save(repo, result(5), baseDir = base)
        assertEquals(5, Store.load(repo, baseDir = base)!!.analyzedCommits)
    }

    @Test
    fun `snapshot records the analysis options for later reuse`() {
        val opts = AnalysisOptions(since = "2 years ago", extraExcludes = listOf("**/*.swift"))
        Store.save(repo, result(5).copy(options = opts), "lt", base)
        assertEquals(opts, Store.load(repo, "lt", base)!!.options)
    }

    @Test
    fun `missing snapshot loads as null`() {
        assertNull(Store.load(repo, "nope", base))
    }

    @Test
    fun `invalid names are rejected`() {
        assertFailsWith<IllegalArgumentException> { Store.validateName("../evil") }
        assertFailsWith<IllegalArgumentException> { Store.validateName("a/b") }
        assertFailsWith<IllegalArgumentException> { Store.validateName("") }
        assertTrue(Store.validateName("recent_2y-1").isNotEmpty())
    }
}

/**
 * A saved snapshot outlives the code that wrote it, and `Model.kt` promises that adding a
 * field does not bump `schemaVersion` — so every additive field needs a default or the
 * next release cannot read yesterday's cache.
 */
class SnapshotForwardCompatibilityTest {
    @Test
    fun `a snapshot written before the newest fields still deserializes`() {
        // A v2 result as an earlier build of this schema version wrote it: no
        // declaredModuleCount, no warnings, no detectorTypes, no tier on the detail blocks.
        val older = """
            {
              "schemaVersion": 2,
              "repo": "/tmp/x",
              "branch": "HEAD",
              "headCommit": "abc",
              "shallow": false,
              "changeUnit": "merge",
              "analyzedCommits": 10,
              "logicalChanges": 10,
              "findings": [
                {
                  "id": "finding-1",
                  "type": "boundary_mismatch",
                  "category": "source",
                  "summary": "a and b",
                  "confidence": 1.0,
                  "impact": "medium",
                  "files": ["a/A.kt", "b/B.kt"],
                  "detail": {
                    "observation": "o",
                    "interpretations": [],
                    "counterSignals": [],
                    "supportingChanges": [{"hashes": ["h1"]}]
                  }
                }
              ],
              "moduleDetection": {
                "methods": ["nearest directory with a build file"],
                "coverage": 1.0,
                "moduleCount": 2,
                "declaredFiles": 4,
                "fallbackFiles": 0,
                "totalFiles": 4,
                "trust": "declared",
                "moduleFindingsEnabled": true,
                "note": "n"
              }
            }
        """.trimIndent()

        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(AnalysisResult.serializer(), older)
        assertEquals(1, parsed.findings.size)
        assertEquals(
            -1, parsed.moduleDetection!!.declaredModuleCount,
            "the field is absent, and -1 says so rather than claiming zero declared modules",
        )
        assertTrue(parsed.warnings.isEmpty())
        assertEquals(DETECTOR_TYPES, parsed.detectorTypes)
        assertEquals(listOf("h1"), parsed.findings.single().detail.supportingChanges.single().hashes)
    }
}
