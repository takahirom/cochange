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
    private fun detection(declared: Int, fallback: Int, modules: Int) = ModuleDetection(
        totalFiles = declared + fallback,
        bySource = mapOf(ModuleSource.BUILD_FILE to declared, ModuleSource.TOP_LEVEL_DIR to fallback),
        moduleCount = modules,
    )

    @Test
    fun `low coverage withholds module findings instead of merely warning`() {
        val report = ModuleGate.report(detection(declared = 10, fallback = 90, modules = 12))
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
    fun `partial coverage still runs, but says which claims may be folder names`() {
        val report = ModuleGate.report(detection(declared = 70, fallback = 30, modules = 8))
        assertEquals(ModuleGate.PARTIAL, report.trust)
        assertTrue(report.moduleFindingsEnabled)
        assertTrue(report.note.contains("different folders"), report.note)
    }

    @Test
    fun `a single-module repo keeps its clean provenance but has no boundary to cross`() {
        val report = ModuleGate.report(detection(declared = 100, fallback = 0, modules = 1))
        assertEquals(ModuleGate.DECLARED, report.trust, "provenance is perfect; enablement is a separate question")
        assertFalse(report.moduleFindingsEnabled)
        assertTrue(report.note.contains("one module"), report.note)
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
}
