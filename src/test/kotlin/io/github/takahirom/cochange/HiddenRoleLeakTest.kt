package io.github.takahirom.cochange

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `--exclude-role` promises two things at once: no number moves, and no hidden path is
 * shown. The second half kept leaking one output at a time — a group's prose, a cluster's
 * strongest-pair line, a collapsed family's siblings, `collapsedFamilies` in JSON.
 *
 * So this checks it the only way that closes the class: run EVERY command, in text and in
 * JSON, and assert that a hidden file's distinctive name appears nowhere at all.
 */
class HiddenRoleLeakTest {
    private val repo = File.createTempFile("cochange-leak", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    private fun git(vararg args: String) = GitLog.runGit(repo, args.toList())

    /**
     * Two declared modules, a hub, two partner groups, a locale family, and a test file in
     * every one of them — so each output path has something it could leak.
     */
    private fun buildRepo() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "dev@example.com")
        git("config", "user.name", "Dev")
        fun write(path: String, content: String) =
            File(repo, path).apply { parentFile?.mkdirs() }.writeText(content)

        write("app/build.gradle.kts", "// app")
        write("core/build.gradle.kts", "// core")
        for (i in 1..12) {
            write("app/Hub.kt", "hub$i\n")
            write("app/SECRETROLE_HubTest.kt", "t$i\n")
            write("core/Rules.kt", "r$i\n")
            write("core/SECRETROLE_RulesTest.kt", "t$i\n")
            write("app/res/values/strings.xml", "<s>$i</s>")
            write("app/res/values-ja/strings.xml", "<s>$i</s>")
            git("add", "-A")
            git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "change $i")
        }
        // A second partner group, so split_candidate has groups to describe.
        for (i in 1..8) {
            write("app/Hub.kt", "hub-b$i\n")
            write("core/Other.kt", "o$i\n")
            write("core/SECRETROLE_OtherTest.kt", "t$i\n")
            git("add", "-A")
            git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "other $i")
        }
        // Make the two test files the STRONGEST pair in the repository, so every
        // "show the strongest edge / top hotspot / first supporting change" path has a
        // hidden pair sitting in front of it. Without this the fixture never reaches
        // those branches and the sweep below passes for the wrong reason.
        for (i in 1..25) {
            write("app/SECRETROLE_HubTest.kt", "strong$i\n")
            write("core/SECRETROLE_RulesTest.kt", "strong$i\n")
            git("add", "-A")
            git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "tests together $i")
        }
        // ...and a third group of test-only partners for the split candidate.
        for (i in 1..6) {
            write("app/Hub.kt", "hub-c$i\n")
            write("app/SECRETROLE_ThirdTest.kt", "t$i\n")
            write("core/SECRETROLE_FourthTest.kt", "t$i\n")
            git("add", "-A")
            git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "third $i")
        }
    }

    @Test
    fun `no excluded path appears in any command's output`() {
        buildRepo()
        val exclude = listOf("--exclude-role", "test")
        val unit = listOf("--change-unit", "commit")

        // Sanity: the test files really are analysed, and really are found without the filter.
        val open = Analyze().test(listOf(repo.path) + unit + listOf("--min-support", "5", "--save", "open"))
        assertEquals(0, open.statusCode, open.output)
        assertTrue(
            "SECRETROLE" in open.output || "SECRETROLE" in Pairs().test(listOf(repo.path) + unit).output,
            "the fixture must actually surface the test files when nothing is excluded",
        )

        val analyze = Analyze().test(listOf(repo.path) + unit + exclude + listOf("--min-support", "5", "--save", "hidden"))
        assertEquals(0, analyze.statusCode, analyze.output)

        val outputs = linkedMapOf(
            "analyze" to analyze.output,
            "analyze --json" to Analyze().test(
                listOf(repo.path) + unit + exclude + listOf("--min-support", "5", "--save", "hidden", "--json"),
            ).output,
            "findings" to Findings().test(listOf(repo.path, "--analysis", "hidden")).output,
            "findings --json" to Findings().test(listOf(repo.path, "--analysis", "hidden", "--json")).output,
            "pairs" to Pairs().test(listOf(repo.path) + unit + exclude).output,
            "pairs --json" to Pairs().test(listOf(repo.path) + unit + exclude + listOf("--json")).output,
            "clusters" to ClustersCommand().test(listOf(repo.path) + unit + exclude + listOf("--min-support", "3")).output,
            "clusters --show" to ClustersCommand().test(
                listOf(repo.path) + unit + exclude + listOf("--min-support", "3", "--show", "1"),
            ).output,
            "clusters --json" to ClustersCommand().test(
                listOf(repo.path) + unit + exclude + listOf("--min-support", "3", "--json"),
            ).output,
            "metrics" to MetricsCommand().test(listOf(repo.path) + unit + exclude).output,
            "metrics --json" to MetricsCommand().test(listOf(repo.path) + unit + exclude + listOf("--json")).output,
            "compare" to CompareCommand().test(
                listOf(repo.path) + unit + exclude + listOf("--baseline", "10 years ago", "--recent", "1 day ago", "--min-count", "1"),
            ).output,
            "compare --json" to CompareCommand().test(
                listOf(repo.path) + unit + exclude +
                    listOf("--baseline", "10 years ago", "--recent", "1 day ago", "--min-count", "1", "--json"),
            ).output,
        )

        // inspect every finding too, since that is where the most prose lives.
        val findingsJson = Findings().test(listOf(repo.path, "--analysis", "hidden", "--json")).stdout
        for (id in Regex("\"id\"\\s*:\\s*\"(finding-\\d+)\"").findAll(findingsJson).map { it.groupValues[1] }) {
            outputs["inspect $id"] = Inspect().test(listOf(id, repo.path, "--analysis", "hidden")).output
        }

        for ((command, output) in outputs) {
            assertTrue(
                "SECRETROLE" !in output,
                "$command leaked a file hidden by --exclude-role:\n" +
                    output.lines().filter { "SECRETROLE" in it }.joinToString("\n"),
            )
        }
        assertTrue(outputs.keys.any { it.startsWith("inspect ") }, "at least one finding must have been inspected")
    }
}
