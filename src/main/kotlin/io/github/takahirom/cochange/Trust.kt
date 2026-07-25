package io.github.takahirom.cochange

import kotlinx.serialization.Serializable

/**
 * How much weight a line of output can carry. cochange mixes three very
 * different kinds of statement, and reading them all as equally solid is the
 * fastest way to act on a false positive:
 *
 * - [EVIDENCE] — counted directly from commits: which files appeared in the
 *   same change, how often. Wrong only if the history was read wrong.
 * - [DERIVED] — structure inferred from the repository: modules, categories,
 *   roles, clusters, change units. Best-effort, and each carries provenance.
 * - [INTERPRETATION] — what a coupling might mean and what it might cost:
 *   findings, impact, effort. Review candidates, not conclusions.
 */
object EvidenceTier {
    const val EVIDENCE = "evidence"
    const val DERIVED = "derived"
    const val INTERPRETATION = "interpretation"
}

/**
 * Provenance and coverage of module detection, plus the resulting decision:
 * whether findings that compare modules are allowed to run at all.
 *
 * `Boundaries.moduleOf` always returns something, so a repository whose build
 * system cochange doesn't recognize still produces module-looking output — from
 * top-level directory names. Reporting coverage is not enough on its own: the
 * gate exists so that output is withheld rather than merely footnoted.
 */
@Serializable
data class ModuleDetectionReport(
    /** Declared signals actually used, e.g. ["nearest directory with a build file"]. Empty when none applied. */
    val methods: List<String>,
    val coverage: Double,
    val moduleCount: Int,
    /** Modules backed by a declared root. Two of these are what a boundary claim needs. */
    val declaredModuleCount: Int,
    val declaredFiles: Int,
    val fallbackFiles: Int,
    val totalFiles: Int,
    /** declared | partial | guessed */
    val trust: String,
    /** False when [ModuleGate] withheld module-comparing findings for this repository. */
    val moduleFindingsEnabled: Boolean,
    val note: String,
    val tier: String = EvidenceTier.DERIVED,
)

/**
 * Decides whether module-comparing findings are possible in this repository at
 * all, and describes how trustworthy the detection is. Raw evidence (pair counts,
 * clusters, metrics) is never gated — only findings that assert something *about
 * module boundaries*.
 *
 * Eligibility is deliberately NOT a repository-wide coverage threshold. Coverage
 * is a file population, while a finding's claim concerns two particular files: a
 * 50%-coverage repo would have admitted a pair whose two endpoints were both
 * guessed folders, and withheld a pair whose endpoints were both declared. So the
 * repo-level question is only "are there at least two *declared* modules for
 * anything to cross", and each finding then checks the provenance of its own
 * endpoints via [AnalysisContext.moduleIsDeclared].
 */
object ModuleGate {
    /** Below this share of declared coverage, [trust] is reported as `guessed`. */
    const val MIN_COVERAGE = 0.5

    /** At or above this share, detection is treated as reliable with no caveat. */
    const val GOOD_COVERAGE = 0.9

    const val DECLARED = "declared"
    const val PARTIAL = "partial"
    const val GUESSED = "guessed"

    fun report(detection: ModuleDetection): ModuleDetectionReport {
        val coverage = detection.coverage
        val fallback = detection.totalFiles - detection.declaredFiles
        val hint = "pass --module-root '<glob>' to declare the boundaries of this repository's layout"
        val hintSentence = hint.replaceFirstChar { it.uppercase() } + "."
        // Trust describes provenance quality across the repository. Eligibility is a
        // separate, narrower question: are there two declared modules for a boundary
        // to exist between. A clean single-module repo has perfect provenance and
        // still has nothing for a boundary finding to say.
        val trust = when {
            coverage >= GOOD_COVERAGE -> DECLARED
            coverage >= MIN_COVERAGE -> PARTIAL
            else -> GUESSED
        }
        val enabled = detection.declaredModuleCount >= 2
        val note = when {
            !enabled && detection.declaredFiles == 0 ->
                "No module root was detected at all, so every \"module\" here is a top-level directory name — " +
                    "a guess, not a boundary. Module-comparing findings are withheld rather than guessed. $hintSentence"
            !enabled ->
                "Only ${detection.declaredModuleCount} module was declared by the repository itself, so no pair can " +
                    "cross a boundary cochange can vouch for. If this repository does have modules cochange didn't " +
                    "detect, $hint."
            trust == DECLARED ->
                "${pct(coverage)} of files resolve to a module root the repository itself declares, across " +
                    "${detection.declaredModuleCount} declared modules."
            else ->
                "${pct(coverage)} of files sit under a declared module root (${detection.declaredModuleCount} declared " +
                    "modules); $fallback fell back to a top-level directory name. Findings are reported only for files " +
                    "on both sides of a declared boundary — a pair resting on a guessed folder is withheld " +
                    "individually, not counted here. $hintSentence"
        }
        return ModuleDetectionReport(
            methods = detection.methods.map { it.label },
            coverage = round2(coverage),
            moduleCount = detection.moduleCount,
            declaredModuleCount = detection.declaredModuleCount,
            declaredFiles = detection.declaredFiles,
            fallbackFiles = fallback,
            totalFiles = detection.totalFiles,
            trust = trust,
            moduleFindingsEnabled = enabled,
            note = note,
        )
    }
}

/** A detector that did not run, and why — so a missing finding type is never read as "nothing found". */
@Serializable
data class SkippedDetector(
    val type: String,
    val reason: String,
)
