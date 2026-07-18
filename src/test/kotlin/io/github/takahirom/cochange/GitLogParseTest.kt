package io.github.takahirom.cochange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitLogParseTest {
    private val sep = '\u0001'
    private val f = '\u0002'

    @Test
    fun `parses commits with statuses`() {
        val log = """
            ${sep}aaa${f}alice${f}1000${f}Fix payment
            M${'\t'}app/src/PaymentScreen.kt
            A${'\t'}data/src/PaymentRepository.kt
            ${sep}bbb${f}bob${f}900${f}Initial
            A${'\t'}app/src/PaymentScreen.kt
        """.trimIndent()
        val commits = GitLog.parseLog(log, emptyList())
        assertEquals(2, commits.size)
        assertEquals(listOf("app/src/PaymentScreen.kt", "data/src/PaymentRepository.kt"), commits[0].files)
        assertEquals("alice", commits[0].author)
        assertEquals(1000L, commits[0].epochSec)
    }

    @Test
    fun `unifies renamed paths to the newest path`() {
        // Newest-first: commit aaa renamed Old.kt -> New.kt, commit bbb (older) modified Old.kt.
        val log = """
            ${sep}aaa${f}alice${f}1000${f}Rename
            R100${'\t'}app/Old.kt${'\t'}app/New.kt
            ${sep}bbb${f}alice${f}900${f}Edit before rename
            M${'\t'}app/Old.kt
        """.trimIndent()
        val commits = GitLog.parseLog(log, emptyList())
        assertEquals(listOf("app/New.kt"), commits[0].files)
        assertEquals(listOf("app/New.kt"), commits[1].files)
    }

    @Test
    fun `applies exclude globs`() {
        val log = """
            ${sep}aaa${f}alice${f}1000${f}Change
            M${'\t'}app/src/generated/Gen.kt
            M${'\t'}gradle.lockfile
            M${'\t'}app/src/Real.kt
        """.trimIndent()
        val commits = GitLog.parseLog(log, listOf("**/generated/**", "*.lockfile"))
        assertEquals(listOf("app/src/Real.kt"), commits[0].files)
    }

    @Test
    fun `glob star does not cross directories`() {
        assertTrue(GitLog.globToRegex("*.lock").matches("yarn.lock"))
        assertTrue(!GitLog.globToRegex("*.lock").matches("sub/yarn.lock"))
        assertTrue(GitLog.globToRegex("**/*.lock").matches("sub/dir/yarn.lock"))
    }
}
