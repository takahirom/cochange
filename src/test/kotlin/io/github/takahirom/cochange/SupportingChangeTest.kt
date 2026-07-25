package io.github.takahirom.cochange

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A change unit is not always one commit. Under `author-window`, one unit is every
 * commit the same author made inside the window — and the co-change evidence was
 * counted from the unit. Storing only each unit's first commit made the most common
 * real shape ("I committed the interface, then the implementation ten minutes
 * later") look like a change to one side only, which is the opposite of what the
 * evidence says.
 */
class SupportingChangeTest {
    private var t = 0L
    private fun commit(vararg files: String): Commit {
        t += 60
        return Commit("h$t", "dev", t, "m", files.toList())
    }

    @Test
    fun `a multi-commit unit contributes all of its commits`() {
        val head = setOf("app/A.kt", "core/B.kt")
        // One unit, two commits: A.kt first, B.kt second.
        val unit = LogicalChange(listOf(commit("app/A.kt"), commit("core/B.kt")))
        val context = AnalysisContext(List(5) { unit }, Boundaries(head), head)

        val samples = context.sampleChanges(setOf("app/A.kt", "core/B.kt"))
        assertTrue(samples.isNotEmpty(), "the pair co-changes within the unit")
        assertEquals(
            2, samples.first().hashes.size,
            "the unit's second commit is where the other side of the pair changed",
        )
    }
}

/** The same thing through the CLI, against a real repository. */
class InspectSupportingChangeTest {
    private val repo = File.createTempFile("cochange-inspect", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    /** git with both dates pinned, so author-window grouping is deterministic. */
    private fun git(at: String?, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repo).apply {
            if (at != null) environment().apply { put("GIT_AUTHOR_DATE", at); put("GIT_COMMITTER_DATE", at) }
        }.redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    private fun commit(at: String, message: String, vararg files: Pair<String, String>) {
        for ((path, content) in files) File(repo, path).apply { parentFile?.mkdirs() }.writeText(content)
        git(at, "add", "-A")
        git(at, "-c", "commit.gpgsign=false", "commit", "-q", "-m", message)
    }

    /** Day [day] of the analysis window, at [minute] past midnight. */
    private fun at(day: Int, minute: Int): String = java.time.Instant.now()
        .minus(java.time.Duration.ofDays(200L - day))
        .plus(java.time.Duration.ofMinutes(minute.toLong()))
        .toString()

    @Test
    fun `inspect shows both sides of a pair that changed in separate commits of one unit`() {
        git(null, "init", "-q", "-b", "main")
        git(null, "config", "user.email", "dev@example.com")
        git(null, "config", "user.name", "Dev")
        // Two declared Gradle modules, so a boundary_mismatch is eligible.
        commit(
            at(0, 0),
            "Set up modules",
            "app/build.gradle.kts" to "// app\n",
            "core/build.gradle.kts" to "// core\n",
            "app/Checkout.kt" to "0\n",
            "core/Pricing.kt" to "0\n",
        )
        // Ten author-window units, one per day, each splitting the pair across two
        // commits ten minutes apart — the shape the old code reported as one-sided.
        repeat(10) { i ->
            val day = 10 + i * 5
            commit(at(day, 0), "Checkout $i", "app/Checkout.kt" to "app-$i\n")
            commit(at(day, 10), "Pricing $i", "core/Pricing.kt" to "core-$i\n")
        }

        val analyze = Analyze().test(listOf(repo.path, "--change-unit", "author-window", "--min-support", "5"))
        assertEquals(0, analyze.statusCode, analyze.output)

        val findings = Findings().test(listOf(repo.path, "--json"))
        assertEquals(0, findings.statusCode, findings.output)
        val id = Regex("\"id\"\\s*:\\s*\"(finding-\\d+)\"").find(findings.stdout)?.groupValues?.get(1)
        assertTrue(id != null, "expected a boundary_mismatch finding: ${findings.stdout.take(400)}")

        val inspect = Inspect().test(listOf(id!!, repo.path))
        assertEquals(0, inspect.statusCode, inspect.output)
        val report = json.decodeFromString(InspectReport.serializer(), inspect.stdout)

        assertTrue(report.supportingChanges.isNotEmpty(), "the finding must carry its supporting units")
        // Most units here are two commits ten minutes apart. Every one of them must
        // report both commits and both sides; the old code reported the first commit
        // only, which read as "just Checkout.kt changed".
        val split = report.supportingChanges.filter { it.hashes.size >= 2 }
        assertTrue(
            split.isNotEmpty(),
            "the two-commit units are the whole point: ${report.supportingChanges.map { it.hashes.size }}",
        )
        for (unit in split) {
            assertEquals(unit.hashes.size, unit.commits.size, "every hash in the unit must resolve")
            assertEquals(
                listOf("app/Checkout.kt", "core/Pricing.kt"), unit.filesTouched,
                "both sides moved inside this unit, and the report has to say so",
            )
        }
    }

    private companion object {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
