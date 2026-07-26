package io.github.takahirom.cochange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleProvenanceTest {
    @Test
    fun `a build file per module is reported as a declared boundary`() {
        val files = setOf(
            "app/build.gradle.kts", "app/src/Main.kt",
            "core/build.gradle.kts", "core/src/Core.kt",
        )
        val boundaries = Boundaries(files)
        assertEquals(ModuleSource.BUILD_FILE, boundaries.sourceOf("app/src/Main.kt"))
        val detection = boundaries.detection(files)
        assertEquals(1.0, detection.coverage)
        assertEquals(2, detection.moduleCount)
    }

    @Test
    fun `paths no build file covers are reported as guessed, not as modules`() {
        // No build files anywhere: moduleOf still answers ("src", "docs"), but from
        // directory names — the case the gate exists for.
        val files = setOf("src/a.py", "src/b.py", "docs/c.py", "tools/d.py")
        val boundaries = Boundaries(files)
        assertEquals(ModuleSource.TOP_LEVEL_DIR, boundaries.sourceOf("src/a.py"))
        val detection = boundaries.detection(files)
        assertEquals(0.0, detection.coverage)
        assertTrue(detection.methods.isEmpty(), "no declared signal was used")
    }

    @Test
    fun `a user glob counts as a declared boundary`() {
        val files = setOf("legacy/ios/Targets/Home/A.swift", "legacy/ios/Targets/Pay/B.swift")
        val boundaries = Boundaries(files, moduleRootGlobs = listOf("legacy/ios/Targets/*"))
        assertEquals(ModuleSource.USER_GLOB, boundaries.sourceOf("legacy/ios/Targets/Home/A.swift"))
        assertEquals(1.0, boundaries.detection(files).coverage)
    }

    @Test
    fun `mixed repos report partial coverage rather than a single verdict`() {
        val files = setOf(
            "app/build.gradle.kts", "app/src/Main.kt", "app/src/Other.kt",
            "scripts/deploy.sh", "misc/notes.txt",
        )
        val detection = Boundaries(files).detection(files)
        assertTrue(detection.coverage > 0.0 && detection.coverage < 1.0, "was ${detection.coverage}")
        assertEquals(2, detection.bySource[ModuleSource.TOP_LEVEL_DIR])
    }
}

class ModuleGateTest {
    private fun detection(declared: Int, fallback: Int, modules: Int, declaredModules: Int = modules) = ModuleDetection(
        totalFiles = declared + fallback,
        bySource = mapOf(ModuleSource.BUILD_FILE to declared, ModuleSource.TOP_LEVEL_DIR to fallback),
        moduleCount = modules,
        declaredModuleCount = declaredModules,
    )

    @Test
    fun `trust reports coverage, and coverage alone no longer decides eligibility`() {
        // 10% coverage across two declared modules: the repo-level answer is "a
        // boundary exists", and whether any particular pair rests on it is checked
        // per finding. Coverage still labels how much of the repo is guesswork.
        val report = ModuleGate.report(detection(declared = 10, fallback = 90, modules = 12, declaredModules = 2))
        assertEquals(ModuleGate.GUESSED, report.trust)
        assertTrue(report.moduleFindingsEnabled, "two declared modules do exist; the per-claim check does the rest")
        assertTrue(report.note.contains("withheld individually"), report.note)
    }

    @Test
    fun `nothing declared means nothing to compare`() {
        val report = ModuleGate.report(detection(declared = 0, fallback = 100, modules = 12, declaredModules = 0))
        assertEquals(ModuleGate.GUESSED, report.trust)
        assertFalse(report.moduleFindingsEnabled)
        assertTrue(report.note.contains("withheld"), report.note)
    }

    @Test
    fun `full coverage enables module findings without a caveat`() {
        val report = ModuleGate.report(detection(declared = 100, fallback = 0, modules = 8))
        assertEquals(ModuleGate.DECLARED, report.trust)
        assertTrue(report.moduleFindingsEnabled)
    }

    @Test
    fun `partial coverage says the guessed pairs are dropped one by one`() {
        val report = ModuleGate.report(detection(declared = 70, fallback = 30, modules = 8, declaredModules = 3))
        assertEquals(ModuleGate.PARTIAL, report.trust)
        assertTrue(report.moduleFindingsEnabled)
        assertTrue(report.note.contains("declared boundary"), report.note)
    }

    @Test
    fun `a single-module repo keeps its clean provenance but has no boundary to cross`() {
        val report = ModuleGate.report(detection(declared = 100, fallback = 0, modules = 1))
        assertEquals(ModuleGate.DECLARED, report.trust, "provenance is perfect; enablement is a separate question")
        assertFalse(report.moduleFindingsEnabled)
        assertTrue(report.note.contains("1 module was declared"), report.note)
    }
}

class DetectorGateTest {
    private fun commit(hash: String, files: List<String>) =
        Commit(hash, "dev", 1_700_000_000, "m", files)

    /** Same history, once with real module boundaries and once with only directory names. */
    private fun history(prefixA: String, prefixB: String) = (1..10).map {
        LogicalChange(listOf(commit("c$it", listOf("$prefixA/A.kt", "$prefixB/B.kt"))))
    }

    @Test
    fun `module findings are produced when boundaries are declared`() {
        val head = setOf("app/build.gradle.kts", "app/A.kt", "core/build.gradle.kts", "core/B.kt")
        val context = AnalysisContext(history("app", "core"), Boundaries(head), head)
        val run = Analyzer().run(context)
        assertTrue(run.moduleDetection.moduleFindingsEnabled)
        assertTrue(run.findings.any { it.type == "boundary_mismatch" })
        assertTrue(run.skipped.isEmpty())
    }

    @Test
    fun `the same history yields no module findings when modules are only folder names`() {
        // Identical co-change evidence, but nothing declares a boundary — so the
        // "these live in different modules" claim is withheld, with a reason.
        val head = setOf("app/A.kt", "core/B.kt", "extra/C.kt")
        val context = AnalysisContext(history("app", "core"), Boundaries(head), head)
        val run = Analyzer().run(context)
        assertFalse(run.moduleDetection.moduleFindingsEnabled)
        assertTrue(run.findings.none { it.type == "boundary_mismatch" })
        assertEquals(
            setOf("boundary_mismatch", "unstable_hub"),
            run.skipped.map { it.type }.toSet(),
            "a withheld detector must be reported, or its absence reads as 'nothing found'",
        )
    }

    /**
     * A repository-wide coverage threshold answered the wrong question. Coverage is a
     * file population; a boundary_mismatch's claim is about two particular files. So
     * a half-declared repo used to admit a pair whose endpoints were both guesses,
     * and withhold a pair whose endpoints were both declared.
     */
    @Test
    fun `a pair resting on guessed folders is dropped even where the repo passes`() {
        // Two real Gradle modules plus two undeclared legacy folders, with equally
        // strong co-change in both pairs. The detector runs, and must report the
        // declared pair while dropping the guessed one — a decision no repository-wide
        // threshold can make, since it admits or withholds both together.
        val head = setOf(
            "app/build.gradle.kts", "app/A.kt",
            "core/build.gradle.kts", "core/B.kt",
            "legacyA/X.kt", "legacyB/Y.kt",
        )
        val context = AnalysisContext(history("app", "core") + history("legacyA", "legacyB"), Boundaries(head), head)
        val run = Analyzer().run(context)
        assertTrue(run.moduleDetection.moduleFindingsEnabled, "two modules are declared")
        val mismatches = run.findings.filter { it.type == "boundary_mismatch" }.flatMap { it.files }.toSet()
        assertTrue("app/A.kt" in mismatches, "the declared pair is reported: $mismatches")
        assertTrue(
            "legacyA/X.kt" !in mismatches && "legacyB/Y.kt" !in mismatches,
            "both endpoints are guessed folders, so that claim is not made: $mismatches",
        )
    }

    @Test
    fun `a declared pair survives a repo full of undeclared files`() {
        // Two declared modules, swamped by 51% fallback files. The old global gate
        // withheld this finding although both of its endpoints have exact provenance.
        val head = setOf(
            "app/build.gradle.kts", "app/A.kt",
            "core/build.gradle.kts", "core/B.kt",
        ) + (1..60).map { "misc$it/file$it.txt" }
        val changes = history("app", "core") +
            (1..60).map { LogicalChange(listOf(commit("m$it", listOf("misc$it/file$it.txt")))) }
        val context = AnalysisContext(changes, Boundaries(head), head)
        val run = Analyzer().run(context)
        assertTrue(run.moduleDetection.coverage < 0.5, "coverage is ${run.moduleDetection.coverage}")
        assertTrue(
            run.findings.any { it.type == "boundary_mismatch" },
            "both endpoints are declared, so the claim stands: ${run.findings.map { it.type }}",
        )
    }
}

/**
 * Some languages declare their unit of code organization by directory instead of by
 * a per-directory build file. A Go repository has exactly one `go.mod`, so build
 * files alone saw one module and every boundary finding was withheld — on the whole
 * ecosystem. The package IS the directory, by the language's own rule.
 */
class LanguagePackageBoundariesTest {
    @Test
    fun `go packages are declared module roots, so a single go-mod repo still has boundaries`() {
        val files = setOf(
            "go.mod", "main.go",
            "pkg/cmd/pr/create.go", "pkg/cmd/pr/create_test.go",
            "pkg/cmd/issue/create.go",
        )
        val boundaries = Boundaries(files)
        assertEquals(ModuleSource.GO_PACKAGE, boundaries.sourceOf("pkg/cmd/pr/create.go"))
        assertEquals("pkg/cmd/pr", boundaries.moduleOf("pkg/cmd/pr/create.go"))
        assertEquals("pkg/cmd/issue", boundaries.moduleOf("pkg/cmd/issue/create.go"))
        val detection = boundaries.detection(files)
        assertEquals(1.0, detection.coverage)
        assertTrue(detection.declaredModuleCount >= 2, "one go.mod must not mean one module")
        assertTrue(ModuleGate.report(detection).moduleFindingsEnabled)
    }

    @Test
    fun `a python package directory is a declared module root`() {
        val files = setOf(
            "pyproject.toml",
            "src/app/__init__.py", "src/app/views.py",
            "src/app/api/__init__.py", "src/app/api/routes.py",
        )
        val boundaries = Boundaries(files)
        assertEquals(ModuleSource.PYTHON_PACKAGE, boundaries.sourceOf("src/app/api/routes.py"))
        assertEquals("src/app/api", boundaries.moduleOf("src/app/api/routes.py"))
        assertEquals("src/app", boundaries.moduleOf("src/app/views.py"))
    }

    @Test
    fun `a directory without the language marker is not a package`() {
        // No __init__.py here, so scripts/ is not a package: it falls back to the
        // root module the build file declares, not to a module of its own.
        val files = setOf("pyproject.toml", "scripts/deploy.py", "src/app/__init__.py")
        val boundaries = Boundaries(files)
        assertEquals(ModuleSource.ROOT_BUILD_FILE, boundaries.sourceOf("scripts/deploy.py"))
        assertEquals("<root>", boundaries.moduleOf("scripts/deploy.py"))
    }

    @Test
    fun `an explicit module root overrides the language default`() {
        val files = setOf("go.mod", "internal/a/x.go", "internal/a/deep/y.go")
        val boundaries = Boundaries(files, moduleRootGlobs = listOf("internal/*"))
        assertEquals(ModuleSource.USER_GLOB, boundaries.sourceOf("internal/a/deep/y.go"))
        assertEquals("internal/a", boundaries.moduleOf("internal/a/deep/y.go"))
    }
}

/**
 * Per-crate `Cargo.toml` files bumped by one release, or per-locale `strings.xml`
 * files translated together, cross module boundaries by process. Reporting them as
 * refactoring candidates — and calling them "interface/implementation pairs" —
 * described the wrong thing.
 */
class VariantSetTest {
    @Test
    fun `the same file name under sibling directories is a variant set`() {
        assertTrue(CouplingKind.siblingVariants("crates/cli/Cargo.toml", "crates/grep/Cargo.toml"))
        assertTrue(CouplingKind.siblingVariants("res/values/strings.xml", "res/values-ja/strings.xml"))
        // Different names, same directory pair: not a variant set.
        assertFalse(CouplingKind.siblingVariants("crates/cli/main.rs", "crates/grep/lib.rs"))
        // Same name but not siblings: app/Foo.kt vs deep/nested/Foo.kt.
        assertFalse(CouplingKind.siblingVariants("app/Foo.kt", "core/nested/Foo.kt"))
        // Same directory: not variants of each other.
        assertFalse(CouplingKind.siblingVariants("a/x.toml", "a/x.toml"))
    }

    @Test
    fun `a variant set is costed as process, not as a refactor`() {
        val estimate = CouplingKind.of(
            "crates/cli/Cargo.toml", "crates/grep/Cargo.toml",
            FileCategory.BUILD, namesRelated = true,
        )
        assertEquals("variant-set", estimate.kind)
        assertEquals("none", estimate.effort, "there is no refactor to do here")
        assertTrue("lockstep" in estimate.note, estimate.note)
    }

    @Test
    fun `a genuine companion pair is still costed as a companion`() {
        val estimate = CouplingKind.of(
            "domain/PaymentRepository.kt", "data/DefaultPaymentRepository.kt",
            FileCategory.SOURCE, namesRelated = true,
        )
        assertEquals("companion", estimate.kind)
    }
}

/**
 * Effort has to describe the work, not the file extensions. A Gradle build script and
 * a version catalog are `jvm` vs `toml`, which the cross-language rule read as an
 * expensive cross-platform design coupling — for two files the build system is designed
 * to change together.
 */
class BuildWiringEffortTest {
    @Test
    fun `two build definitions are costed as build wiring, not as a platform boundary`() {
        val estimate = CouplingKind.of(
            "app/build.gradle.kts", "gradle/libs.versions.toml",
            FileCategory.BUILD, namesRelated = false,
        )
        assertEquals("build-wiring", estimate.kind)
        assertEquals("low", estimate.effort)
    }

    @Test
    fun `a build file paired with production code is not called build wiring`() {
        // categoryOfPair calls this pair "build" because one side is a build file. That
        // must not become the claim "both files are build definitions" — the other side
        // is production code, and this may be the leak the tool exists to find.
        val estimate = CouplingKind.of(
            "app/build.gradle.kts", "core/Pricing.kt",
            FileCategory.BUILD, namesRelated = false,
        )
        assertTrue(estimate.kind != "build-wiring", "was ${estimate.kind}: ${estimate.note}")
    }

    @Test
    fun `a docs-categorised pair with a source endpoint is not a variant set`() {
        // categoryOfPair calls this pair "docs", and the two share a basename under
        // sibling directories — but src/example.py is production code, so "declarative
        // variant set, nothing to do" is false of it.
        val estimate = CouplingKind.of("docs/example.py", "src/example.py", FileCategory.DOCS, namesRelated = true)
        assertTrue(estimate.kind != "variant-set", "was ${estimate.kind}")
    }

    @Test
    fun `a manifest paired with the code it declares is cheap, not a platform boundary`() {
        val estimate = CouplingKind.of("web/package.json", "server/app.py", FileCategory.BUILD, namesRelated = false)
        assertEquals("manifest-and-source", estimate.kind)
        assertEquals("low", estimate.effort)
    }

    @Test
    fun `same-named source peers under sibling directories are not a variant set`() {
        // services/orders/Router.kt and services/payments/Router.kt are architectural
        // peers, possibly duplicated logic. "Expected, effort none" is the wrong verdict.
        assertTrue(CouplingKind.siblingVariants("services/orders/Router.kt", "services/payments/Router.kt"))
        val estimate = CouplingKind.of(
            "services/orders/Router.kt", "services/payments/Router.kt",
            FileCategory.SOURCE, namesRelated = true,
        )
        assertEquals("parallel-implementation", estimate.kind, "possible duplication, not a companion pair")
        assertEquals("medium", estimate.effort)
        assertTrue("duplicated logic" in estimate.note, estimate.note)
    }

    @Test
    fun `a real cross-language source coupling is still expensive`() {
        val estimate = CouplingKind.of(
            "android/Screen.kt", "ios/Screen.swift",
            FileCategory.SOURCE, namesRelated = true,
        )
        assertEquals("cross-language", estimate.kind)
        assertEquals("high", estimate.effort)
    }

    @Test
    fun `a lockstep manifest set outranks the build-wiring reading`() {
        // crates/*/Cargo.toml is both a build pair and a variant set; the variant set is
        // the more specific statement, and it costs nothing rather than a little.
        val estimate = CouplingKind.of(
            "crates/cli/Cargo.toml", "crates/grep/Cargo.toml",
            FileCategory.BUILD, namesRelated = true,
        )
        assertEquals("variant-set", estimate.kind)
        assertEquals("none", estimate.effort)
    }
}

/**
 * A language package is a precise claim, and claiming too much manufactures boundaries.
 * Go's own rule ignores `testdata` and any element starting with `_` or `.`; a package
 * is one directory, never a subtree; and a shell script inside a package directory is
 * not part of the package.
 */
class LanguagePackagePrecisionTest {
    @Test
    fun `go fixture directories are not packages`() {
        val files = setOf(
            "go.mod", "parser/parse.go",
            "parser/testdata/good/input.go", "parser/testdata/bad/input.go",
            "parser/_ignored/x.go", "parser/.hidden/y.go",
        )
        val boundaries = Boundaries(files)
        assertEquals(ModuleSource.GO_PACKAGE, boundaries.sourceOf("parser/parse.go"))
        // The fixtures are not packages of their own, so they all fall back to the module
        // go.mod declares. Two synchronized fixture updates therefore cross no boundary —
        // which is the point: they used to look like two modules changing together.
        val fixtures = listOf(
            "parser/testdata/good/input.go", "parser/testdata/bad/input.go",
            "parser/_ignored/x.go", "parser/.hidden/y.go",
        )
        for (fixture in fixtures) {
            assertFalse(
                boundaries.sourceOf(fixture).declared,
                "$fixture is not part of this repository's structure: got ${boundaries.sourceOf(fixture)}",
            )
        }
    }

    @Test
    fun `vendored dependencies are not packages of the repository`() {
        // A dependency bump touches many vendored files at once; treating each vendored
        // directory as a declared module turns that into cross-module findings.
        val files = setOf("go.mod", "pkg/a/a.go", "vendor/github.com/x/y/y.go", "vendor/modules.txt")
        val boundaries = Boundaries(files)
        assertEquals(ModuleSource.GO_PACKAGE, boundaries.sourceOf("pkg/a/a.go"))
        assertFalse(
            boundaries.sourceOf("vendor/github.com/x/y/y.go").declared,
            "vendored code is not this repository's structure: got ${boundaries.sourceOf("vendor/github.com/x/y/y.go")}",
        )
        assertEquals(
            "vendor", boundaries.moduleOf("vendor/github.com/x/y/y.go"),
            "all vendored files fall into one undeclared bucket, so a dependency bump crosses no boundary",
        )
    }

    @Test
    fun `a go package covers its own directory only, not its subtree`() {
        val files = setOf("go.mod", "pkg/a/a.go", "pkg/a/sub/b.go")
        val boundaries = Boundaries(files)
        assertEquals("pkg/a", boundaries.moduleOf("pkg/a/a.go"))
        assertEquals("pkg/a/sub", boundaries.moduleOf("pkg/a/sub/b.go"), "Go packages are per-directory")
    }

    @Test
    fun `a root go package does not lend its provenance to unrelated files`() {
        // No build file anywhere: main.go at the top level says nothing about a shell
        // script in scripts/. Treating that as declared invented a boundary between them.
        val files = setOf("main.go", "pkg/a/a.go", "scripts/deploy.sh")
        val boundaries = Boundaries(files)
        assertEquals(ModuleSource.GO_PACKAGE, boundaries.sourceOf("main.go"))
        assertFalse(
            boundaries.sourceOf("scripts/deploy.sh").declared,
            "got ${boundaries.sourceOf("scripts/deploy.sh")}",
        )
    }

    @Test
    fun `a go file the go command skips does not become a declared module`() {
        // Paired against the real package this would otherwise read as two declared
        // modules changing together, because it fell back to the root go.mod.
        val files = setOf("go.mod", "parser/parse.go", "parser/_ignored/x.go")
        val boundaries = Boundaries(files)
        assertFalse(boundaries.sourceOf("parser/_ignored/x.go").declared)
        assertTrue(boundaries.sourceOf("parser/parse.go").declared)
    }

    @Test
    fun `a python stub file belongs to its package, not to the root`() {
        val files = setOf("pyproject.toml", "pkg/__init__.py", "pkg/impl.py", "pkg/api.pyi")
        val boundaries = Boundaries(files)
        assertEquals(
            boundaries.moduleOf("pkg/impl.py"), boundaries.moduleOf("pkg/api.pyi"),
            ".pyi is Python; putting the stub in a different module invented a boundary inside one package",
        )
    }

    @Test
    fun `a non-python file inside a python package is not part of it`() {
        val files = setOf("src/app/__init__.py", "src/app/views.py", "src/app/schema.sql")
        val boundaries = Boundaries(files)
        assertEquals(ModuleSource.PYTHON_PACKAGE, boundaries.sourceOf("src/app/views.py"))
        assertFalse(boundaries.sourceOf("src/app/schema.sql").declared)
    }

    @Test
    fun `a real build file at the root still adopts uncovered files`() {
        val files = setOf("pyproject.toml", "scripts/deploy.sh", "src/app/__init__.py")
        val boundaries = Boundaries(files)
        assertEquals(ModuleSource.ROOT_BUILD_FILE, boundaries.sourceOf("scripts/deploy.sh"))
    }
}

/**
 * Every metric is a function of the module partition, so it has to draw on the same
 * declared-only population the findings do. It used to include guessed assignments —
 * so a repository could see `boundary_mismatch` correctly withheld for a legacy pair
 * while `metrics` counted that very pair as a boundary hotspot.
 */
class MetricsProvenanceTest {
    private var t = 0L
    private fun commit(vararg files: String): Commit {
        t += 3600 * 24
        return Commit("h$t", "dev", t, "m", files.toList())
    }

    @Test
    fun `adjusted locality can be recomputed from the published numbers`() {
        // Publishing a rounded expectedLocality broke the documented formula, so a
        // consumer could not reproduce the score it is told how to compute.
        val head = setOf("a/build.gradle.kts", "a/A.kt", "a/A2.kt", "b/build.gradle.kts", "b/B.kt")
        val changes = List(6) { LogicalChange(listOf(commit("a/A.kt", "a/A2.kt"))) } +
            List(6) { LogicalChange(listOf(commit("a/A.kt", "b/B.kt"))) }
        val m = Metrics.compute(AnalysisContext(changes, Boundaries(head), head))
        val expected = m.expectedLocality
        val recomputed = (m.moduleLocality!! - expected) / (1 - expected)
        assertTrue(
            kotlin.math.abs(recomputed - m.adjustedLocality!!) < 1e-9,
            "documented as (moduleLocality - expectedLocality) / (1 - expectedLocality); " +
                "got $recomputed vs ${m.adjustedLocality}",
        )
    }

    @Test
    fun `guessed folders do not become hotspots or modules`() {
        val head = setOf(
            "app/build.gradle.kts", "app/A.kt",
            "core/build.gradle.kts", "core/B.kt",
            "legacyA/X.kt", "legacyB/Y.kt",
        )
        val declaredOnly = List(6) { LogicalChange(listOf(commit("app/A.kt", "core/B.kt"))) }
        val guessedToo = declaredOnly + List(6) { LogicalChange(listOf(commit("legacyA/X.kt", "legacyB/Y.kt"))) }

        val withGuessed = Metrics.compute(AnalysisContext(guessedToo, Boundaries(head), head))
        val withoutGuessed = Metrics.compute(AnalysisContext(declaredOnly, Boundaries(head), head))

        assertEquals(
            withoutGuessed.boundaryHotspots, withGuessed.boundaryHotspots,
            "a guessed-folder pair is not a boundary hotspot",
        )
        assertEquals(
            withoutGuessed.distinctModules, withGuessed.distinctModules,
            "legacyA/legacyB are folder names, not modules",
        )
        assertEquals(
            withoutGuessed.declaredMultiFileUnits, withGuessed.declaredMultiFileUnits,
            "a change touching only guessed files contributes no module evidence",
        )
        // ...while the raw count still reports everything, so the two denominators are
        // visibly different rather than one silently standing in for the other.
        assertTrue(
            withGuessed.multiFileUnits > withGuessed.declaredMultiFileUnits,
            "${withGuessed.multiFileUnits} vs ${withGuessed.declaredMultiFileUnits}",
        )
    }

    @Test
    fun `metrics and the detector report the same hub set`() {
        // Codex's case: a hub whose partners are mostly guessed. Projecting to declared
        // files first turned those units single-file and dropped them, so metrics omitted
        // a hub the detector reported. Both now read one shared predicate.
        val head = setOf("app/build.gradle.kts", "app/H.kt") +
            (1..5).map { "m$it/build.gradle.kts" } + (1..5).map { "m$it/F$it.kt" } +
            (1..15).map { "guessed$it/X$it.txt" }
        val changes = (1..5).map { i -> LogicalChange(listOf(commit("app/H.kt", "m$i/F$i.kt"))) } +
            (1..15).map { i -> LogicalChange(listOf(commit("app/H.kt", "guessed$i/X$i.txt"))) }
        val context = AnalysisContext(changes, Boundaries(head), head)

        val fromDetector = Analyzer().run(context).findings
            .filter { it.type == "unstable_hub" }.flatMap { it.subjects }.toSet()
        val fromMetrics = Metrics.compute(context).hubFiles.toSet()
        assertEquals(fromDetector, fromMetrics, "one predicate, one answer")
        assertTrue("app/H.kt" in fromDetector, "20 multi-file units across 5 declared modules is a hub")
    }

    @Test
    fun `a hub over guessed modules only is reported by neither`() {
        // A hub over guessed folders only: the detector withholds it, so metrics must
        // not list it either. Both now read the same declared-only population.
        val head = setOf("a/H.kt") + (1..8).map { "m$it/F$it.kt" }
        val changes = (1..25).map { i ->
            LogicalChange(listOf(commit("a/H.kt", "m${(i % 8) + 1}/F${(i % 8) + 1}.kt")))
        }
        val context = AnalysisContext(changes, Boundaries(head), head)
        val metrics = Metrics.compute(context)
        val findings = Analyzer().run(context)
        assertTrue(metrics.hubFiles.isEmpty(), "no module here is declared: ${metrics.hubFiles}")
        assertTrue(findings.findings.none { it.type == "unstable_hub" })
    }
}

/** A changelog moving with the code it describes is documentation upkeep, not a design coupling. */
class DocumentationEffortTest {
    @Test
    fun `a docs pairing is not a cross-platform design coupling`() {
        val estimate = CouplingKind.of(
            "CHANGES.rst", "src/flask/app.py",
            FileCategory.DOCS, namesRelated = false,
        )
        assertEquals("documentation", estimate.kind)
        assertEquals("low", estimate.effort)
    }

    @Test
    fun `generated still wins over documentation`() {
        val estimate = CouplingKind.of("api.md", "api.pb.go", FileCategory.GENERATED, namesRelated = true)
        assertEquals("generated", estimate.kind)
    }
}

/**
 * A Python subdirectory without `__init__.py` is not a package of its own; its modules
 * belong to the nearest ancestor that is. Matching a package to exactly one directory
 * sent those files to the root module, so they appeared to cross a boundary with the
 * very package they are part of.
 */
class PythonPackageNestingTest {
    @Test
    fun `a non-package subdirectory belongs to its nearest enclosing package`() {
        val files = setOf(
            "pyproject.toml",
            "src/flask/__init__.py", "src/flask/app.py", "src/flask/testing.py",
            "src/flask/sansio/app.py",
            "src/flask/json/__init__.py", "src/flask/json/provider.py",
        )
        val boundaries = Boundaries(files)
        assertEquals(
            "src/flask", boundaries.moduleOf("src/flask/sansio/app.py"),
            "sansio has no __init__.py, so its modules are part of src/flask",
        )
        assertEquals(
            boundaries.moduleOf("src/flask/testing.py"), boundaries.moduleOf("src/flask/sansio/app.py"),
            "these must not look like two modules changing together",
        )
        // A real subpackage still wins, because the nearest root does.
        assertEquals("src/flask/json", boundaries.moduleOf("src/flask/json/provider.py"))
    }
}

/**
 * Every previous round of coupling-kind fixes produced a new false description, because
 * the rules read file extensions before asking what kind of file each endpoint is. A
 * language difference only means "platform boundary" when both sides are code.
 */
class CouplingKindByCategoryTest {
    @Test
    fun `a config file next to code is not a platform boundary`() {
        val estimate = CouplingKind.of("config/app.yaml", "src/App.kt", FileCategory.CONFIG, namesRelated = false)
        assertTrue(estimate.kind != "cross-language", "was ${estimate.kind}: ${estimate.note}")
        assertEquals("low", estimate.effort)
    }

    @Test
    fun `a build definition and a config file are not manifest-and-source`() {
        val estimate = CouplingKind.of("app/build.gradle.kts", "infra/deploy.yml", FileCategory.BUILD, namesRelated = false)
        assertTrue(estimate.kind != "manifest-and-source", "deploy.yml is not code the manifest declares")
        assertEquals("low", estimate.effort)
    }

    @Test
    fun `a docs and source sibling pair is documentation, not parallel implementation`() {
        val estimate = CouplingKind.of("docs/example.py", "src/example.py", FileCategory.DOCS, namesRelated = true)
        assertEquals("documentation", estimate.kind)
    }

    @Test
    fun `one build-file catalog, so a manifest is never mistaken for a language`() {
        // pyproject.toml was a module root in Boundaries and a *config* file in
        // FileCategory, so this pair came out as "toml vs py, expensive to break".
        for (manifest in listOf("pkg/pyproject.toml", "pkg/setup.py", "pkg/CMakeLists.txt", "pkg/mix.exs")) {
            assertEquals(
                FileCategory.BUILD, FileCategory.of(manifest),
                "$manifest is a module root, so it must be categorised as a build file",
            )
        }
        val estimate = CouplingKind.of("pkg/pyproject.toml", "src/app.py", FileCategory.BUILD, namesRelated = false)
        assertEquals("manifest-and-source", estimate.kind)
    }

    @Test
    fun `two source files in different languages are still a platform boundary`() {
        val estimate = CouplingKind.of("android/Screen.kt", "ios/Screen.swift", FileCategory.SOURCE, namesRelated = true)
        assertEquals("cross-language", estimate.kind)
        assertEquals("high", estimate.effort)
    }
}

/**
 * A dependency tree carries its own manifests, so letting module roots match before the
 * "not our structure" check turned every vendored package into a declared module.
 */
class NotOurStructureOrderingTest {
    @Test
    fun `a dependency's own package json does not make it a module`() {
        val files = setOf("package.json", "src/index.js", "node_modules/pkg/package.json", "node_modules/pkg/index.js")
        val boundaries = Boundaries(files)
        assertFalse(
            boundaries.sourceOf("node_modules/pkg/index.js").declared,
            "got ${boundaries.sourceOf("node_modules/pkg/index.js")}",
        )
    }

    @Test
    fun `a broad module-root glob does not swallow the dependencies under it`() {
        val files = setOf("apps/a/main.js", "apps/a/node_modules/pkg/index.js")
        val boundaries = Boundaries(files, moduleRootGlobs = listOf("apps/*"))
        assertEquals("apps/a", boundaries.moduleOf("apps/a/main.js"))
        assertFalse(boundaries.sourceOf("apps/a/node_modules/pkg/index.js").declared)
    }

    @Test
    fun `an ignored go file is not claimed by a build root`() {
        val files = setOf("app/package.json", "app/main.go", "app/_ignored.go")
        val boundaries = Boundaries(files)
        assertTrue(boundaries.sourceOf("app/main.go").declared)
        assertFalse(
            boundaries.sourceOf("app/_ignored.go").declared,
            "go skips _-prefixed FILE names too: got ${boundaries.sourceOf("app/_ignored.go")}",
        )
    }

    @Test
    fun `vendor is third-party only on evidence`() {
        // go mod vendor writes vendor/modules.txt; that settles it.
        val vendored = Boundaries(setOf("go.mod", "vendor/modules.txt", "vendor/x/y.go", "pkg/a/a.go"))
        assertFalse(vendored.sourceOf("vendor/x/y.go").declared)
        // Without it, a directory called vendor may be perfectly ordinary first-party code.
        val firstParty = Boundaries(setOf("build.gradle.kts", "vendor/Orders.kt", "src/App.kt"))
        assertTrue(
            firstParty.sourceOf("vendor/Orders.kt").declared,
            "rejecting it by name alone would drop first-party code from every claim",
        )
    }

    @Test
    fun `an explicit module root can declare a directory named vendor`() {
        val files = setOf("go.mod", "vendor/modules.txt", "vendor/x/y.go")
        val boundaries = Boundaries(files, moduleRootGlobs = listOf("vendor/x"))
        assertEquals(ModuleSource.USER_GLOB, boundaries.sourceOf("vendor/x/y.go"))
    }

    @Test
    fun `a stub-only python package is a package`() {
        val files = setOf("pyproject.toml", "stubs/foo/__init__.pyi", "stubs/foo/api.pyi", "stubs/bar/__init__.pyi")
        val boundaries = Boundaries(files)
        assertEquals("stubs/foo", boundaries.moduleOf("stubs/foo/api.pyi"))
        assertEquals(ModuleSource.PYTHON_PACKAGE, boundaries.sourceOf("stubs/foo/api.pyi"))
    }
}

/**
 * "Code" is not the same set as `FileRole.SOURCE`. A localized `strings.xml` sits in the
 * SOURCE *category* but is a resource; a `_test.go` file is a different *role* but is
 * still code. Getting either wrong produces a description that is false of the pair.
 */
class CodeVersusDeclarativeTest {
    @Test
    fun `localized resources are a variant set, not parallel implementations`() {
        val a = "app/src/main/res/values/strings.xml"
        val b = "app/src/main/res/values-ja/strings.xml"
        assertTrue(CouplingKind.siblingVariants(a, b))
        val estimate = CouplingKind.of(a, b, FileCategory.ofPair(a, b), namesRelated = true)
        assertEquals("variant-set", estimate.kind, "a translation pair is not duplicated logic")
        assertEquals("none", estimate.effort)
    }

    @Test
    fun `a test file is code`() {
        val estimate = CouplingKind.of(
            "internal/gh/gh.go", "pkg/cmd/config/list/list_test.go",
            FileCategory.SOURCE, namesRelated = false,
        )
        assertEquals("same-language", estimate.kind, "was ${estimate.note}")
    }

    @Test
    fun `a C source and its header are companions, not a platform boundary`() {
        for ((a, b) in listOf("src/foo.c" to "include/foo.h", "a/foo.cpp" to "a/foo.h", "a/foo.mm" to "a/foo.h")) {
            val estimate = CouplingKind.of(a, b, FileCategory.SOURCE, namesRelated = true)
            assertTrue(
                estimate.kind != "cross-language",
                "$a x $b is one native toolchain, not a platform boundary: got ${estimate.kind}",
            )
        }
    }
}

/** A lockfile is a resolved snapshot, so nothing else may describe the pair first. */
class LockfileCouplingTest {
    @Test
    fun `two build files, one of them a lockfile, is not build wiring`() {
        val estimate = CouplingKind.of("web/package-lock.json", "web/package.json", FileCategory.BUILD, namesRelated = true)
        assertEquals("lockfile", estimate.kind, "package-lock.json is not a build definition")
        assertEquals("none", estimate.effort)
    }

    @Test
    fun `a lockfile beside documentation is still a lockfile`() {
        val estimate = CouplingKind.of("CHANGELOG.md", "Cargo.lock", FileCategory.DOCS, namesRelated = false)
        assertEquals("lockfile", estimate.kind)
    }

    @Test
    fun `a lockfile beside code is not a manifest declaring it`() {
        val estimate = CouplingKind.of("web/package-lock.json", "src/App.kt", FileCategory.BUILD, namesRelated = false)
        assertEquals("lockfile", estimate.kind)
    }

    @Test
    fun `resolved-dependency files of other ecosystems count as lockfiles`() {
        for (name in listOf("Package.resolved", "packages.lock.json", "flake.lock", "uv.lock")) {
            assertEquals(FileRole.LOCKFILE, FileRole.of("a/$name"), name)
        }
    }
}

/**
 * One directory can be declared by two languages at once. Deduplicating module roots by
 * directory alone kept whichever was found first and dropped the other, so a mixed
 * Go/Python directory lost its Python package and its `.py` files fell back to a guessed
 * top-level folder.
 */
class MixedLanguageRootTest {
    @Test
    fun `a directory declared by two languages keeps both roots`() {
        val files = setOf(
            "go.mod",
            "services/pkg/main.go", "services/pkg/__init__.py", "services/pkg/app.py",
        )
        val boundaries = Boundaries(files)
        assertEquals(ModuleSource.GO_PACKAGE, boundaries.sourceOf("services/pkg/main.go"))
        assertEquals(
            ModuleSource.PYTHON_PACKAGE, boundaries.sourceOf("services/pkg/app.py"),
            "the Python package must not be discarded by the Go one",
        )
        assertEquals("services/pkg", boundaries.moduleOf("services/pkg/app.py"))
    }
}

/**
 * `moduleRootFileNames` is documented as a subset of `buildNames`, and the two disagreeing
 * is what made `pyproject.toml` a module root in one place and a config file in the other.
 * Documentation did not stop that, so this checks it.
 */
class BuildFileCatalogueTest {
    @Test
    fun `every module-root marker is also categorised as a build file`() {
        for (name in FileCategory.moduleRootFileNames) {
            assertTrue(
                name.lowercase() in FileCategory.buildNames,
                "$name marks a module root but is not in buildNames, so FileCategory.of() will not call it BUILD",
            )
            assertEquals(FileCategory.BUILD, FileCategory.of("some/dir/$name"), name)
        }
    }
}

/**
 * A file with no language is not a platform away from anything. `langKey` used to fall
 * back to the raw extension, so `App.kt` x `.gitignore` came out as
 * "jvm vs gitignore — expensive to break, effort=high" — which would send a reader to
 * deprioritize a coupling that costs nothing to fix.
 */
class UnclassifiedLanguageTest {
    @Test
    fun `a file with no extension is not a language boundary`() {
        // None of these may be called an expensive cross-platform coupling. Makefile and
        // Dockerfile are build definitions, so the manifest rule claims them first and
        // costs them low — which is the better answer. LICENSE has no category of its own
        // and lands on `unclassified`.
        for (other in listOf("LICENSE", "Makefile", "tools/Dockerfile")) {
            val e = CouplingKind.of("app/src/App.kt", other, FileCategory.ofPair("app/src/App.kt", other), namesRelated = false)
            assertTrue(e.kind != "cross-language", "$other has no language: got ${e.kind}/${e.effort}")
            assertTrue(e.effort != "high", "$other must not be costed as an expensive platform coupling: ${e.effort}")
        }
        val license = CouplingKind.of("app/src/App.kt", "LICENSE", FileCategory.SOURCE, namesRelated = false)
        assertEquals("unclassified", license.kind)
        assertEquals("medium", license.effort)
    }

    @Test
    fun `a dotfile with no stem is not a language boundary`() {
        val e = CouplingKind.of("app/src/App.kt", "tools/.gitignore", FileCategory.SOURCE, namesRelated = false)
        assertEquals("unclassified", e.kind)
        assertEquals("medium", e.effort)
        assertTrue("no language to compare" in e.note, e.note)
    }

    @Test
    fun `an unmapped but real extension is still a language boundary`() {
        // .php is not in the table, but it IS a language — this must not be softened.
        assertEquals("php", CouplingKind.langKeyOrNull("legacy/Foo.php"))
        val e = CouplingKind.of("web/Foo.kt", "legacy/Foo.php", FileCategory.SOURCE, namesRelated = true)
        assertEquals("cross-language", e.kind)
        assertEquals("high", e.effort)
    }

    @Test
    fun `a mapped language still resolves normally`() {
        assertEquals("jvm", CouplingKind.langKeyOrNull("a/A.kt"))
        assertEquals("native", CouplingKind.langKeyOrNull("a/a.h"))
        assertEquals(null, CouplingKind.langKeyOrNull("LICENSE"))
        assertEquals(null, CouplingKind.langKeyOrNull("a/.editorconfig"))
    }
}
