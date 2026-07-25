package io.github.takahirom.cochange

import kotlin.math.pow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Repository-level indicators computed from the co-change index. All headline
 * scores are shares of change units and read "higher is better". They are
 * meant for trend comparison within one repository under the same options —
 * absolute values are not comparable across repositories.
 *
 * Guardrails: when the module partition is uninformative (one effective
 * module) the module-based scores are reported as null (N/A) instead of a
 * vacuous 100%, and a low-resolution flag is raised below [LOW_RESOLUTION].
 */
data class RepoMetrics(
    /** Share of multi-file change units fully contained in one module. Null when the partition is uninformative. */
    val moduleLocality: Double?,
    /** [moduleLocality] corrected for chance (kappa-style): how much the module structure contributes
     *  beyond what random file placement with the same module sizes would already achieve.
     *  ~0 on a monolith even when raw locality is high. */
    val adjustedLocality: Double?,
    /** Share of multi-file change units not touching any hub file. Null when hubs cannot meaningfully exist (< 6 modules). */
    val hubFreeRate: Double?,
    /** Share of cross-module change units that avoid every recurring hotspot pair. Null when nothing crosses modules. */
    val boundaryIntegrity: Double?,
    /** Activity-weighted effective module count (1 / sum of squared module shares). */
    val effectiveModules: Double,
    val distinctModules: Int,
    /** True when the partition is too coarse for module-based scores to mean much. */
    val lowResolution: Boolean,
    /** Change units touching more than one file — the denominator of [hubFreeRate]. */
    val multiFileUnits: Int,
    /**
     * Multi-file units after dropping files whose module was only guessed, keeping those
     * with two declared files left — because a change touching one declared file and one
     * guessed one says nothing about the module partition. This is the denominator of
     * [moduleLocality] and [adjustedLocality]; [boundaryIntegrity] divides by
     * [crossModuleUnits], the subset of these that span more than one module.
     */
    val declaredMultiFileUnits: Int,
    val crossModuleUnits: Int,
    val localUnits: Int,
    val hubAvoidingUnits: Int,
    /** Hubs the rate counted, including any hidden from [hubFiles] by a role filter. */
    val hubCount: Int,
    val hotspotFreeCrossUnits: Int,
    /** Hub files (participation >= 20 units, >= 5 partner modules), most active first. */
    val hubFiles: List<String>,
    /** Recurring cross-module pairs (support >= 5, confidence >= 0.6). */
    val boundaryHotspots: Int,
    val topHotspot: AnalysisContext.PairStat?,
    /** Window length in years derived from the analyzed commits, for per-year costs. */
    val windowYears: Double,
    /** Locality a random placement would have produced — the baseline [adjustedLocality] corrects against. */
    val expectedLocality: Double,
)

object Metrics {
    /** Effective-module-count threshold below which module scores get a warning. Product policy, versioned with the metric. */
    const val LOW_RESOLUTION = 1.25

    fun compute(context: AnalysisContext): RepoMetrics {
        val boundaries = context.boundaries
        // Every score below is a statement about the module partition, so it may only
        // use files whose module the repository actually declares. Including guessed
        // assignments made these numbers describe directory names while the findings
        // built on the same partition were being withheld for exactly that reason.
        fun declared(path: String) = context.moduleIsDeclared(path)
        val multiFile = context.changes
            .map { change -> change to change.files.filter(::declared) }
            .filter { (_, files) -> files.size >= 2 }
            .map { (_, files) -> files }

        // Activity-weighted effective module count over changed-file incidences.
        val incidences = HashMap<String, Int>()
        var totalIncidences = 0
        for (files in multiFile) {
            for (file in files) {
                incidences.merge(boundaries.moduleOf(file), 1, Int::plus)
                totalIncidences++
            }
        }
        val effectiveModules = if (totalIncidences == 0) 0.0 else {
            1.0 / incidences.values.sumOf { val p = it.toDouble() / totalIncidences; p * p }
        }
        val partitionInformative = effectiveModules > 1.0001

        val crossModule = multiFile.filter { files -> files.map(boundaries::moduleOf).toSet().size > 1 }
        val localUnits = multiFile.size - crossModule.size

        // Chance-corrected locality: expected P(all k files land in one module)
        // under random placement weighted by module activity shares.
        val moduleShares = incidences.values.map { it.toDouble() / totalIncidences }
        val expectedLocal = if (multiFile.isEmpty()) 0.0 else multiFile.sumOf { files ->
            moduleShares.sumOf { p -> p.pow(files.size) }
        } / multiFile.size

        // Hubs come from AnalysisContext, the single definition UnstableHubDetector also
        // uses, so the two can no longer report different hub sets. Note its population
        // is every multi-file unit — NOT the declared-projected `multiFile` above — so
        // the hub-free rate is a share of all multi-file units.
        // Unfiltered for the rate; the displayed list below is filtered. Otherwise
        // `--exclude-role test` moved hubFreeRate, which the tool promises it cannot.
        val hubFiles = context.hubFiles(minParticipation = 20, minModuleSpread = 5)
        val allMultiFile = context.hubStats.multiFileChanges
        // With < 6 modules the >= 5 partner-module predicate is unsatisfiable;
        // a perfect score there would be structural, not architectural.
        val hubsMeaningful = incidences.size >= 6
        val hubAvoiding = allMultiFile.count { change -> change.files.none { it in hubFiles } }

        val hotspots = context.pairs(minTogether = 5)
            // Not filtered by visibility: a hidden hotspot must not raise boundaryIntegrity.
            .filter { declared(it.a) && declared(it.b) }
            .filter { boundaries.moduleOf(it.a) != boundaries.moduleOf(it.b) }
            .filter { it.confidence >= 0.6 }
            .toList()
        val hotspotKeys = hotspots.map { setOf(it.a, it.b) }.toHashSet()
        val hotspotFreeCross = crossModule.count { changeFiles ->
            val files = changeFiles.toList()
            files.indices.none { i ->
                (i + 1 until files.size).any { j -> setOf(files[i], files[j]) in hotspotKeys }
            }
        }

        val epochs = context.changes.flatMap { change -> change.commits.map { it.epochSec } }
        val windowYears = if (epochs.size >= 2) {
            ((epochs.max() - epochs.min()) / (365.25 * 24 * 3600)).coerceAtLeast(1.0 / 365.25)
        } else 1.0

        return RepoMetrics(
            moduleLocality = if (partitionInformative) ratio(localUnits, multiFile.size) else null,
            adjustedLocality = if (partitionInformative && expectedLocal < 0.999) {
                (ratio(localUnits, multiFile.size) - expectedLocal) / (1 - expectedLocal)
            } else null,
            hubFreeRate = if (hubsMeaningful) ratio(hubAvoiding, allMultiFile.size) else null,
            boundaryIntegrity = if (partitionInformative && crossModule.isNotEmpty()) {
                ratio(hotspotFreeCross, crossModule.size)
            } else null,
            effectiveModules = effectiveModules,
            distinctModules = incidences.size,
            lowResolution = effectiveModules < LOW_RESOLUTION,
            declaredMultiFileUnits = multiFile.size,
            multiFileUnits = allMultiFile.size,
            crossModuleUnits = crossModule.size,
            localUnits = localUnits,
            hubAvoidingUnits = hubAvoiding,
            hubCount = hubFiles.size,
            hotspotFreeCrossUnits = hotspotFreeCross,
            // Listed for a reader, so hidden roles are dropped here — the rate above used
            // the full set, which is what keeps --exclude-role from moving a score.
            hubFiles = hubFiles.filter(context::isVisible),
            boundaryHotspots = hotspots.size,
            // Listed, so hidden roles are dropped here — an excluded pair must not have
            // its paths printed. The hotspot COUNT above still includes it.
            topHotspot = hotspots.filter { context.isVisible(it.a) && context.isVisible(it.b) }
                .maxByOrNull { it.together },
            windowYears = windowYears,
            expectedLocality = expectedLocal,
        )
    }

    private fun ratio(n: Int, d: Int) = if (d == 0) 0.0 else n.toDouble() / d

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** Machine-readable form for recording runs over time (dashboards, weekly reviews). */
    fun encode(setup: AnalysisSetup, m: RepoMetrics): String = json.encodeToString(
        MetricsReport.serializer(),
        MetricsReport(
            context = RunContext.of(setup),
            windowYears = m.windowYears,
            multiFileUnits = m.multiFileUnits,
            declaredMultiFileUnits = m.declaredMultiFileUnits,
            crossModuleUnits = m.crossModuleUnits,
            localUnits = m.localUnits,
            hubAvoidingUnits = m.hubAvoidingUnits,
            hubCount = m.hubCount,
            hotspotFreeCrossUnits = m.hotspotFreeCrossUnits,
            // Unrounded: rounding it broke the promise that adjustedLocality can be
            // recomputed as (moduleLocality - expectedLocality) / (1 - expectedLocality).
            expectedLocality = m.expectedLocality,
            effectiveModules = m.effectiveModules,
            distinctModules = m.distinctModules,
            lowResolution = m.lowResolution,
            moduleLocality = m.moduleLocality,
            adjustedLocality = m.adjustedLocality,
            hubFreeRate = m.hubFreeRate,
            boundaryIntegrity = m.boundaryIntegrity,
            hubFiles = m.hubFiles,
            boundaryHotspots = m.boundaryHotspots,
            topHotspot = m.topHotspot?.let { HotspotRef(it.a, it.b, it.together) },
        ),
    )
}

/**
 * Every score here is computed over module assignments, so it depends on the same
 * boundary detection the findings do — `context.moduleDetection` is not decoration.
 * At `trust=guessed` these numbers describe top-level directory names, not modules,
 * and must not be read as "this repo has good structure".
 */
@Serializable
data class MetricsReport(
    /** Pinned conditions, module provenance, warnings, tier meanings — the shared envelope. */
    val context: RunContext,
    val windowYears: Double,
    /** Change units touching more than one file — the denominator of [hubFreeRate]. */
    val multiFileUnits: Int,
    /** Multi-file units restricted to declared modules — the denominator of the locality scores. */
    val declaredMultiFileUnits: Int,
    /** Declared multi-file units that span more than one module — the denominator of [boundaryIntegrity]. */
    val crossModuleUnits: Int,
    /** Numerator of [moduleLocality]: declared multi-file units contained in one module. */
    val localUnits: Int,
    /** Numerator of [hubFreeRate], over [multiFileUnits]. */
    val hubAvoidingUnits: Int,
    /** Hub files the rate was computed against, including any a role filter hides from [hubFiles]. */
    val hubCount: Int,
    /** Numerator of [boundaryIntegrity], over [crossModuleUnits]. */
    val hotspotFreeCrossUnits: Int,
    /**
     * The locality a random file placement would have produced, given each module's share
     * of activity. [adjustedLocality] is `(moduleLocality - this) / (1 - this)`, which a
     * consumer could not reproduce while this was unpublished.
     */
    val expectedLocality: Double,
    val effectiveModules: Double,
    val distinctModules: Int,
    val lowResolution: Boolean,
    val moduleLocality: Double?,
    val adjustedLocality: Double?,
    val hubFreeRate: Double?,
    val boundaryIntegrity: Double?,
    val hubFiles: List<String>,
    val boundaryHotspots: Int,
    val topHotspot: HotspotRef?,
    /** Derived: every score is a function of the module partition, not a raw count. */
    val tier: String = EvidenceTier.DERIVED,
)

@Serializable
data class HotspotRef(val a: String, val b: String, val together: Int)
