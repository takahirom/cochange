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
    val tiers: Map<String, String> = TIER_MEANINGS,
) {
    companion object {
        val TIER_MEANINGS = mapOf(
            EvidenceTier.EVIDENCE to "counted from commits; wrong only if the history was read wrong",
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
        )
    }
}

/** One co-change pair as evidence: counted numbers plus the derived module labels. */
@Serializable
data class PairReport(
    val a: String,
    val b: String,
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
    val nameSimilarity: Double,
    val category: String,
    val moduleA: String,
    val moduleB: String,
    val sameModule: Boolean,
    val tier: String = EvidenceTier.EVIDENCE,
)

@Serializable
data class PairsReport(val context: RunContext, val minSupport: Int, val pairs: List<PairReport>)

/** One cluster: derived, because it depends on the edge thresholds and on family collapsing. */
@Serializable
data class ClusterReport(
    val index: Int,
    val files: List<String>,
    val modules: List<String>,
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
 * One supporting change, readable without shelling out to git: what it was and
 * how much of it landed in the files this finding is about. Churn is empty for a
 * merge commit, which has no numstat of its own.
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
        together = p.together,
        changesA = p.countA,
        changesB = p.countB,
        confidence = round2(p.confidence),
        reverse = round2(p.reverse),
        jaccard = round2(p.jaccard),
        evidenceStrength = round2(Surprise.evidenceStrength(p.together, p.countA, p.countB)),
        nameSimilarity = round2(Surprise.nameSimilarity(p.a, p.b)),
        category = context.categoryOfPair(p.a, p.b),
        moduleA = modA,
        moduleB = modB,
        sameModule = modA == modB,
    )
}
