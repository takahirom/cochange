package io.github.takahirom.cochange

fun defaultDetectors(minSupport: Int = 5, minConfidence: Double = 0.6): List<FindingDetector> = listOf(
    BoundaryMismatchDetector(minSupport = minSupport, minConfidence = minConfidence),
    UnstableHubDetector(),
    SplitCandidateDetector(minSupport = minSupport),
)

class Analyzer(
    private val detectors: List<FindingDetector>,
) {
    constructor(minSupport: Int = 5, minConfidence: Double = 0.6) : this(defaultDetectors(minSupport, minConfidence))

    fun analyze(
        changes: List<LogicalChange>,
        boundaries: Boundaries,
        headFiles: Set<String>,
    ): List<Finding> {
        val context = AnalysisContext(changes, boundaries, headFiles)
        return detectors.flatMap { it.detect(context) }
            .sortedBy { FileCategory.priority.indexOf(it.category) }
            .mapIndexed { i, f -> f.copy(id = "finding-${i + 1}") }
    }
}

internal fun pct(v: Double) = "${(v * 100).toInt()}%"
internal fun round2(v: Double) = kotlin.math.round(v * 100) / 100

/**
 * Finds file pairs in different modules that keep changing together: the
 * module boundary and the actual change boundary disagree.
 */
class BoundaryMismatchDetector(
    private val minSupport: Int = 5,
    private val minConfidence: Double = 0.6,
    private val maxFindings: Int = 30,
    private val maxOtherCategoryFindings: Int = 5,
) : FindingDetector {
    override val type = "boundary_mismatch"
    override val description =
        "File pairs in different modules that keep changing together — the module boundary and the actual change boundary disagree."

    override fun detect(context: AnalysisContext): List<Finding> {
        val boundaries = context.boundaries
        val candidates = context.pairs(minTogether = minSupport)
            .filter { it.a in context.headFiles && it.b in context.headFiles }
            .filter { boundaries.moduleOf(it.a) != boundaries.moduleOf(it.b) }
            // Confidence of the stronger direction: P(other | rarer file changed).
            .filter { confidence(it) >= minConfidence }
            // Companion pairs (Foo / DefaultFoo / FakeFoo) are expected to
            // co-change; unrelated names are the architecturally surprising ones.
            .sortedWith(compareBy<AnalysisContext.PairStat> { namesRelated(it.a, it.b) }
                .thenByDescending { confidence(it) * it.together })
            .toList()
            // Rank per category so build files and docs, which always co-change,
            // can't crowd production-code findings out of the list.
            .groupBy { FileCategory.ofPair(it.a, it.b) }
            .flatMap { (category, list) ->
                list.take(if (category == FileCategory.SOURCE) maxFindings else maxOtherCategoryFindings)
            }

        return candidates.map { pair -> toFinding(pair, context) }
    }

    private fun confidence(p: AnalysisContext.PairStat) = p.together.toDouble() / minOf(p.countA, p.countB)

    companion object {
        private val decorators = listOf("default", "fake", "impl", "abstract", "base", "stub", "mock", "real")

        private fun stem(path: String): String {
            var s = path.substringAfterLast('/').substringBefore('.').lowercase()
            for (d in decorators) {
                s = s.removePrefix(d).removeSuffix(d)
            }
            return s
        }

        /** True for pairs whose names predict the coupling: Foo/DefaultFoo, FooService/FakeFooService, Foo/FooTest. */
        fun namesRelated(a: String, b: String): Boolean {
            val sa = stem(a)
            val sb = stem(b)
            if (sa.length < 4 || sb.length < 4) return sa == sb
            return sa.contains(sb) || sb.contains(sa)
        }
    }

    private fun toFinding(p: AnalysisContext.PairStat, context: AnalysisContext): Finding {
        val boundaries = context.boundaries
        val confidence = confidence(p)
        val rarer = if (p.countA <= p.countB) p.a else p.b
        val other = if (p.countA <= p.countB) p.b else p.a
        val rarerCount = minOf(p.countA, p.countB)
        val otherCount = maxOf(p.countA, p.countB)
        // Same basename on both sides (e.g. two README.md) needs full paths to disambiguate.
        fun name(path: String) =
            if (p.a.substringAfterLast('/') == p.b.substringAfterLast('/')) path
            else path.substringAfterLast('/')

        val reverse = p.together.toDouble() / otherCount
        val counterSignals = buildList {
            if (reverse < 0.3) add(
                "${name(other)} changed $otherCount times overall but only ${p.together} of those touched ${name(rarer)} " +
                    "(${pct(reverse)}) — the coupling is one-directional, so ${name(other)} may simply be a widely shared file."
            )
            if (rarerCount < 10) add("Only $rarerCount changes to ${name(rarer)} in the analyzed period — small sample.")
            if (namesRelated(p.a, p.b)) add(
                "The file names look like an interface/implementation or companion pair — this coupling is expected " +
                    "and carries less architectural surprise than coupling between unrelated names."
            )
        }
        return Finding(
            id = "",
            type = type,
            category = FileCategory.ofPair(p.a, p.b),
            summary = "${name(p.a)} (${boundaries.moduleOf(p.a)}) and ${name(p.b)} (${boundaries.moduleOf(p.b)}) " +
                "evolve as one change unit across a module boundary",
            confidence = round2(confidence),
            impact = if (confidence >= 0.8 && reverse >= 0.3 && p.together >= 10 && !namesRelated(p.a, p.b)) "high" else "medium",
            detail = FindingDetail(
                observation = "${p.together} of $rarerCount changes to ${name(rarer)} also changed ${name(other)} " +
                    "(${pct(confidence)}), despite living in different modules " +
                    "(${boundaries.moduleOf(p.a)} vs ${boundaries.moduleOf(p.b)}).",
                interpretations = listOf(
                    "The module boundary may not match the actual change boundary.",
                    "An abstraction in one module may be leaking implementation details into the other.",
                    "Consider moving the pair into one module, or introducing an interface that absorbs the shared reason to change.",
                ),
                counterSignals = counterSignals,
                supportingChanges = context.sampleChanges(setOf(p.a, p.b)),
                metrics = mapOf(
                    "together" to p.together.toString(),
                    "changes(${p.a})" to p.countA.toString(),
                    "changes(${p.b})" to p.countB.toString(),
                    "P(${name(other)}|${name(rarer)})" to round2(confidence).toString(),
                    "P(${name(rarer)}|${name(other)})" to round2(reverse).toString(),
                ),
            ),
        )
    }
}

/**
 * Finds files dragged into a large share of multi-file changes across many
 * modules: candidates for god modules, central wiring, or catch-all utilities.
 */
class UnstableHubDetector(
    private val minParticipation: Int = 20,
    private val minModuleSpread: Int = 5,
    private val maxFindings: Int = 10,
) : FindingDetector {
    override val type = "unstable_hub"
    override val description =
        "Files dragged into a large share of multi-file changes across many modules — candidates for god modules, central wiring, or catch-all utilities."

    override fun detect(context: AnalysisContext): List<Finding> {
        // Only multi-file changes: a hub is a file dragged into *other* work.
        val multiFileChanges = context.changes.filter { it.files.size >= 2 }
        if (multiFileChanges.isEmpty()) return emptyList()
        val boundaries = context.boundaries

        val participation = HashMap<String, Int>()
        val partnerModules = HashMap<String, MutableSet<String>>()
        for (change in multiFileChanges) {
            val modules = change.files.map(boundaries::moduleOf).toSet()
            for (file in change.files) {
                participation.merge(file, 1, Int::plus)
                partnerModules.getOrPut(file) { HashSet() }.addAll(modules - boundaries.moduleOf(file))
            }
        }

        return participation.asSequence()
            .filter { (file, count) ->
                file in context.headFiles && count >= minParticipation &&
                    (partnerModules[file]?.size ?: 0) >= minModuleSpread
            }
            .sortedByDescending { (file, count) -> count.toLong() * partnerModules[file]!!.size }
            .take(maxFindings)
            .map { (file, count) ->
                val rate = count.toDouble() / multiFileChanges.size
                val modules = partnerModules[file]!!.size
                Finding(
                    id = "",
                    type = type,
                    category = FileCategory.of(file),
                    summary = "${file.substringAfterLast('/')} participated in ${pct(rate)} of multi-file changes, " +
                        "spanning $modules other modules",
                    confidence = round2(minOf(1.0, count / 50.0)),
                    impact = if (rate >= 0.05) "high" else "medium",
                    detail = FindingDetail(
                        observation = "$file was part of $count of ${multiFileChanges.size} multi-file changes " +
                            "(${pct(rate)}), together with files from $modules other modules.",
                        interpretations = listOf(
                            "This file may aggregate unrelated responsibilities (e.g. a god module, central DI wiring, or a catch-all utility).",
                            "Every feature change paying a toll here increases merge conflicts and review load.",
                            "Consider splitting it along the change clusters that pass through it.",
                        ),
                        counterSignals = listOf(
                            "Registration points (DI modules, navigation graphs, string resources) legitimately change with many features; the question is whether the churn is additive-only or structural.",
                        ),
                        supportingChanges = multiFileChanges.asSequence()
                            .filter { file in it.files }.take(10).map { it.hashes.first() }.toList(),
                        metrics = mapOf(
                            "participation" to count.toString(),
                            "multiFileChanges" to multiFileChanges.size.toString(),
                            "partnerModules" to modules.toString(),
                        ),
                    ),
                )
            }
            .toList()
    }
}
