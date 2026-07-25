package io.github.takahirom.cochange

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
        val resolved = GitLog.resolveSince(repo, "las year")
        assertNotNull(resolved)
        assertTrue(Analysis.windowLooksUnparsed(resolved), "a window resolving to ~now means git didn't understand it")
        assertTrue(!Analysis.windowLooksUnparsed(GitLog.resolveSince(repo, "1 year ago")!!))
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
}
