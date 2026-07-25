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

    /**
     * One window's denominators. Both are reported because they differ: rates are
     * a share of [multiFileChanges], while [logicalChanges] is the window's total
     * activity. Printing the latter next to a rate computed from the former made
     * every percentage look inconsistent with the numbers beside it.
     */
    @Serializable
    data class Window(
        val requested: String,
        /** The window's start as an absolute instant, so a reader knows what "30d" resolved to. */
        val resolvedSince: String?,
        val logicalChanges: Int,
        /** The rate denominator: change units touching more than one file. */
        val multiFileChanges: Int,
    )

    /** file -> count of multi-file changes touching it, and the number of multi-file changes in the window. */
    private fun participation(context: AnalysisContext): Pair<Map<String, Int>, Int> {
        // The denominator is every multi-file unit, and participation is counted for every
        // file — `--exclude-role` is a view filter, so it must not move a rate. Hidden
        // files are dropped from the LISTING in Compare.of instead.
        val multiFile = context.changes.filter { it.files.size >= 2 }
        val counts = HashMap<String, Int>()
        for (change in multiFile) {
            for (file in change.files) if (file in context.headFiles) counts.merge(file, 1, Int::plus)
        }
        return counts to multiFile.size
    }

    /**
     * The comparison and the exact denominators it was computed against.
     *
     * [summary] is computed over every mover, including those a role filter hides, so
     * `--exclude-role test` changes what is listed and never the trend number. [moves] is
     * the listing.
     */
    data class Comparison(
        val moves: List<Move>,
        val summary: Summary,
        val baselineMultiFile: Int,
        val recentMultiFile: Int,
    )

    /**
     * Files whose participation is worth comparing — those that reached [minCount] in
     * either window — with each window's participation rate.
     *
     * [inScope] selects which files the comparison is ABOUT (`--category`), as opposed to
     * which are hidden from the listing (`--exclude-role`). The distinction matters for
     * [Comparison.summary]: a category is a deliberate scoping of the question, so the
     * trend number must honour it, while a role filter is a view and must not move it.
     * Filtering after summarising reported `heating=1` for a file the caller had excluded.
     */
    fun of(
        baseline: AnalysisContext,
        recent: AnalysisContext,
        minCount: Int = 3,
        inScope: (String) -> Boolean = { true },
    ): Comparison {
        val (baseCounts, baseUnits) = participation(baseline)
        val (recentCounts, recentUnits) = participation(recent)
        // Rates came from the full population above; hidden roles are dropped here, where
        // we choose what to list.
        val allMoves = (baseCounts.keys + recentCounts.keys)
            .filter(inScope)
            .mapNotNull { file ->
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
        // The summary describes the whole window; hidden roles are dropped from the
        // listing only. Aggregating after filtering moved heating/cooling and meanAbsShift.
        val moves = allMoves.filter { recent.isVisible(it.file) || baseline.isVisible(it.file) }
        return Comparison(moves, summarize(allMoves), baseUnits, recentUnits)
    }

    /**
     * A weekly-trackable summary over every mover in scope — including any a role filter
     * hides from the listing, so the trend number does not move when the view changes.
     * [files] is the count it was divided by. [totalAbsShift] grows with that count, so it
     * is only comparable across runs with the same `--min-count` and a similar repository
     * size; [meanAbsShift] is the per-mover figure to trend.
     */
    data class Summary(
        val heating: Int,
        val cooling: Int,
        val totalAbsShift: Double,
        val meanAbsShift: Double,
        /** Movers the summary was computed over — every one in scope, including any the listing hides. */
        val files: Int,
    )

    fun summarize(moves: List<Move>): Summary {
        val total = moves.sumOf { abs(it.delta) }
        return Summary(
            heating = moves.count { it.delta > 0 },
            cooling = moves.count { it.delta < 0 },
            totalAbsShift = total,
            meanAbsShift = if (moves.isEmpty()) 0.0 else total / moves.size,
            files = moves.size,
        )
    }

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun encode(
        context: RunContext, minCount: Int, category: String?,
        baseline: Window, recent: Window, comparison: Comparison,
        recentWindowAloneWouldUse: String,
    ): String {
        val moves = comparison.moves
        val s = comparison.summary
        return json.encodeToString(
            CompareReport.serializer(),
            CompareReport(
                context = context, minCount = minCount, category = category,
                baseline = baseline, recent = recent,
                recentWindowAloneWouldUse = recentWindowAloneWouldUse,
                recentIsInsideBaseline = recentIsInsideBaseline(baseline, recent),
                heating = s.heating, cooling = s.cooling,
                totalAbsShift = round4(s.totalAbsShift), meanAbsShift = round4(s.meanAbsShift),
                summarizedFiles = s.files,
                moves = moves.sortedByDescending { abs(it.delta) }.map {
                    MoveJson(it.file, round4(it.baselineRate), round4(it.recentRate), round4(it.delta), it.baselineCount, it.recentCount)
                },
            ),
        )
    }

    /**
     * True when the recent window is nested inside the baseline — the intended usage
     * (`--baseline 180d --recent 30d`). False means the two were passed the wrong way
     * round, and every "recent" number is the wider sample.
     *
     * Note the two windows ALWAYS overlap: both end at the same HEAD, so one is always
     * a sub-range of the other. That is why this reports nesting rather than overlap —
     * a previous `windowsOverlap` field was false for a swapped pair that did in fact
     * overlap. The samples are never independent, so a shift is a change in *share*,
     * not a before/after difference.
     */
    fun recentIsInsideBaseline(baseline: Window, recent: Window): Boolean {
        val b = baseline.resolvedSince ?: return true
        val r = recent.resolvedSince ?: return true
        return b <= r
    }

    private fun round4(v: Double) = kotlin.math.round(v * 10000) / 10000

    @Serializable
    data class CompareReport(
        /**
         * The shared envelope, describing the BASELINE window's setup — including the
         * single change unit both windows were counted in (`context.changeUnit`).
         */
        val context: RunContext,
        /** Minimum participation in either window for a file to be listed — the summary scales with it. */
        val minCount: Int,
        val category: String?,
        val baseline: Window,
        val recent: Window,
        /**
         * What `auto` would have chosen for the recent window on its own. When it differs
         * from `context.changeUnit`, the history's shape changed mid-window: rates can move
         * because the granularity fits the recent window worse, not because a file
         * became more central.
         */
        val recentWindowAloneWouldUse: String,
        /**
         * True when the recent window is nested inside the baseline. The two always
         * overlap — both end at HEAD — so a shift is a change in share, never a
         * before/after difference between independent samples.
         */
        val recentIsInsideBaseline: Boolean,
        val heating: Int,
        val cooling: Int,
        /** Summed |delta| across every mover in scope. Scales with [summarizedFiles]. */
        val totalAbsShift: Double,
        /** Per-mover mean |delta| — the figure to trend across runs. */
        val meanAbsShift: Double,
        /** Movers the two figures above were computed over; `moves` may be shorter if a role is hidden. */
        val summarizedFiles: Int,
        val moves: List<MoveJson>,
        /**
         * Derived, not evidence: every rate is a share of change units (a derived
         * grouping) and the report also carries inferred metadata such as the resolved
         * unit and window nesting. The counts inside each move are the evidence.
         */
        val tier: String = EvidenceTier.DERIVED,
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
