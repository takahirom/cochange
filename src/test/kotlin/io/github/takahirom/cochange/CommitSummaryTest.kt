package io.github.takahirom.cochange

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `supportingChanges` used to be bare hashes. The one question co-change cannot
 * answer is "did these files change for the same *reason*", and answering it
 * meant leaving the tool to run `git show` by hand — so the subject, date, and
 * per-file churn are resolved here.
 */
class CommitSummaryTest {
    private val repo = File.createTempFile("cochange-summary", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    private fun git(vararg args: String) = GitLog.runGit(repo, args.toList())

    private fun commit(message: String, vararg files: Pair<String, String>): String {
        for ((path, content) in files) {
            File(repo, path).apply { parentFile?.mkdirs() }.writeText(content)
        }
        git("add", "-A")
        git("-c", "commit.gpgsign=false", "commit", "-q", "-m", message)
        return git("rev-parse", "HEAD").trim()
    }

    private fun initRepo() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "dev@example.com")
        git("config", "user.name", "Dev")
    }

    @Test
    fun `a hash resolves to subject, date, author, and per-file churn`() {
        initRepo()
        val first = commit("Add the screen", "app/Screen.kt" to "one\n", "core/Rules.kt" to "a\n")
        val second = commit("Change pricing", "app/Screen.kt" to "one\ntwo\nthree\n", "core/Rules.kt" to "a\nb\n")

        val summaries = GitLog.commitSummaries(repo, listOf(second, first), setOf("app/Screen.kt", "core/Rules.kt"))
        assertEquals(2, summaries.size)
        val latest = summaries.single { it.hash == second }
        assertEquals("Change pricing", latest.subject)
        assertEquals("Dev", latest.author)
        assertTrue(latest.date.matches(Regex("""\d{4}-\d{2}-\d{2}""")), "was ${latest.date}")
        // Two lines added to Screen.kt, one to Rules.kt.
        assertEquals(2, latest.churn["app/Screen.kt"])
        assertEquals(1, latest.churn["core/Rules.kt"])
    }

    @Test
    fun `churn is restricted to the finding's files`() {
        initRepo()
        val hash = commit(
            "Touch three files",
            "app/Screen.kt" to "x\n", "core/Rules.kt" to "y\n", "unrelated/Other.kt" to "z\n",
        )
        val summary = GitLog.commitSummaries(repo, listOf(hash), setOf("app/Screen.kt")).single()
        assertEquals(setOf("app/Screen.kt"), summary.churn.keys, "an unrelated file in the same commit is noise here")
    }

    @Test
    fun `an unreachable hash yields nothing rather than failing the command`() {
        initRepo()
        commit("Only commit", "a.kt" to "a\n")
        // A hash from another repository: inspect must still print the finding.
        val summaries = GitLog.commitSummaries(repo, listOf("0".repeat(40)), setOf("a.kt"))
        assertTrue(summaries.isEmpty())
    }
}

/**
 * In a merge-based repository the merge commit IS the change unit, and
 * `git show --numstat` prints nothing for a merge — so every supporting change came
 * back with no files, and `inspect` reported `filesTouched: []` for the very evidence
 * the finding rested on.
 */
class MergeAndRenameChurnTest {
    private val repo = File.createTempFile("cochange-merge-churn", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    private fun git(vararg args: String) = GitLog.runGit(repo, args.toList())

    private fun commit(message: String, vararg files: Pair<String, String>): String {
        for ((path, content) in files) File(repo, path).apply { parentFile?.mkdirs() }.writeText(content)
        git("add", "-A")
        git("-c", "commit.gpgsign=false", "commit", "-q", "-m", message)
        return git("rev-parse", "HEAD").trim()
    }

    @Test
    fun `a merge commit reports the churn of its whole side branch`() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "dev@example.com")
        git("config", "user.name", "Dev")
        commit("Initial", "app/A.kt" to "0\n", "core/B.kt" to "0\n")
        git("checkout", "-q", "-b", "feature")
        commit("Work", "app/A.kt" to "1\n2\n", "core/B.kt" to "1\n")
        git("checkout", "-q", "main")
        git("-c", "commit.gpgsign=false", "merge", "-q", "--no-ff", "-m", "Merge the feature", "feature")
        val merge = git("rev-parse", "HEAD").trim()

        val summary = GitLog.commitSummaries(repo, listOf(merge), setOf("app/A.kt", "core/B.kt")).single()
        assertEquals("Merge the feature", summary.subject)
        assertEquals(
            setOf("app/A.kt", "core/B.kt"), summary.churn.keys,
            "the merge's first-parent diff is what the change unit counted",
        )
    }

    /**
     * An octopus merge has more than two parents. `-m` asks git for a diff against every
     * one of them, so a side parent's contribution would be counted as churn the merge
     * change unit never attributed to it — `--diff-merges=first-parent` asks the question
     * the change unit actually asked.
     */
    @Test
    fun `an octopus merge reports only its first-parent diff`() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "dev@example.com")
        git("config", "user.name", "Dev")
        commit("Initial", "app/A.kt" to "0\n", "core/B.kt" to "0\n", "extra/C.kt" to "0\n")
        // Two side branches, each touching a different file.
        git("checkout", "-q", "-b", "one")
        commit("On one", "core/B.kt" to "one\n")
        git("checkout", "-q", "main")
        git("checkout", "-q", "-b", "two")
        commit("On two", "extra/C.kt" to "two\n")
        git("checkout", "-q", "main")
        commit("On main", "app/A.kt" to "main\n")
        git("-c", "commit.gpgsign=false", "merge", "-q", "--no-ff", "-m", "Octopus", "one", "two")
        val octopus = git("rev-parse", "HEAD").trim()

        val files = setOf("app/A.kt", "core/B.kt", "extra/C.kt")
        val summary = GitLog.commitSummaries(repo, listOf(octopus), files).single()
        assertEquals("Octopus", summary.subject)
        assertEquals(
            setOf("core/B.kt", "extra/C.kt"), summary.churn.keys,
            "the first-parent diff is what the merge brought in; app/A.kt was already on main",
        )
    }

    @Test
    fun `a renamed file's churn is attributed to its current path`() {
        // git -M writes a rename as "old => new" or "pre/{old => new}/post"; matching
        // those raw strings against the finding's paths dropped the churn silently.
        assertEquals("new/A.kt", GitLog.renameTarget("old/A.kt => new/A.kt"))
        assertEquals("app/feature/A.kt", GitLog.renameTarget("app/{old => feature}/A.kt"))
        assertEquals("app/A.kt", GitLog.renameTarget("app/{legacy => }/A.kt"))
        assertEquals("plain/path.kt", GitLog.renameTarget("plain/path.kt"))
    }
}
