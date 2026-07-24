package io.github.takahirom.cochange

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedFilesTest {
    private val repo = File.createTempFile("cochange-gattr", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    private fun git(vararg args: String) = GitLog.runGit(repo, args.toList())

    @Test
    fun `reads linguist-generated from the analyzed revision, not the working tree`() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        File(repo, "gen").mkdirs()
        File(repo, "gen/Api.kt").writeText("x\n")
        File(repo, ".gitattributes").writeText("gen/Api.kt linguist-generated=true\n")
        git("add", ".")
        git("commit", "-q", "-m", "init")

        // Working tree now DISAGREES with the committed revision: drop the marker.
        File(repo, ".gitattributes").writeText("\n")

        // Analyzing HEAD must still classify the file as generated, reading the
        // attribute from the revision (--source) rather than the dirty working tree.
        val head = setOf("gen/Api.kt", ".gitattributes")
        assertEquals(setOf("gen/Api.kt"), GitLog.generatedFiles(repo, "HEAD", head))
    }
}
