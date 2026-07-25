package io.github.takahirom.cochange

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PinnedConditionsTest {
    private val repo = File.createTempFile("cochange-pin", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    private fun git(vararg args: String) = GitLog.runGit(repo, args.toList())

    private fun initRepo() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "T")
        File(repo, "build.gradle.kts").writeText("// root")
        git("add", "-A")
        git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "first")
    }

    @Test
    fun `a relative window resolves to an absolute instant`() {
        initRepo()
        val resolved = GitLog.resolveSince(repo, "1 year ago")
        assertNotNull(resolved)
        assertNotEquals("1 year ago", resolved)
        // An ISO-8601 UTC instant, which git accepts back as --since.
        assertTrue(resolved.endsWith("Z") && resolved.contains("T"), "was $resolved")
    }

    @Test
    fun `an unparseable window silently means now, and that is detectable`() {
        initRepo()
        // git does not reject a bad date: it falls back to "now", so `--since "las year"`
        // quietly analyzes nothing. Pinning makes that visible instead of invisible.
        val now = java.time.Instant.now()
        val resolved = GitLog.resolveSince(repo, "las year")
        assertNotNull(resolved)
        assertTrue(Analysis.windowLooksUnparsed(resolved, now), "a window resolving to now means git didn't understand it")
        assertTrue(!Analysis.windowLooksUnparsed(GitLog.resolveSince(repo, "1 year ago")!!, now))
        // The reference instant is the moment of resolution, not "now" at check time:
        // a setup that took minutes must not make a malformed date look valid.
        assertTrue(
            Analysis.windowLooksUnparsed(resolved, now.plus(java.time.Duration.ofMinutes(10))).not(),
            "a later reference is a different question; callers must pass the resolution instant",
        )
    }

    /**
     * The banner is suppressed under `--json`, so a broken window used to produce a
     * clean-looking empty analysis. An agent reads "no findings", not "invalid input".
     */
    @Test
    fun `a window over nothing is a warning in the JSON, not just in the banner`() {
        initRepo()
        val result = Analyze().test(listOf(repo.path, "--since", "las year", "--json"))
        assertEquals(0, result.statusCode, result.output)
        val parsed = json.decodeFromString(AnalysisResult.serializer(), result.stdout)
        val warning = parsed.warnings.singleOrNull { it.code == AnalysisWarning.WINDOW_IS_NOW }
        assertNotNull(warning, "warnings were ${parsed.warnings.map { it.code }}")
        assertEquals(AnalysisWarning.WARNING, warning.severity)
        assertTrue(parsed.findings.isEmpty(), "sanity: this window really does select nothing")
    }

    @Test
    fun `a valid window carries no window warning`() {
        initRepo()
        val result = Analyze().test(listOf(repo.path, "--since", "1 year ago", "--json"))
        assertEquals(0, result.statusCode, result.output)
        val parsed = json.decodeFromString(AnalysisResult.serializer(), result.stdout)
        assertTrue(
            parsed.warnings.none { it.code == AnalysisWarning.WINDOW_IS_NOW },
            "warnings were ${parsed.warnings.map { it.code }}",
        )
    }

    @Test
    fun `saved conditions pin the commit, the window, and the resolved change unit`() {
        initRepo()
        val setup = Analysis.contextFor(repo, AnalysisOptions(since = "1 year ago", changeUnit = "auto"))
        val pinned = setup.resolvedOptions
        assertEquals(setup.headCommit, pinned.branch, "replaying a branch name would follow a moving tip")
        assertNotEquals("1 year ago", pinned.since, "replaying a relative window would read a different history")
        assertNotEquals("auto", pinned.changeUnit, "replaying 'auto' could re-decide differently")
        assertTrue(pinned.changeUnit in setOf("merge", "author-window", "commit"), "was ${pinned.changeUnit}")
    }

    @Test
    fun `replaying pinned conditions reads the same history`() {
        initRepo()
        val first = Analysis.contextFor(repo, AnalysisOptions(since = "1 year ago", changeUnit = "auto"))
        // A later commit must not leak into a replay of the pinned conditions.
        File(repo, "later.kt").writeText("// added after the snapshot")
        git("add", "-A")
        git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "second")

        val replay = Analysis.contextFor(repo, first.resolvedOptions)
        assertEquals(first.changes.size, replay.changes.size)
        assertEquals(first.headCommit, replay.headCommit)
        assertTrue("later.kt" !in replay.context.headFiles, "the pinned commit predates later.kt")
    }

    private companion object {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }

}

/**
 * A finding is usually read through `inspect`, one at a time. If the run's caveats stop
 * at `analyze`, an agent inspecting finding-1 has no way to learn that the window it
 * came from selected nothing.
 */
class InspectWarningsTest {
    private val repo = File.createTempFile("cochange-inspect-warn", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    private fun git(vararg args: String) = GitLog.runGit(repo, args.toList())

    @Test
    fun `inspect carries the warnings of the run it replays`() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "T")
        // Two declared modules with a strong coupling, so there is a finding to inspect.
        for (i in 1..8) {
            File(repo, "app/build.gradle.kts").apply { parentFile.mkdirs() }.writeText("// app")
            File(repo, "core/build.gradle.kts").apply { parentFile.mkdirs() }.writeText("// core")
            File(repo, "app/A.kt").writeText("a$i\n")
            File(repo, "core/B.kt").writeText("b$i\n")
            git("add", "-A")
            git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "change $i")
        }
        // No --since at all: a note, not a warning, but it must still travel.
        // One commit per unit: the commits here are instantaneous, so author-window
        // grouping would collapse all eight into one and there would be no finding.
        val analyze = Analyze().test(listOf(repo.path, "--change-unit", "commit", "--save", "w"))
        assertEquals(0, analyze.statusCode, analyze.output)
        val inspect = Inspect().test(listOf("finding-1", repo.path, "--analysis", "w"))
        assertEquals(0, inspect.statusCode, inspect.output)
        val report = json.decodeFromString(InspectReport.serializer(), inspect.stdout)
        assertTrue(
            report.warnings.any { it.code == AnalysisWarning.NO_WINDOW },
            "inspect must repeat the run's caveats: ${report.warnings.map { it.code }}",
        )
    }

    private companion object {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
