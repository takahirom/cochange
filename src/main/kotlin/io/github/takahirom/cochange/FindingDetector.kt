package io.github.takahirom.cochange

/**
 * One detector producing findings of a single type. Each detector describes
 * what it finds ([type], [description]) so `cochange detectors` can tell
 * humans and AI what this tool is able to discover. New finding types plug in
 * by implementing this and adding the instance to the Analyzer's list.
 */
interface FindingDetector {
    /** Machine-readable finding type, e.g. "boundary_mismatch". */
    val type: String

    /** One sentence: what this detector finds and why it matters. */
    val description: String

    /**
     * True when this detector's findings assert something about module
     * boundaries. Those detectors are withheld entirely when module detection
     * did not resolve real boundaries for this repository — see [ModuleGate].
     */
    val requiresModuleBoundaries: Boolean get() = false

    fun detect(context: AnalysisContext): List<Finding>
}

/**
 * Shared statistics computed once per analysis and handed to every strategy:
 * per-file change counts and co-change counts for every file pair.
 */
class AnalysisContext(
    val changes: List<LogicalChange>,
    val boundaries: Boundaries,
    val headFiles: Set<String>,
    /** Files declared `linguist-generated` in `.gitattributes`; classified as generated on top of the name heuristics. */
    val generated: Set<String> = emptySet(),
    /**
     * Roles the user asked to exclude (see [FileRole]). Applied when *presenting*
     * results, never when counting them: the pair counts below always reflect the
     * full history, so hiding tests can't change what the remaining numbers mean.
     */
    val excludedRoles: Set<String> = emptySet(),
) {
    /** True when [path]'s role was excluded, so it should not be shown or reported on. */
    fun isHidden(path: String): Boolean =
        excludedRoles.isNotEmpty() && FileRole.of(path) in excludedRoles

    /** A file worth showing: still present at HEAD, and not hidden by an excluded role. */
    fun isVisible(path: String): Boolean = path in headFiles && !isHidden(path)

    /**
     * True when this file's module comes from something the repository declares
     * (a build file, a SwiftPM target, a `--module-root` glob) rather than from a
     * top-level directory name. A finding whose claim is "these live in different
     * modules" is only worth making about files that pass this.
     */
    fun moduleIsDeclared(path: String): Boolean = boundaries.sourceOf(path).declared

    /** How many analyzed files each excluded role is hiding, for the "and here's what you're not seeing" line. */
    val hiddenByRole: Map<String, Int> by lazy {
        if (excludedRoles.isEmpty()) emptyMap() else analyzedFiles
            .groupingBy { FileRole.of(it) }.eachCount()
            .filterKeys { it in excludedRoles }
    }

    /** Category of one file, treating repo-declared generated files as [FileCategory.GENERATED]. */
    fun categoryOf(path: String): String =
        if (path in generated) FileCategory.GENERATED else FileCategory.of(path)

    /** Category of a pair: the least source-like side (one generated/build file makes the pair that). */
    fun categoryOfPair(a: String, b: String): String {
        val ca = categoryOf(a)
        val cb = categoryOf(b)
        return if (FileCategory.priority.indexOf(ca) >= FileCategory.priority.indexOf(cb)) ca else cb
    }

    /**
     * Files that both appear in the analyzed history and still exist at HEAD —
     * the population every finding draws from, and therefore the population
     * module-detection coverage is measured over.
     */
    val analyzedFiles: Set<String> by lazy {
        changes.flatMap { it.files }.filterTo(HashSet()) { it in headFiles }
    }

    /** Provenance and coverage of module detection over [analyzedFiles]. */
    val moduleDetection: ModuleDetection by lazy { boundaries.detection(analyzedFiles) }

    private val pathToId = HashMap<String, Int>()
    private val idToPath = ArrayList<String>()
    private val fileChangeCountById = HashMap<Int, Int>()
    private val pairCountByKey = HashMap<Long, Int>()

    init {
        fun id(path: String): Int = pathToId.getOrPut(path) { idToPath.add(path); idToPath.size - 1 }
        for (change in changes) {
            val ids = change.files.map(::id).sorted()
            for (fileId in ids) fileChangeCountById.merge(fileId, 1, Int::plus)
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    val key = (ids[i].toLong() shl 32) or ids[j].toLong()
                    pairCountByKey.merge(key, 1, Int::plus)
                }
            }
        }
    }

    data class PairStat(val a: String, val b: String, val together: Int, val countA: Int, val countB: Int) {
        /** P(other | rarer): co-change probability in the stronger direction. */
        val confidence: Double get() = together.toDouble() / minOf(countA, countB)

        /** P(rarer | other): the weaker direction of the same pair. */
        val reverse: Double get() = together.toDouble() / maxOf(countA, countB)

        /** Symmetric similarity: together / changes touching either file. */
        val jaccard: Double get() = together.toDouble() / (countA + countB - together)
    }

    fun changeCount(path: String): Int = pathToId[path]?.let { fileChangeCountById[it] } ?: 0

    fun pairs(minTogether: Int = 1): Sequence<PairStat> = pairCountByKey.asSequence()
        .filter { it.value >= minTogether }
        .map { (key, together) ->
            val aId = (key ushr 32).toInt()
            val bId = (key and 0xFFFFFFFFL).toInt()
            PairStat(idToPath[aId], idToPath[bId], together, fileChangeCountById[aId]!!, fileChangeCountById[bId]!!)
        }

    /**
     * Sample change units (newest-first order of [changes]) touching all of [files],
     * each with every commit it contains — the unit, not its first commit, is what
     * the co-change evidence was counted from.
     */
    fun sampleChanges(files: Set<String>, limit: Int = 10): List<SupportingChange> =
        changes.asSequence()
            .filter { it.files.containsAll(files) }
            .take(limit)
            .map { SupportingChange(it.hashes) }
            .toList()
}
