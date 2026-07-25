package io.github.takahirom.cochange

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
