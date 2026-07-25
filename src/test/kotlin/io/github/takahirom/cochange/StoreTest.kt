package io.github.takahirom.cochange

import com.github.ajalt.clikt.testing.test
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

/**
 * `snapshot records the analysis options for later reuse` only checks the JSON round-trip —
 * it would still pass if `metrics`, `pairs` or `clusters` never called `resolveOptions()`.
 * This drives the commands and asserts the snapshot's conditions are the ones they used.
 */
class AnalysisReuseCommandTest {
    private val repo = File.createTempFile("cochange-reuse", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    private fun git(vararg args: String) = GitLog.runGit(repo, args.toList())

    /**
     * A file that exists only in the OLD half of the history, so a command honouring the
     * snapshot's narrow window cannot see it and one falling back to all-history can.
     */
    private fun buildRepo() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "dev@example.com")
        git("config", "user.name", "Dev")
        fun write(path: String, content: String) =
            File(repo, path).apply { parentFile?.mkdirs() }.writeText(content)

        fun commit(at: String, message: String) {
            val p = ProcessBuilder(listOf("git", "-c", "commit.gpgsign=false", "commit", "-q", "-m", message))
                .directory(repo).apply {
                    environment()["GIT_AUTHOR_DATE"] = at
                    environment()["GIT_COMMITTER_DATE"] = at
                }.redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "commit failed: $out" }
        }
        fun at(days: Long) = java.time.Instant.now().minus(java.time.Duration.ofDays(days)).toString()

        write("build.gradle.kts", "// root")
        // Old era, 300 days back: ANCIENT.kt moves with a partner.
        for (i in 1..8) {
            write("app/ANCIENT.kt", "a$i\n")
            write("app/Partner.kt", "p$i\n")
            git("add", "-A")
            commit(at(300), "old $i")
        }
        // Recent era: a different pair entirely.
        for (i in 1..8) {
            write("app/Recent.kt", "r$i\n")
            write("app/RecentPartner.kt", "q$i\n")
            git("add", "-A")
            commit(at(3), "recent $i")
        }
    }

    @Test
    fun `metrics, pairs and clusters use the snapshot's window, not the current defaults`() {
        buildRepo()
        // Save a snapshot restricted to the recent era only.
        val save = Analyze().test(
            listOf(repo.path, "--since", "30 days ago", "--change-unit", "commit", "--save", "recent-only"),
        )
        assertTrue(save.statusCode == 0, save.output)

        // With --analysis, the old era must be invisible...
        for ((name, out) in listOf(
            "pairs" to Pairs().test(listOf(repo.path, "--analysis", "recent-only", "--min-support", "2")).output,
            "clusters" to ClustersCommand().test(listOf(repo.path, "--analysis", "recent-only", "--min-support", "2")).output,
        )) {
            assertTrue(
                "ANCIENT" !in out,
                "$name ignored the snapshot's window and fell back to all-history:\n$out",
            )
            assertTrue("Recent" in out, "$name should still see the snapshot's own era:\n$out")
        }
        // ...and metrics must report the snapshot's unit count, not the whole history's.
        val scoped = MetricsCommand().test(listOf(repo.path, "--analysis", "recent-only", "--json"))
        val whole = MetricsCommand().test(listOf(repo.path, "--change-unit", "commit", "--json"))
        val units = { s: String -> Regex("\"multiFileUnits\"\\s*:\\s*(\\d+)").find(s)!!.groupValues[1].toInt() }
        assertTrue(
            units(scoped.stdout) < units(whole.stdout),
            "metrics saw ${units(scoped.stdout)} units with --analysis and ${units(whole.stdout)} without; " +
                "the snapshot covers only half the history, so it must be fewer",
        )

        // Sanity: without --analysis the old era IS visible, so the assertions above bite.
        val unscoped = Pairs().test(listOf(repo.path, "--change-unit", "commit", "--min-support", "2")).output
        assertTrue("ANCIENT" in unscoped, "the fixture must actually contain the old era:\n$unscoped")
    }
}
