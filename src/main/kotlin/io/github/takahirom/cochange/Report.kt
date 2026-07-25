package io.github.takahirom.cochange

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The header every machine-readable output carries: what was analyzed, under
 * which pinned conditions, and how much the derived structure can be trusted.
 *
 * Without it each command's JSON was a bare result — a consumer could not tell
 * whether two files it was comparing came from the same window, whether the
 * clone was shallow, or whether "module" meant a build file or a folder name.
 */
@Serializable
data class RunContext(
    val schemaVersion: Int = SCHEMA_VERSION,
    val repo: String,
    val headCommit: String,
    val shallow: Boolean,
    /** What the user asked for, including relative values like "1 year ago". */
    val requestedOptions: AnalysisOptions,
    /** The same conditions pinned to what they resolved to — replay these, not the request. */
    val options: AnalysisOptions,
    val changeUnit: String,
    val changeUnitReason: String,
    val logicalChanges: Int,
    val moduleDetection: ModuleDetectionReport,
    val hiddenByRole: Map<String, Int> = emptyMap(),
    /** Read this before the numbers: a `warning` here means they are not quotable yet. */
    val warnings: List<AnalysisWarning> = emptyList(),
    val tiers: Map<String, String> = TIER_MEANINGS,
) {
    companion object {
        val TIER_MEANINGS = mapOf(
            EvidenceTier.EVIDENCE to "counted from the history, as grouped into change units (see changeUnit, which is itself derived); wrong only if the history was read wrong",
            EvidenceTier.DERIVED to "structure inferred from the repository; best-effort, carries provenance",
            EvidenceTier.INTERPRETATION to "what a coupling might mean or cost; a review candidate, not a measurement",
        )

        fun of(setup: AnalysisSetup) = RunContext(
            repo = setup.repo.path,
            headCommit = setup.headCommit,
            shallow = setup.shallow,
            requestedOptions = setup.options,
            options = setup.resolvedOptions,
            changeUnit = setup.changeUnitName,
            changeUnitReason = setup.changeUnitReason,
            logicalChanges = setup.changes.size,
            moduleDetection = ModuleGate.report(setup.context.moduleDetection),
            hiddenByRole = setup.context.hiddenByRole,
            warnings = setup.warnings,
        )
    }
}

/**
 * One co-change pair, with the counted numbers separated from the structure
 * cochange inferred about the two files.
 *
 * They used to sit side by side under a single `tier: "evidence"`, so a guessed
 * `moduleA: "src"` looked exactly as solid as `together: 14`.
 */
@Serializable
data class PairReport(
    val a: String,
    val b: String,
    val evidence: PairEvidence,
    val derived: PairDerived,
)

/** Counted from commits, plus arithmetic on those counts. Nothing inferred. */
@Serializable
data class PairEvidence(
    val together: Int,
    val changesA: Int,
    val changesB: Int,
    /** P(other | rarer) — the stronger direction. */
    val confidence: Double,
    /** P(rarer | other) — the weaker direction; a low value means one side is just widely shared. */
    val reverse: Double,
    val jaccard: Double,
    /** Sample-corrected strength: what separates 20-of-25 from 5-of-5, which both score 1.0 on ratio alone. */
    val evidenceStrength: Double,
    val tier: String = EvidenceTier.EVIDENCE,
)

/**
 * Inferred about the pair: which module each file belongs to, whether that module
 * was declared by the repository or guessed from a directory name, the category,
 * and how much the two names alone already predicted the coupling.
 */
@Serializable
data class PairDerived(
    val category: String,
    val moduleA: String,
    val moduleB: String,
    /** False when [moduleA] is a top-level directory name rather than a declared root. */
    val moduleADeclared: Boolean,
    val moduleBDeclared: Boolean,
    val sameModule: Boolean,
    /** 0..1 token overlap of the two basenames. High means the names predicted this. */
    val nameSimilarity: Double,
    val tier: String = EvidenceTier.DERIVED,
)

@Serializable
data class PairsReport(val context: RunContext, val minSupport: Int, val pairs: List<PairReport>)

/** One cluster: derived, because it depends on the edge thresholds and on family collapsing. */
@Serializable
data class ClusterReport(
    val index: Int,
    /** The files shown. Excluded roles are omitted; see [hiddenFiles]. */
    val files: List<String>,
    /** Members in total, including any omitted — what [strongPairs] and [pairSupportVolume] describe. */
    val fileCount: Int,
    /** Members omitted by `--exclude-role`; the counts still include them. */
    val hiddenFiles: Int,
    val modules: List<String>,
    /**
     * The subset of [modules] backed by a declared root. A cluster used to publish bare
     * module names, so a consumer could not tell `app` (a Gradle module) from `legacy`
     * (a folder name), and repository-wide coverage cannot answer that per cluster.
     */
    val declaredModules: List<String>,
    /**
     * The cluster's files whose module was only guessed. Needed because one module label
     * can have mixed provenance: with a root `main.go` and a `README.md` and no build
     * file, both resolve to `<root>` while only the Go file's provenance is declared.
     */
    val guessedFiles: List<String>,
    val strongPairs: Int,
    /** Sum of pair supports, so a file in n pairs contributes n times — a ranking weight, not a count. */
    val pairSupportVolume: Long,
    /** The cluster's strongest edge, as a pair — how the cluster earned its ranking. */
    val strongest: ClusterEdgeReport?,
    /** Same-basename sibling sets collapsed into one representative, keyed by representative. */
    val collapsedFamilies: Map<String, List<String>> = emptyMap(),
    val tier: String = EvidenceTier.DERIVED,
)

/** A cluster edge: the pair and the symmetric similarity the clustering used. */
@Serializable
data class ClusterEdgeReport(
    val a: String,
    val b: String,
    val together: Int,
    val jaccard: Double,
    val tier: String = EvidenceTier.EVIDENCE,
)

/**
 * One supporting change, readable without shelling out to git: what it was and how much
 * of it landed in the files this finding is about. A merge commit's churn is its
 * first-parent diff — the same thing the `merge` change unit counted.
 */
@Serializable
data class CommitSummary(
    val hash: String,
    val date: String,
    val author: String,
    val subject: String,
    /** Lines added + deleted, per file, restricted to the finding's files. */
    val churn: Map<String, Int> = emptyMap(),
    val tier: String = EvidenceTier.EVIDENCE,
)

/**
 * One supporting change unit with its commits resolved. [filesTouched] is the
 * finding's own files that this unit touched, across all of its commits — the
 * direct answer to "did both sides really move here", which reading a single
 * commit cannot give.
 */
@Serializable
data class SupportingChangeReport(
    val hashes: List<String>,
    val commits: List<CommitSummary>,
    val filesTouched: List<String>,
    val tier: String = EvidenceTier.EVIDENCE,
)

/**
 * `inspect` output: the finding plus the run it came from. A bare finding gave a
 * consumer no way to tell which window produced it, whether the clone was
 * shallow, or whether the module names in its summary were real module roots.
 */
@Serializable
data class InspectReport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val analysis: String,
    val repo: String,
    val branch: String,
    val headCommit: String,
    val shallow: Boolean,
    val changeUnit: String,
    val analyzedCommits: Int,
    val logicalChanges: Int,
    val requestedOptions: AnalysisOptions? = null,
    val options: AnalysisOptions? = null,
    val moduleDetection: ModuleDetectionReport? = null,
    val skippedDetectors: List<SkippedDetector> = emptyList(),
    val hiddenByRole: Map<String, Int> = emptyMap(),
    /**
     * The saved run's caveats, carried through: a finding read on its own says nothing
     * about whether the window that produced it was valid.
     */
    val warnings: List<AnalysisWarning> = emptyList(),
    /** True when HEAD has moved since the snapshot was taken. */
    val stale: Boolean = false,
    val finding: Finding,
    /**
     * [Finding.detail]'s supporting change units, resolved against the repository when
     * it is reachable — grouped by unit, because "did both files change here" is a
     * question about the unit, and an author-window unit can spread the two sides
     * across separate commits.
     */
    val supportingChanges: List<SupportingChangeReport> = emptyList(),
    val tiers: Map<String, String> = RunContext.TIER_MEANINGS,
)

@Serializable
data class ClustersReport(
    val context: RunContext,
    val minSupport: Int,
    val minJaccard: Double,
    val clusters: List<ClusterReport>,
)

// encodeDefaults so schemaVersion and every `tier` are always present: a consumer
// must never have to infer the contract version from a missing field.
internal val reportJson = Json { prettyPrint = true; encodeDefaults = true }

internal fun pairReport(p: AnalysisContext.PairStat, context: AnalysisContext): PairReport {
    val modA = context.boundaries.moduleOf(p.a)
    val modB = context.boundaries.moduleOf(p.b)
    return PairReport(
        a = p.a,
        b = p.b,
        evidence = PairEvidence(
            together = p.together,
            changesA = p.countA,
            changesB = p.countB,
            confidence = round2(p.confidence),
            reverse = round2(p.reverse),
            jaccard = round2(p.jaccard),
            evidenceStrength = round2(Surprise.evidenceStrength(p.together, p.countA, p.countB)),
        ),
        derived = PairDerived(
            category = context.categoryOfPair(p.a, p.b),
            moduleA = modA,
            moduleB = modB,
            moduleADeclared = context.moduleIsDeclared(p.a),
            moduleBDeclared = context.moduleIsDeclared(p.b),
            sameModule = modA == modB,
            nameSimilarity = round2(Surprise.nameSimilarity(p.a, p.b)),
        ),
    )
}
