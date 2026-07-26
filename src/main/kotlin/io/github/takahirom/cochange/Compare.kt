package io.github.takahirom.cochange

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * Compares how central each file is (its share of multi-file changes) between a
 * longer baseline window and a shorter recent one. A file that was a big hub
 * historically but is quiet lately should not be ranked next to one that is
 * getting worse right now — this surfaces that difference.
 */
object Compare {
    data class Move(
        val file: String,
        val baselineRate: Double,
        val recentRate: Double,
        val baselineCount: Int,
        val recentCount: Int,
    ) {
        val delta: Double get() = recentRate - baselineRate
    }

    /** file -> count of multi-file changes touching it, and the number of multi-file changes in the window. */
    private fun participation(context: AnalysisContext): Pair<Map<String, Int>, Int> {
        val multiFile = context.changes.filter { it.files.size >= 2 }
        val counts = HashMap<String, Int>()
        for (change in multiFile) {
            for (file in change.files) if (file in context.headFiles) counts.merge(file, 1, Int::plus)
        }
        return counts to multiFile.size
    }

    /**
     * Files whose participation is worth comparing (reached [minCount] in either
     * window), with each window's participation rate.
     */
    fun of(baseline: AnalysisContext, recent: AnalysisContext, minCount: Int = 3): List<Move> {
        val (baseCounts, baseUnits) = participation(baseline)
        val (recentCounts, recentUnits) = participation(recent)
        return (baseCounts.keys + recentCounts.keys).mapNotNull { file ->
            val bc = baseCounts[file] ?: 0
            val rc = recentCounts[file] ?: 0
            if (bc < minCount && rc < minCount) return@mapNotNull null
            Move(
                file = file,
                baselineRate = if (baseUnits > 0) bc.toDouble() / baseUnits else 0.0,
                recentRate = if (recentUnits > 0) rc.toDouble() / recentUnits else 0.0,
                baselineCount = bc,
                recentCount = rc,
            )
        }
    }

    /** A single weekly-trackable summary: how many files moved each way and how much overall. */
    data class Summary(val heating: Int, val cooling: Int, val totalAbsShift: Double)

    fun summarize(moves: List<Move>) = Summary(
        heating = moves.count { it.delta > 0 },
        cooling = moves.count { it.delta < 0 },
        totalAbsShift = moves.sumOf { abs(it.delta) },
    )

    private val json = Json { prettyPrint = true }

    fun encode(
        repo: String, branch: String, baseline: String, recent: String,
        baselineUnits: Int, recentUnits: Int, category: String?, moves: List<Move>,
    ): String {
        val s = summarize(moves)
        return json.encodeToString(
            CompareReport.serializer(),
            CompareReport(
                repo = repo, branch = branch, baseline = baseline, recent = recent,
                baselineUnits = baselineUnits, recentUnits = recentUnits, category = category,
                heating = s.heating, cooling = s.cooling, totalAbsShift = round4(s.totalAbsShift),
                moves = moves.sortedByDescending { abs(it.delta) }.map {
                    MoveJson(it.file, round4(it.baselineRate), round4(it.recentRate), round4(it.delta), it.baselineCount, it.recentCount)
                },
            ),
        )
    }

    private fun round4(v: Double) = kotlin.math.round(v * 10000) / 10000

    @Serializable
    data class CompareReport(
        val repo: String,
        val branch: String,
        val baseline: String,
        val recent: String,
        val baselineUnits: Int,
        val recentUnits: Int,
        val category: String?,
        val heating: Int,
        val cooling: Int,
        val totalAbsShift: Double,
        val moves: List<MoveJson>,
    )

    @Serializable
    data class MoveJson(
        val file: String,
        val baselineRate: Double,
        val recentRate: Double,
        val delta: Double,
        val baselineCount: Int,
        val recentCount: Int,
    )
}
