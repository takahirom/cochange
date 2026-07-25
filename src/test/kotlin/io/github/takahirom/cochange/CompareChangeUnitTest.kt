package io.github.takahirom.cochange

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `compare` divides each window's participation by that window's own unit count, so
 * both windows have to be counted in the same unit. `--change-unit auto` resolves per
 * window, so a repository that moved from merge commits to squashes used to have its
 * recent window counted at a finer granularity than its baseline — every rate then
 * shifted because the denominator changed, with nothing in the output saying so.
 */
class CompareChangeUnitTest {
    private val repo = File.createTempFile("cochange-compare-unit", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    /** git with both dates pinned — `--since` filters on the committer date. */
    private fun git(at: String?, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repo).apply {
            if (at != null) environment().apply { put("GIT_AUTHOR_DATE", at); put("GIT_COMMITTER_DATE", at) }
            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
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

    /**
     * An era of PR merges long ago, then an era of plain commits lately: 365d resolves
     * to merge-based, 30d on its own would resolve to author-window.
     */
    private fun buildRepoThatChangedItsMergeHabit() {
        git(null, "init", "-q", "-b", "main")
        git(null, "config", "user.email", "dev@example.com")
        git(null, "config", "user.name", "Dev")

        // Relative to now, so the windows below keep meaning the same thing over time.
        val old = daysAgo(300)
        commit(old, "Initial", "app/Screen.kt" to "start\n", "core/Rules.kt" to "start\n")
        repeat(4) { i ->
            git(old, "checkout", "-q", "-b", "feature-$i")
            commit(old, "Work $i", "app/Screen.kt" to "pr-$i\n", "core/Rules.kt" to "pr-$i\n")
            git(old, "checkout", "-q", "main")
            git(old, "-c", "commit.gpgsign=false", "merge", "-q", "--no-ff", "-m", "Merge PR $i", "feature-$i")
        }

        val recent = daysAgo(3)
        repeat(3) { i ->
            commit(recent, "Squashed change $i", "app/Screen.kt" to "late-$i\n", "core/Rules.kt" to "late-$i\n")
        }
    }

    @Test
    fun `both windows are counted in one unit, and the mismatch is reported`() {
        buildRepoThatChangedItsMergeHabit()

        // The premise: left to itself, each window would pick a different unit.
        val wide = ChangeUnits.resolve("auto", repo, null, "365 days ago").strategy.name
        val narrow = ChangeUnits.resolve("auto", repo, null, "30 days ago").strategy.name
        assertEquals("merge", wide, "the 365d window is mostly PR merges")
        assertEquals("author-window", narrow, "the 30d window has no merges at all")

        val result = CompareCommand().test(
            listOf(repo.path, "--baseline", "365 days ago", "--recent", "30 days ago", "--min-count", "1", "--json")
        )
        assertEquals(0, result.statusCode, result.output)
        val report = Json.decodeFromString(Compare.CompareReport.serializer(), result.stdout)

        assertEquals("merge", report.context.changeUnit, "the baseline's unit must govern both windows")
        assertEquals(
            "author-window", report.recentWindowAloneWouldUse,
            "the recent window's own choice is published so a reader can see the history changed shape",
        )
    }

    @Test
    fun `the human output warns when the recent window is a poor fit for the shared unit`() {
        buildRepoThatChangedItsMergeHabit()
        val result = CompareCommand().test(
            listOf(repo.path, "--baseline", "365 days ago", "--recent", "30 days ago", "--min-count", "1")
        )
        assertEquals(0, result.statusCode, result.output)
        assertTrue(
            result.output.contains("WARNING") && result.output.contains("author-window"),
            "a granularity change between windows moves rates on its own and must be stated: ${result.output}",
        )
        assertTrue(
            result.output.contains("change unit: merge") && result.output.contains("applied to both windows"),
            "the shared unit must be printed: ${result.output}",
        )
    }

    @Test
    fun `an explicit change unit is used for both windows without a warning`() {
        buildRepoThatChangedItsMergeHabit()
        val result = CompareCommand().test(
            listOf(
                repo.path, "--baseline", "365 days ago", "--recent", "30 days ago",
                "--change-unit", "commit", "--min-count", "1",
            )
        )
        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("change unit: commit"), result.output)
        assertTrue(
            !result.output.contains("would be counted as"),
            "nothing was auto-resolved, so there is no mismatch to report: ${result.output}",
        )
    }

    private companion object {
        val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun daysAgo(days: Long): String =
            java.time.Instant.now().minus(java.time.Duration.ofDays(days)).toString()
    }
}

/**
 * `compare` builds two setups, and only the baseline's caveats were reported. A
 * malformed `--recent` resolves to now, so every established file appears to cool to
 * zero — the most alarming output the tool can produce, and it looked clean.
 */
class CompareWarningsTest {
    private val repo = File.createTempFile("cochange-compare-warn", "").apply { delete(); mkdirs() }

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

    @Test
    fun `a malformed recent window is reported, in JSON and in text`() {
        initRepo()
        val args = listOf(repo.path, "--baseline", "365 days ago", "--recent", "las year", "--min-count", "1")

        val text = CompareCommand().test(args)
        assertEquals(0, text.statusCode, text.output)
        assertTrue(
            text.output.contains("WARNING (recent window)") && text.output.contains("resolved to the current instant"),
            "the recent window is what broke, and the output must say which: ${text.output}",
        )

        val json = CompareCommand().test(args + "--json")
        assertEquals(0, json.statusCode, json.output)
        val report = Json.decodeFromString(Compare.CompareReport.serializer(), json.stdout)
        assertTrue(
            report.context.warnings.any { it.code == AnalysisWarning.WINDOW_IS_NOW },
            "warnings were ${report.context.warnings.map { it.code }}",
        )
    }

    private companion object {
        val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
