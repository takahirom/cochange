package io.github.takahirom.cochange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FamiliesTest {
    private var t = 0L
    private fun commit(vararg files: String): Commit { t += 3600 * 24; return Commit("h$t", "a", t, "m", files.toList()) }

    @Test
    fun `collapses a synchronized same-basename sibling set`() {
        val head = setOf(
            "feature/build.gradle.kts",
            "feature/values/strings.xml", "feature/values-ja/strings.xml", "feature/values-fr/strings.xml",
            "feature/Screen.kt",
        )
        val changes = buildList {
            // The three strings.xml always move together, and with the screen.
            repeat(6) { add(LogicalChange(listOf(commit("feature/values/strings.xml", "feature/values-ja/strings.xml", "feature/values-fr/strings.xml", "feature/Screen.kt")))) }
        }
        val ctx = AnalysisContext(changes, Boundaries(head), head)
        val families = Families.detect(ctx, ctx.boundaries)
        assertEquals(1, families.size)
        val fam = families.single()
        assertEquals("feature/values/strings.xml", fam.representative) // shortest dir is the base variant
        assertEquals(3, fam.members.size)

        // Projection: family members become one node; the strings x strings pairs disappear.
        val projected = Families.project(ctx, ctx.boundaries, families)
        assertTrue(projected.changeCount("feature/values-ja/strings.xml") == 0)
        assertTrue(projected.changeCount("feature/values/strings.xml") > 0)
    }

    @Test
    fun `does not collapse files that merely share a name but move independently`() {
        val head = setOf("app/a/config.yml", "app/b/config.yml", "app/c/config.yml")
        val changes = buildList {
            // Each config changes on its own — never as a set.
            repeat(6) { add(LogicalChange(listOf(commit("app/a/config.yml")))) }
            repeat(6) { add(LogicalChange(listOf(commit("app/b/config.yml")))) }
            repeat(6) { add(LogicalChange(listOf(commit("app/c/config.yml")))) }
        }
        val ctx = AnalysisContext(changes, Boundaries(head), head)
        assertTrue(Families.detect(ctx, ctx.boundaries).isEmpty())
    }

    @Test
    fun `does not collapse different basenames like interface and impl`() {
        val head = setOf("domain/Repo.kt", "data/DefaultRepo.kt")
        val changes = List(6) { LogicalChange(listOf(commit("domain/Repo.kt", "data/DefaultRepo.kt"))) }
        val ctx = AnalysisContext(changes, Boundaries(head), head)
        assertTrue(Families.detect(ctx, ctx.boundaries).isEmpty())
    }
}

/**
 * Family collapsing removes edges, so it must rest on a declared module. Under guessed
 * boundaries every sibling service directory shares one top-level "module", which made
 * two independent services' config files look like one variant set.
 */
class FamilyProvenanceTest {
    private var t = 0L
    private fun commit(vararg files: String): Commit {
        t += 3600 * 24
        return Commit("h$t", "dev", t, "m", files.toList())
    }

    private fun families(head: Set<String>): List<Families.Family> {
        val changes = List(8) { LogicalChange(listOf(commit("services/orders/config.yml", "services/payments/config.yml"))) }
        val boundaries = Boundaries(head)
        return Families.detect(AnalysisContext(changes, boundaries, head), boundaries)
    }

    @Test
    fun `a guessed module is not enough to collapse a family`() {
        val head = setOf("services/orders/config.yml", "services/payments/config.yml")
        assertTrue(
            families(head).isEmpty(),
            "both files resolve to the guessed module 'services'; collapsing would delete a real edge",
        )
    }

    @Test
    fun `a declared module still collapses its variant set`() {
        val head = setOf(
            "build.gradle.kts",
            "services/orders/config.yml", "services/payments/config.yml",
        )
        val detected = families(head)
        assertTrue(detected.isNotEmpty(), "one declared root module covers both, so the family stands")
        assertEquals(2, detected.single().members.size)
    }
}

/**
 * `Families.project` aliases every non-representative member to the representative, so
 * those paths leave the projected context's `headFiles`. Asking the PROJECTED context
 * whether a sibling is visible therefore answered "hidden" for all of them, even with no
 * `--exclude-role` — the sibling list came back empty and claimed they were role-filtered.
 */
class FamilyVisibilityContextTest {
    private var t = 0L
    private fun commit(vararg files: String): Commit {
        t += 3600 * 24
        return Commit("h$t", "dev", t, "m", files.toList())
    }

    @Test
    fun `a projected context must not be asked whether a family sibling is visible`() {
        val head = setOf(
            "build.gradle.kts",
            "app/res/values/strings.xml", "app/res/values-ja/strings.xml", "app/Screen.kt",
        )
        val changes = List(8) {
            LogicalChange(listOf(commit("app/res/values/strings.xml", "app/res/values-ja/strings.xml", "app/Screen.kt")))
        }
        val boundaries = Boundaries(head)
        val raw = AnalysisContext(changes, boundaries, head)
        val families = Families.detect(raw, boundaries)
        assertTrue(families.isNotEmpty(), "the two locale files move as a set")

        val projected = Families.project(raw, boundaries, families)
        val member = families.single().members.single { it != families.single().representative }
        assertTrue(
            !projected.isVisible(member),
            "the projection aliased it away, which is exactly why the projected context cannot answer this",
        )
        assertTrue(
            raw.isVisible(member),
            "nothing was excluded, so the unprojected context is the one that gives the right answer",
        )
    }
}
