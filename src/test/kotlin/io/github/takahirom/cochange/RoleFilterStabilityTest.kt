package io.github.takahirom.cochange

import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The other half of the `--exclude-role` contract: it may shorten a LISTING, and it may not
 * move a NUMBER.
 *
 * This broke one aggregate at a time — a cluster's support volume, a split candidate's group
 * count, compare's trend, `boundaryIntegrity`, `hubFreeRate` — because each was written to
 * filter its numerator while its denominator kept everything. So the aggregates are
 * enumerated here explicitly rather than swept generically: a generic walk needs an
 * exemption for every legitimately-shrinking list (`findings`, `pairs`, `moves`, `hubFiles`,
 * a cluster's `files`), and an exemption list that grows is an exemption list that hides the
 * next real regression.
 */
class RoleFilterStabilityTest {
    private val repo = File.createTempFile("cochange-stability", "").apply { delete(); mkdirs() }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    private fun git(vararg args: String) = GitLog.runGit(repo, args.toList())

    private val unit = listOf("--change-unit", "commit")
    private val exclude = listOf("--exclude-role", "test")

    /**
     * Eight declared modules so hubs are possible, a test file dragged into everything so it
     * qualifies as a hub, and a second cluster whose strongest pair is a test pair.
     */
    private fun buildRepo() {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "dev@example.com")
        git("config", "user.name", "Dev")
        fun write(path: String, content: String) =
            File(repo, path).apply { parentFile?.mkdirs() }.writeText(content)

        write("build.gradle.kts", "// root")
        for (i in 1..8) write("m$i/build.gradle.kts", "// m$i")
        for (i in 1..30) {
            write("m${(i % 8) + 1}/F.kt", "f$i\n")
            write("m1/SharedTest.kt", "t$i\n")
            git("add", "-A")
            git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "change $i")
        }
        for (i in 1..20) {
            write("m2/ATest.kt", "a$i\n")
            write("m3/BTest.kt", "b$i\n")
            git("add", "-A")
            git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "tests $i")
        }
    }

    private fun json(command: String, extra: List<String>): JsonObject {
        val args = listOf(repo.path) + unit + extra
        val result = when (command) {
            "metrics" -> MetricsCommand().test(args + "--json")
            "clusters" -> ClustersCommand().test(args + listOf("--min-support", "3", "--json"))
            "compare" -> CompareCommand().test(
                args + listOf("--baseline", "10 years ago", "--recent", "1 day ago", "--min-count", "1", "--json"),
            )
            else -> error("unknown command $command")
        }
        assertEquals(0, result.statusCode, result.output)
        return reader.parseToJsonElement(result.stdout) as JsonObject
    }

    private fun num(obj: JsonObject, field: String): String = obj[field]!!.jsonPrimitive.content

    @Test
    fun `every metric score and count is identical with a role hidden`() {
        buildRepo()
        val open = json("metrics", emptyList())
        val hidden = json("metrics", exclude)
        val fields = listOf(
            "moduleLocality", "adjustedLocality", "expectedLocality", "hubFreeRate", "boundaryIntegrity",
            "effectiveModules", "distinctModules", "multiFileUnits", "declaredMultiFileUnits",
            "crossModuleUnits", "localUnits", "hubAvoidingUnits", "hubCount", "boundaryHotspots",
        )
        // Sanity: the filter must actually remove a hub, or none of this proves anything.
        assertTrue(
            (open["hubFiles"] as JsonArray).size > (hidden["hubFiles"] as JsonArray).size,
            "the fixture must hide a hub: ${open["hubFiles"]} vs ${hidden["hubFiles"]}",
        )
        assertTrue(num(open, "hubFreeRate") != "null", "hubFreeRate must be computable here")
        for (f in fields) assertEquals(num(open, f), num(hidden, f), "metrics.$f moved")
    }

    @Test
    fun `every cluster's counts are identical with a role hidden`() {
        buildRepo()
        fun counts(obj: JsonObject) = (obj["clusters"] as JsonArray)
            .map { it as JsonObject }
            .map { "files=${num(it, "fileCount")} pairs=${num(it, "strongPairs")} volume=${num(it, "pairSupportVolume")}" }
            .sorted()

        val open = json("clusters", emptyList())
        val hidden = json("clusters", exclude)
        // Sanity: some cluster must actually be hiding members.
        assertTrue(
            (hidden["clusters"] as JsonArray).any { (it as JsonObject)["hiddenFiles"]!!.jsonPrimitive.content != "0" },
            "the fixture must hide cluster members",
        )
        assertEquals(
            counts(open), counts(hidden),
            "a cluster's size, strong-pair count and support volume are facts about the history",
        )
    }

    @Test
    fun `compare's denominators and trend are identical with a role hidden`() {
        buildRepo()
        val open = json("compare", emptyList())
        val hidden = json("compare", exclude)
        for (f in listOf("heating", "cooling", "totalAbsShift", "meanAbsShift", "summarizedFiles")) {
            assertEquals(num(open, f), num(hidden, f), "compare.$f moved")
        }
        for (window in listOf("baseline", "recent")) {
            val a = open[window] as JsonObject
            val b = hidden[window] as JsonObject
            for (f in listOf("logicalChanges", "multiFileChanges")) {
                assertEquals(num(a, f), num(b, f), "compare.$window.$f moved")
            }
        }
        // Sanity: the listing did shrink even though nothing above moved.
        assertTrue(
            (open["moves"] as JsonArray).size > (hidden["moves"] as JsonArray).size,
            "the filter must shorten the listing",
        )
    }

    private companion object {
        val reader = Json { ignoreUnknownKeys = true }
    }
}
