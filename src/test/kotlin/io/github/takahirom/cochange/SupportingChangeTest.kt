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

        val findings = Findings().test(listOf(repo.path, "--type", "boundary_mismatch", "--json"))
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

/**
 * The log is read with `-M`, so a file renamed after a commit is stored under its
 * current path while the commit still names the old one. `filesTouched` used to be
 * rebuilt from `git show`'s churn, which therefore dropped exactly the files a rename
 * had moved. It is recorded when the analysis runs instead.
 */
class RenamedSupportingChangeTest {
    private val repo = File.createTempFile("cochange-rename", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    private fun git(vararg args: String) = GitLog.runGit(repo, args.toList())

    @Test
    fun `a file renamed later still shows in the units that predate the rename`() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "dev@example.com")
        git("config", "user.name", "Dev")
        File(repo, "app/build.gradle.kts").apply { parentFile.mkdirs() }.writeText("// app")
        File(repo, "core/build.gradle.kts").apply { parentFile.mkdirs() }.writeText("// core")
        // Six units editing old/A.kt together with core/B.kt...
        repeat(6) { i ->
            File(repo, "app/Old.kt").writeText("a$i\n".repeat(i + 1))
            File(repo, "core/B.kt").writeText("b$i\n")
            git("add", "-A")
            git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "change $i")
        }
        // ...then the file is renamed, so the analysis knows it as app/New.kt.
        git("mv", "app/Old.kt", "app/New.kt")
        git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "Rename it")

        assertEquals(0, Analyze().test(listOf(repo.path, "--change-unit", "commit", "--save", "r")).statusCode)
        val findings = Findings().test(listOf(repo.path, "--analysis", "r", "--json"))
        val id = Regex("\"id\"\\s*:\\s*\"(finding-\\d+)\"").find(findings.stdout)?.groupValues?.get(1)
        assertTrue(id != null, "expected a finding about the renamed pair: ${findings.stdout.take(400)}")

        val inspect = Inspect().test(listOf(id!!, repo.path, "--analysis", "r"))
        assertEquals(0, inspect.statusCode, inspect.output)
        val report = json.decodeFromString(InspectReport.serializer(), inspect.stdout)
        assertTrue(report.finding.subjects.any { it.endsWith("New.kt") }, "was ${report.finding.subjects}")
        // Every supporting unit predates the rename, and every one must still name both sides.
        for (unit in report.supportingChanges) {
            assertEquals(
                report.finding.subjects.sorted(), unit.filesTouched,
                "the pre-rename commit names app/Old.kt; the finding is about app/New.kt",
            )
        }
    }

    private companion object {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
