package io.github.takahirom.cochange

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeUnitTest {
    private val repo = File.createTempFile("cochange-test", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    private fun git(vararg args: String) = GitLog.runGit(repo, args.toList())

    private fun commitFile(path: String, message: String) {
        val file = File(repo, path)
        file.parentFile?.mkdirs()
        file.appendText("$message\n")
        git("add", ".")
        git("commit", "-q", "-m", message)
    }

    private fun setUpMergeHistory() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        commitFile("README.md", "init")
        git("checkout", "-q", "-b", "feature")
        commitFile("app/Screen.kt", "add screen")
        commitFile("data/Repository.kt", "add repository")
        git("checkout", "-q", "main")
        git("merge", "-q", "--no-ff", "-m", "Merge feature", "feature")
    }

    @Test
    fun `merge unit carries the whole side branch as one change`() {
        setUpMergeHistory()
        val units = MergeBasedChangeUnit().changeUnits(repo, "main", null, emptyList())
        // Mainline: initial commit + the merge (side branch collapsed into it).
        assertEquals(2, units.size)
        val mergeUnit = units.first { it.commits.single().message == "Merge feature" }
        assertEquals(setOf("app/Screen.kt", "data/Repository.kt"), mergeUnit.files)
    }

    @Test
    fun `auto picks merge for merge-based history and author-window for linear history`() {
        setUpMergeHistory()
        assertTrue(ChangeUnits.resolve("auto", repo, "main", null).strategy is MergeBasedChangeUnit)

        // Linear-only history: a fresh repo without merges resolves to author-window.
        val linearRepo = File.createTempFile("cochange-linear", "").apply { delete(); mkdirs() }
        try {
            GitLog.runGit(linearRepo, listOf("init", "-q", "-b", "main"))
            GitLog.runGit(linearRepo, listOf("config", "user.email", "t@example.com"))
            GitLog.runGit(linearRepo, listOf("config", "user.name", "T"))
            repeat(3) {
                File(linearRepo, "f$it.txt").writeText("x")
                GitLog.runGit(linearRepo, listOf("add", "."))
                GitLog.runGit(linearRepo, listOf("commit", "-q", "-m", "c$it"))
            }
            assertTrue(ChangeUnits.resolve("auto", linearRepo, "main", null).strategy is AuthorWindowChangeUnit)
        } finally {
            linearRepo.deleteRecursively()
        }
    }
}

class ChangeUnitDecisionTest {
    @Test
    fun `merge-heavy mainline with small merges resolves to merge`() {
        val resolved = ChangeUnits.decide(mainline = 100, merges = 90, total = 500)
        assertTrue(resolved.strategy is MergeBasedChangeUnit)
    }

    @Test
    fun `linear history resolves to author-window`() {
        val resolved = ChangeUnits.decide(mainline = 100, merges = 5, total = 105)
        assertTrue(resolved.strategy is AuthorWindowChangeUnit)
    }

    @Test
    fun `release-only mainline falls back to author-window with a hint`() {
        val resolved = ChangeUnits.decide(mainline = 10, merges = 8, total = 500)
        assertTrue(resolved.strategy is AuthorWindowChangeUnit)
        assertTrue(resolved.reason.contains("release-only"))
    }
}
