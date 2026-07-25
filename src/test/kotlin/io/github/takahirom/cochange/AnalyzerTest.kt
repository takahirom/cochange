package io.github.takahirom.cochange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyzerTest {
    private var t = 0L
    private fun commit(author: String, vararg files: String): Commit {
        t += 3600 * 24
        return Commit("h$t", author, t, "msg", files.toList())
    }

    @Test
    fun `grouping merges same-author commits within window`() {
        val base = Commit("a1", "alice", 1000, "one", listOf("A.kt"))
        val fixup = Commit("a2", "alice", 1000 + 600, "fixup", listOf("B.kt"))
        val later = Commit("a3", "alice", 1000 + 600 + 10_000, "other", listOf("C.kt"))
        val changes = Grouping.group(listOf(later, fixup, base), windowSec = 1800)
        assertEquals(2, changes.size)
        assertEquals(setOf("A.kt", "B.kt"), changes.first { it.commits.size == 2 }.files)
    }

    @Test
    fun `detects boundary mismatch across modules`() {
        val head = setOf(
            "app/build.gradle.kts", "data/build.gradle.kts",
            "app/PaymentScreen.kt", "data/PaymentRepository.kt", "app/Other.kt",
        )
        val changes = buildList {
            repeat(8) { add(LogicalChange(listOf(commit("alice", "app/PaymentScreen.kt", "data/PaymentRepository.kt")))) }
            repeat(2) { add(LogicalChange(listOf(commit("bob", "data/PaymentRepository.kt", "app/Other.kt")))) }
            repeat(2) { add(LogicalChange(listOf(commit("bob", "app/PaymentScreen.kt")))) }
        }
        val findings = Analyzer(minSupport = 5, minConfidence = 0.6)
            .analyze(changes, Boundaries(head), head)
        val mismatch = findings.filter { it.type == "boundary_mismatch" }
        assertEquals(1, mismatch.size)
        assertEquals(0.8, mismatch[0].confidence)
        assertTrue(mismatch[0].detail.supportingChanges.isNotEmpty())
    }

    @Test
    fun `same-module pairs are not boundary mismatches`() {
        // A second module exists, so the gate lets boundary findings run — the pair
        // is simply inside one module.
        val head = setOf("app/build.gradle.kts", "app/A.kt", "app/B.kt", "core/build.gradle.kts", "core/C.kt")
        val changes = List(10) { LogicalChange(listOf(commit("alice", "app/A.kt", "app/B.kt"))) } +
            List(3) { LogicalChange(listOf(commit("alice", "core/C.kt"))) }
        val findings = Analyzer(minSupport = 5, minConfidence = 0.6)
            .analyze(changes, Boundaries(head), head)
        assertTrue(findings.none { it.type == "boundary_mismatch" })
    }

    @Test
    fun `detects unstable hub touched by changes across modules`() {
        val mods = listOf("a", "b", "c", "d", "e")
        val head = buildSet {
            add("core/build.gradle.kts"); add("core/AppModule.kt")
            for (m in mods) { add("$m/build.gradle.kts"); add("$m/${m.uppercase()}.kt") }
        }
        val changes = buildList {
            for (m in mods) {
                repeat(5) { add(LogicalChange(listOf(commit("x", "core/AppModule.kt", "$m/${m.uppercase()}.kt")))) }
            }
            repeat(30) { add(LogicalChange(listOf(commit("x", "a/A.kt", "b/B.kt")))) }
        }
        val findings = Analyzer(minSupport = 100, minConfidence = 0.99)
            .analyze(changes, Boundaries(head), head)
        val hubs = findings.filter { it.type == "unstable_hub" }
        assertEquals(1, hubs.size)
        assertTrue(hubs[0].summary.contains("AppModule.kt"))
    }

    @Test
    fun `module detection uses nearest build file`() {
        val head = setOf("build.gradle.kts", "feature/payment/build.gradle.kts", "feature/payment/src/P.kt", "docs/readme.md")
        val boundaries = Boundaries(head)
        assertEquals("feature/payment", boundaries.moduleOf("feature/payment/src/P.kt"))
        // Root build file makes the root a module: unclaimed paths belong to it,
        // so a single-module repo doesn't fabricate per-directory boundaries.
        assertEquals("<root>", boundaries.moduleOf("docs/readme.md"))
        assertEquals("<root>", boundaries.moduleOf("README.md"))
    }

    @Test
    fun `repo without any build files falls back to top-level directories`() {
        val head = setOf("src/A.kt", "docs/readme.md")
        val boundaries = Boundaries(head)
        assertEquals("src", boundaries.moduleOf("src/A.kt"))
        assertEquals("docs", boundaries.moduleOf("docs/readme.md"))
    }
}

class FileCategoryTest {
    @Test
    fun `classifies files across ecosystems`() {
        assertEquals("build", FileCategory.of("app/build.gradle.kts"))
        assertEquals("build", FileCategory.of("gradle/libs.versions.toml"))
        assertEquals("build", FileCategory.of("workspaces/client/package.json"))
        assertEquals("build", FileCategory.of("Cargo.toml"))
        assertEquals("docs", FileCategory.of("README.md"))
        assertEquals("config", FileCategory.of(".github/workflows/ci.yml"))
        assertEquals("source", FileCategory.of("src/main/App.tsx"))
        assertEquals("source", FileCategory.of("lib/service.rb"))
    }

    @Test
    fun `pair inherits the least source-like category`() {
        assertEquals("build", FileCategory.ofPair("src/App.kt", "app/build.gradle.kts"))
        assertEquals("source", FileCategory.ofPair("src/App.kt", "src/Repo.kt"))
        assertEquals("docs", FileCategory.ofPair("README.md", "app/build.gradle.kts"))
    }

    @Test
    fun `built-in heuristics classify well-known generated artifacts`() {
        assertEquals(FileCategory.GENERATED, FileCategory.of("Mocks/zzz.mockolo.swift"))
        assertEquals(FileCategory.GENERATED, FileCategory.of("Generated/zzz.Sourcery.Foo.out.swift"))
        assertEquals(FileCategory.GENERATED, FileCategory.of("api/service.pb.go"))
        assertEquals(FileCategory.GENERATED, FileCategory.of("lib/model.g.dart"))
    }

    @Test
    fun `hand-written files that merely mention generated are not swept up`() {
        assertFalse(FileCategory.looksGenerated("ui/GeneratedContentView.swift"))
        assertEquals(FileCategory.SOURCE, FileCategory.of("ui/GeneratedContentView.swift"))
    }
}

class GeneratedCategoryTest {
    private var t = 0L
    private fun commit(vararg files: String): Commit { t += 3600 * 24; return Commit("h$t", "alice", t, "m", files.toList()) }

    @Test
    fun `gitattributes-declared files override the name-based category`() {
        val head = setOf("app/Foo.kt", "app/Bar.kt")
        val context = AnalysisContext(emptyList(), Boundaries(head), head, generated = setOf("app/Foo.kt"))
        // Declared generated, despite an ordinary .kt name.
        assertEquals(FileCategory.GENERATED, context.categoryOf("app/Foo.kt"))
        // A pair inherits the generated (least source-like) side.
        assertEquals(FileCategory.GENERATED, context.categoryOfPair("app/Foo.kt", "app/Bar.kt"))
    }

    @Test
    fun `generated findings rank below source findings`() {
        val head = setOf(
            "app/build.gradle.kts", "data/build.gradle.kts", "gen/build.gradle.kts",
            "app/PaymentScreen.kt", "data/PaymentRepository.kt",
            "app/CardView.kt", "gen/zzz.mockolo.kt",
        )
        val changes = buildList {
            repeat(8) { add(LogicalChange(listOf(commit("app/PaymentScreen.kt", "data/PaymentRepository.kt")))) }
            // A generated file coupled to source across a module boundary — the classic noise.
            repeat(8) { add(LogicalChange(listOf(commit("app/CardView.kt", "gen/zzz.mockolo.kt")))) }
        }
        val findings = Analyzer(minSupport = 5, minConfidence = 0.6).analyze(changes, Boundaries(head), head)
        val sourceIdx = findings.indexOfFirst { it.category == FileCategory.SOURCE }
        val generatedIdx = findings.indexOfFirst { it.category == FileCategory.GENERATED }
        assertTrue(sourceIdx >= 0, "expected a source finding")
        assertTrue(generatedIdx >= 0, "expected a generated finding")
        assertTrue(sourceIdx < generatedIdx, "source findings must rank before generated ones")
    }
}

class NamesRelatedTest {
    @Test
    fun `companion pairs are related`() {
        assertTrue(BoundaryMismatchDetector.namesRelated("domain/PaymentRepository.kt", "app/DefaultPaymentRepository.kt"))
        assertTrue(BoundaryMismatchDetector.namesRelated("payment/PaymentGateway.kt", "test-support/FakePaymentGateway.kt"))
        assertTrue(BoundaryMismatchDetector.namesRelated("a/README.md", "b/README.md"))
    }

    @Test
    fun `unrelated names are not related`() {
        assertTrue(!BoundaryMismatchDetector.namesRelated("app/CheckoutScreen.kt", "core/PricingRules.kt"))
        assertTrue(!BoundaryMismatchDetector.namesRelated("app/OrderHistoryPage.kt", "designsystem/HorizontalCarousel.kt"))
    }
}

class CouplingKindTest {
    @Test
    fun `cross-language coupling is high effort even when the names match`() {
        // Screen.kt / Screen.swift: platform-parallel, not a cheap companion pair.
        val e = CouplingKind.of("android/Screen.kt", "ios/Screen.swift", FileCategory.SOURCE, namesRelated = true)
        assertEquals("cross-language", e.kind)
        assertEquals("high", e.effort)
    }

    @Test
    fun `same-language interface-impl pair is low effort`() {
        val e = CouplingKind.of("domain/PaymentRepository.kt", "data/DefaultPaymentRepository.kt", FileCategory.SOURCE, namesRelated = true)
        assertEquals("companion", e.kind)
        assertEquals("low", e.effort)
    }

    @Test
    fun `unrelated same-language coupling is medium effort`() {
        val e = CouplingKind.of("app/CheckoutScreen.kt", "core/PricingRules.kt", FileCategory.SOURCE, namesRelated = false)
        assertEquals("same-language", e.kind)
        assertEquals("medium", e.effort)
    }

    @Test
    fun `generated coupling needs no fixing`() {
        val e = CouplingKind.of("app/Foo.kt", "gen/Foo.mockolo.kt", FileCategory.GENERATED, namesRelated = true)
        assertEquals("generated", e.kind)
        assertEquals("none", e.effort)
    }

    @Test
    fun `an unrecognized language is not understated as companion or same-language`() {
        // .php is unmapped; the pair still crosses a language boundary and must not
        // be labelled low-effort companion just because the names match.
        val e = CouplingKind.of("web/Foo.kt", "legacy/Foo.php", FileCategory.SOURCE, namesRelated = true)
        assertEquals("cross-language", e.kind)
        assertEquals("high", e.effort)
    }

    @Test
    fun `boundary mismatch finding carries the effort estimate end to end`() {
        var t = 0L
        fun commit(vararg files: String): Commit { t += 3600 * 24; return Commit("h$t", "alice", t, "m", files.toList()) }
        // Kotlin↔Swift across modules: the detector must wire the estimate through.
        val head = setOf(
            "android/build.gradle.kts", "android/Screen.kt",
            "ios/Package.swift", "ios/Sources/App/Screen.swift",
        )
        val changes = List(8) { LogicalChange(listOf(commit("android/Screen.kt", "ios/Sources/App/Screen.swift"))) }
        val finding = Analyzer(minSupport = 5, minConfidence = 0.6)
            .analyze(changes, Boundaries(head), head)
            .single { it.type == "boundary_mismatch" }
        assertEquals("high", finding.effort)
        assertEquals("cross-language", finding.detail.metrics["couplingKind"])
        assertEquals("high", finding.detail.metrics["effort"])
        assertTrue(finding.detail.interpretations.any { it.startsWith("Effort (cross-language):") })
    }
}

class SplitCandidateGroupsTest {
    private var t = 0L
    private fun commit(vararg files: String): Commit { t += 3600 * 24; return Commit("h$t", "a", t, "m", files.toList()) }

    @Test
    fun `split_candidate exposes full groups with support and active period`() {
        val head = setOf("Hub.kt", "A1.kt", "A2.kt", "B1.kt", "B2.kt")
        val changes = buildList {
            repeat(6) { add(LogicalChange(listOf(commit("Hub.kt", "A1.kt", "A2.kt")))) }
            repeat(6) { add(LogicalChange(listOf(commit("Hub.kt", "B1.kt", "B2.kt")))) }
        }
        val finding = Analyzer(minSupport = 5, minConfidence = 0.6)
            .analyze(changes, Boundaries(head), head)
            .single { it.type == "split_candidate" }
        val groups = finding.detail.groups
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.files.size == 2 }, "each group has its full file list")
        assertTrue(groups.all { it.support > 0 })
        assertTrue(groups.all { it.activeFrom.isNotEmpty() && it.activeTo.isNotEmpty() })
    }
}

class DistinguishingLabelsTest {
    @Test
    fun `different basenames use the basename`() {
        assertEquals("App.kt" to "Repo.kt", distinguishingLabels("app/App.kt", "data/Repo.kt"))
    }

    @Test
    fun `same basename adds the smallest distinguishing suffix`() {
        assertEquals(
            "values/strings.xml" to "values-ja/strings.xml",
            distinguishingLabels("feature/home/values/strings.xml", "feature/home/values-ja/strings.xml"),
        )
    }

    @Test
    fun `keeps extending until the suffixes differ`() {
        assertEquals(
            "a/x/build.gradle.kts" to "b/x/build.gradle.kts",
            distinguishingLabels("a/x/build.gradle.kts", "b/x/build.gradle.kts"),
        )
    }
}

class ClustersTest {
    private var t = 0L
    private fun change(vararg files: String): LogicalChange {
        t += 3600 * 24
        return LogicalChange(listOf(Commit("h$t", "a", t, "m", files.toList())))
    }

    @Test
    fun `connected pairs merge into one cluster and independent pairs stay separate`() {
        val head = setOf("A.kt", "B.kt", "C.kt", "X.kt", "Y.kt")
        val changes = buildList {
            repeat(10) { add(change("A.kt", "B.kt")) }
            repeat(10) { add(change("A.kt", "C.kt")) }
            repeat(10) { add(change("X.kt", "Y.kt")) }
        }
        val clusters = Clusters.build(AnalysisContext(changes, Boundaries(head), head), minSupport = 5, minConfidence = 0.5)
        assertEquals(2, clusters.size)
        assertEquals(listOf("A.kt", "B.kt", "C.kt"), clusters[0].files.sorted())
        assertEquals(listOf("X.kt", "Y.kt"), clusters[1].files.sorted())
    }
}

class SplitCandidateTest {
    private var t = 0L
    private fun change(vararg files: String): LogicalChange {
        t += 3600 * 24
        return LogicalChange(listOf(Commit("h$t", "a", t, "m", files.toList())))
    }

    @Test
    fun `file bridging two independent partner clusters is a split candidate`() {
        val head = setOf("God.kt", "a/A1.kt", "a/A2.kt", "b/B1.kt", "b/B2.kt")
        val changes = buildList {
            repeat(8) { add(change("God.kt", "a/A1.kt", "a/A2.kt")) }
            repeat(8) { add(change("God.kt", "b/B1.kt", "b/B2.kt")) }
        }
        val findings = SplitCandidateDetector().detect(AnalysisContext(changes, Boundaries(head), head))
        assertEquals(1, findings.size)
        assertTrue(findings[0].summary.contains("God.kt"))
        assertTrue(findings[0].summary.contains("2 independent change clusters"))
    }

    @Test
    fun `file whose partners all co-change is not a split candidate`() {
        val head = setOf("Hub.kt", "a/A1.kt", "a/A2.kt", "b/B1.kt", "b/B2.kt")
        val changes = List(10) { change("Hub.kt", "a/A1.kt", "a/A2.kt", "b/B1.kt", "b/B2.kt") }
        val findings = SplitCandidateDetector().detect(AnalysisContext(changes, Boundaries(head), head))
        assertTrue(findings.isEmpty())
    }
}

class MetricsTest {
    private var t = 0L
    private fun change(vararg files: String): LogicalChange {
        t += 3600 * 24
        return LogicalChange(listOf(Commit("h$t", "a", t, "m", files.toList())))
    }

    @Test
    fun `locality and boundary integrity are unit shares and hubs need enough modules`() {
        val head = setOf("a/build.gradle.kts", "b/build.gradle.kts", "a/A1.kt", "a/A2.kt", "b/B1.kt", "b/B2.kt")
        val changes = buildList {
            repeat(6) { add(change("a/A1.kt", "a/A2.kt")) }   // local
            repeat(6) { add(change("a/A1.kt", "b/B1.kt")) }   // cross-module, hotspot pair (conf >= 0.6)
            repeat(2) { add(change("a/A2.kt", "b/B2.kt")) }   // cross-module, below hotspot support
        }
        val m = Metrics.compute(AnalysisContext(changes, Boundaries(head), head))
        assertEquals(6.0 / 14, m.moduleLocality)
        assertEquals(1, m.boundaryHotspots)
        assertEquals(2.0 / 8, m.boundaryIntegrity)
        assertNull(m.hubFreeRate) // only 2 modules: hub predicate unsatisfiable
        assertFalse(m.lowResolution) // effective modules ~1.7, above the 1.25 threshold
    }

    @Test
    fun `single-module repo reports module scores as not applicable`() {
        val head = setOf("app/build.gradle.kts", "app/A.kt", "app/B.kt")
        val changes = List(10) { change("app/A.kt", "app/B.kt") }
        val m = Metrics.compute(AnalysisContext(changes, Boundaries(head), head))
        assertNull(m.moduleLocality)
        assertNull(m.boundaryIntegrity)
        assertTrue(m.lowResolution) // one effective module is below any resolution threshold
    }
}

class BoundaryHintsTest {
    @Test
    fun `swiftpm targets under Package_swift become module roots`() {
        val head = setOf(
            "apple/Package.swift",
            "apple/Sources/PlayerUI/View.swift",
            "apple/Sources/AppUI/App.swift",
            "apple/Tests/PlayerUITests/ViewTests.swift",
        )
        val b = Boundaries(head)
        assertEquals("apple/Sources/PlayerUI", b.moduleOf("apple/Sources/PlayerUI/View.swift"))
        assertEquals("apple/Sources/AppUI", b.moduleOf("apple/Sources/AppUI/App.swift"))
        assertEquals("apple/Tests/PlayerUITests", b.moduleOf("apple/Tests/PlayerUITests/ViewTests.swift"))
    }

    @Test
    fun `module-root globs mark directories as boundaries`() {
        val head = setOf(
            "ios/Targets/Domain/Sources/Repo.swift",
            "ios/Targets/Repository/Sources/Impl.swift",
            "ios/Other/File.swift",
        )
        val b = Boundaries(head, moduleRootGlobs = listOf("ios/Targets/*"))
        assertEquals("ios/Targets/Domain", b.moduleOf("ios/Targets/Domain/Sources/Repo.swift"))
        assertEquals("ios/Targets/Repository", b.moduleOf("ios/Targets/Repository/Sources/Impl.swift"))
        assertEquals("ios", b.moduleOf("ios/Other/File.swift"))
    }
}
