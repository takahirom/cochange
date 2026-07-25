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
