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
) {
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

    /** Sample commit hashes (newest-first order of [changes]) touching all of [files]. */
    fun sampleChanges(files: Set<String>, limit: Int = 10): List<String> =
        changes.asSequence()
            .filter { it.files.containsAll(files) }
            .take(limit)
            .map { it.hashes.first() }
            .toList()
}
