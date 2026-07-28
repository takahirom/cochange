package io.github.takahirom.cochange

/**
 * Groups strongly co-changing files into clusters — connected components over
 * pair edges — so a hub file shows up once with all its companions instead of
 * scattering across many A×B rows. Edge strength is Jaccard similarity
 * (together / changes touching either file), which is symmetric: a rarely
 * changed leaf can't attach to a ubiquitous hub on one-sided probability alone.
 */
object Clusters {
    data class Edge(val a: String, val b: String, val together: Int, val jaccard: Double)
    data class Cluster(
        /** The files shown. Excluded roles are omitted; [hiddenFiles] says how many. */
        val files: List<String>,
        val edges: List<Edge>,
        /** Members omitted by `--exclude-role`. The counts below still include them. */
        val hiddenFiles: Int = 0,
        /** Members in total, including [hiddenFiles] — what the counts below describe. */
        val fileCount: Int = files.size,
    ) {
        /** Sum of pair supports, not distinct change units (files in n pairs count n times). */
        val pairSupportVolume: Long get() = edges.sumOf { it.together.toLong() }
    }

    fun build(
        context: AnalysisContext,
        minSupport: Int,
        minConfidence: Double,
        fileFilter: (String) -> Boolean = { true },
    ): List<Cluster> {
        // Built over every file at HEAD, NOT the visible subset. A cluster's file count,
        // strong-pair count and support volume are facts about the history; filtering the
        // graph first made `--exclude-role test` shrink all three and re-rank the clusters,
        // which the tool promises a view filter cannot do. `fileFilter` is different — it
        // is the caller's `--category` scope, a deliberate narrowing of the question.
        val edges = context.pairs(minTogether = minSupport)
            .filter { it.a in context.headFiles && it.b in context.headFiles }
            .filter { fileFilter(it.a) && fileFilter(it.b) }
            .map { Edge(it.a, it.b, it.together, it.jaccard) }
            .filter { it.jaccard >= minConfidence }
            .toList()

        val parent = HashMap<String, String>()
        fun find(x: String): String {
            var root = x
            while (parent.getOrDefault(root, root) != root) root = parent[root]!!
            var cur = x
            while (cur != root) {
                val next = parent[cur]!!
                parent[cur] = root
                cur = next
            }
            return root
        }
        for (e in edges) {
            parent[find(e.a)] = find(e.b)
        }

        return edges.groupBy { find(it.a) }
            .map { (_, clusterEdges) ->
                val all = clusterEdges.flatMap { listOf(it.a, it.b) }.distinct()
                    .sortedByDescending { f -> clusterEdges.filter { it.a == f || it.b == f }.sumOf { it.together } }
                // The counts above came from the whole cluster; the file list a reader sees
                // omits hidden roles, and `hiddenFiles` says how many.
                Cluster(
                    files = all.filter(context::isVisible),
                    hiddenFiles = all.count { !context.isVisible(it) },
                    edges = clusterEdges.sortedByDescending { it.together },
                    fileCount = all.size,
                )
            }
            .sortedByDescending { it.pairSupportVolume }
    }
}
