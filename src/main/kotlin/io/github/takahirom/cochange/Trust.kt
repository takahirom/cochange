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
 * Decides whether this repository's module detection is good enough to base
 * findings on. Raw evidence (pair counts, clusters, metrics) is never gated —
 * only the findings that assert something *about module boundaries*.
 */
object ModuleGate {
    /** Below this share of files resolved from a declared boundary, module findings are withheld. */
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
        // Trust describes provenance quality; enablement additionally requires that
        // there be more than one module for a boundary to be crossed at all. The two
        // are separate: a clean single-module repo has perfect provenance and still
        // has nothing for a boundary finding to say.
        val trust = when {
            coverage >= GOOD_COVERAGE -> DECLARED
            coverage >= MIN_COVERAGE -> PARTIAL
            else -> GUESSED
        }
        val note = when {
            detection.moduleCount < 2 ->
                "Only one module was resolved, so no pair can cross a module boundary. " +
                    "If this repository does have modules cochange didn't detect, $hint."
            trust == GUESSED ->
                "Only ${pct(coverage)} of files sit under a declared module root; the rest were bucketed by " +
                    "top-level directory name, which is a guess, not a boundary. Module-comparing findings are " +
                    "withheld rather than guessed. $hintSentence"
            trust == PARTIAL ->
                "${pct(coverage)} of files sit under a declared module root; $fallback fell back to a top-level " +
                    "directory name, so some \"different modules\" claims may just be different folders. $hintSentence"
            else -> "${pct(coverage)} of files resolve to a module root the repository itself declares."
        }
        val enabled = trust != GUESSED && detection.moduleCount >= 2
        return ModuleDetectionReport(
            methods = detection.methods.map { it.label },
            coverage = round2(coverage),
            moduleCount = detection.moduleCount,
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
