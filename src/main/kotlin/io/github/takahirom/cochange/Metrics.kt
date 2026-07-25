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
    val multiFileUnits: Int,
    val crossModuleUnits: Int,
    val localUnits: Int,
    val hubAvoidingUnits: Int,
    val hotspotFreeCrossUnits: Int,
    /** Hub files (participation >= 20 units, >= 5 partner modules), most active first. */
    val hubFiles: List<String>,
    /** Recurring cross-module pairs (support >= 5, confidence >= 0.6). */
    val boundaryHotspots: Int,
    val topHotspot: AnalysisContext.PairStat?,
    /** Window length in years derived from the analyzed commits, for per-year costs. */
    val windowYears: Double,
)

object Metrics {
    /** Effective-module-count threshold below which module scores get a warning. Product policy, versioned with the metric. */
    const val LOW_RESOLUTION = 1.25

    fun compute(context: AnalysisContext): RepoMetrics {
        val boundaries = context.boundaries
        val multiFile = context.changes.filter { it.files.size >= 2 }

        // Activity-weighted effective module count over changed-file incidences.
        val incidences = HashMap<String, Int>()
        var totalIncidences = 0
        for (change in multiFile) {
            for (file in change.files) {
                incidences.merge(boundaries.moduleOf(file), 1, Int::plus)
                totalIncidences++
            }
        }
        val effectiveModules = if (totalIncidences == 0) 0.0 else {
            1.0 / incidences.values.sumOf { val p = it.toDouble() / totalIncidences; p * p }
        }
        val partitionInformative = effectiveModules > 1.0001

        val crossModule = multiFile.filter { change -> change.files.map(boundaries::moduleOf).toSet().size > 1 }
        val localUnits = multiFile.size - crossModule.size

        // Chance-corrected locality: expected P(all k files land in one module)
        // under random placement weighted by module activity shares.
        val moduleShares = incidences.values.map { it.toDouble() / totalIncidences }
        val expectedLocal = if (multiFile.isEmpty()) 0.0 else multiFile.sumOf { change ->
            moduleShares.sumOf { p -> p.pow(change.files.size) }
        } / multiFile.size

        // Hubs: same predicate as UnstableHubDetector's defaults.
        val participation = HashMap<String, Int>()
        val partnerModules = HashMap<String, MutableSet<String>>()
        for (change in multiFile) {
            val modules = change.files.map(boundaries::moduleOf).toSet()
            for (file in change.files) {
                participation.merge(file, 1, Int::plus)
                partnerModules.getOrPut(file) { HashSet() }.addAll(modules - boundaries.moduleOf(file))
            }
        }
        val hubFiles = participation.filter { (file, count) ->
            context.isVisible(file) && count >= 20 && (partnerModules[file]?.size ?: 0) >= 5
        }.keys.sortedByDescending { participation[it] }
        // With < 6 modules the >= 5 partner-module predicate is unsatisfiable;
        // a perfect score there would be structural, not architectural.
        val hubsMeaningful = incidences.size >= 6
        val hubAvoiding = multiFile.count { change -> change.files.none { it in hubFiles } }

        val hotspots = context.pairs(minTogether = 5)
            .filter { context.isVisible(it.a) && context.isVisible(it.b) }
            .filter { boundaries.moduleOf(it.a) != boundaries.moduleOf(it.b) }
            .filter { it.confidence >= 0.6 }
            .toList()
        val hotspotKeys = hotspots.map { setOf(it.a, it.b) }.toHashSet()
        val hotspotFreeCross = crossModule.count { change ->
            val files = change.files.toList()
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
            hubFreeRate = if (hubsMeaningful) ratio(hubAvoiding, multiFile.size) else null,
            boundaryIntegrity = if (partitionInformative && crossModule.isNotEmpty()) {
                ratio(hotspotFreeCross, crossModule.size)
            } else null,
            effectiveModules = effectiveModules,
            distinctModules = incidences.size,
            lowResolution = effectiveModules < LOW_RESOLUTION,
            multiFileUnits = multiFile.size,
            crossModuleUnits = crossModule.size,
            localUnits = localUnits,
            hubAvoidingUnits = hubAvoiding,
            hotspotFreeCrossUnits = hotspotFreeCross,
            hubFiles = hubFiles,
            boundaryHotspots = hotspots.size,
            topHotspot = hotspots.maxByOrNull { it.together },
            windowYears = windowYears,
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
    val multiFileUnits: Int,
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
