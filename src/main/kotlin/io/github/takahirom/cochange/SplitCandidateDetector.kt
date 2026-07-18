package io.github.takahirom.cochange

/**
 * Finds files whose strong co-change partners fall apart into multiple
 * independent groups once the file itself is removed from the graph. Such a
 * file participates in several unrelated change contexts — the classic god
 * class/module signature — and the partner groups suggest the split lines.
 */
class SplitCandidateDetector(
    private val minSupport: Int = 5,
    private val minPartnerLink: Int = 3,
    private val minComponentSize: Int = 2,
    private val maxFindings: Int = 10,
) : FindingDetector {
    override val type = "split_candidate"
    override val description =
        "Files whose co-change partners form multiple independent clusters — the file likely serves several unrelated responsibilities and the clusters suggest where to split it."

    override fun detect(context: AnalysisContext): List<Finding> {
        // Co-change adjacency over all files, at the weaker partner-partner strength.
        val adjacency = HashMap<String, MutableMap<String, Int>>()
        for (p in context.pairs(minTogether = minPartnerLink)) {
            if (p.a !in context.headFiles || p.b !in context.headFiles) continue
            adjacency.getOrPut(p.a) { HashMap() }[p.b] = p.together
            adjacency.getOrPut(p.b) { HashMap() }[p.a] = p.together
        }

        data class Candidate(val file: String, val components: List<List<String>>, val partnerSupport: Int)

        val candidates = adjacency.mapNotNull { (file, links) ->
            val partners = links.filterValues { it >= minSupport }.keys
            if (partners.size < minComponentSize * 2) return@mapNotNull null
            val components = connectedComponents(partners, adjacency)
                .filter { it.size >= minComponentSize }
                .sortedByDescending { component -> component.sumOf { links[it] ?: 0 } }
            if (components.size < 2) return@mapNotNull null
            Candidate(file, components, partners.sumOf { links[it] ?: 0 })
        }
            .sortedWith(compareByDescending<Candidate> { it.components.size }.thenByDescending { it.partnerSupport })
            .take(maxFindings)

        return candidates.map { c ->
            val file = c.file
            val name = file.substringAfterLast('/')
            val groups = c.components.mapIndexed { i, component ->
                val names = component.take(5).joinToString(", ") { it.substringAfterLast('/') }
                val more = if (component.size > 5) " … and ${component.size - 5} more" else ""
                "group ${i + 1} (${component.size} files): $names$more"
            }
            Finding(
                id = "",
                type = type,
                category = FileCategory.of(file),
                summary = "$name belongs to ${c.components.size} independent change clusters",
                confidence = round2(minOf(1.0, c.partnerSupport / 50.0)),
                impact = if (c.components.size >= 3) "high" else "medium",
                detail = FindingDetail(
                    observation = "$file strongly co-changes with ${c.components.sumOf { it.size }} files that fall into " +
                        "${c.components.size} groups with no co-change between them: ${groups.joinToString("; ")}.",
                    interpretations = listOf(
                        "The file likely bundles several unrelated responsibilities, one per group.",
                        "Splitting it along the groups would let each change context evolve without touching the others.",
                    ),
                    counterSignals = listOf(
                        "Groups can also reflect eras (an old and a new caller generation) or platform variants rather than separable responsibilities — check whether the groups are alive at the same time.",
                    ),
                    supportingChanges = context.sampleChanges(setOf(file)),
                    metrics = mapOf(
                        "independentGroups" to c.components.size.toString(),
                        "partners" to c.components.sumOf { it.size }.toString(),
                        "partnerSupport" to c.partnerSupport.toString(),
                    ),
                ),
            )
        }
    }

    /** Connected components among [nodes], using [adjacency] edges of at least [minPartnerLink]. */
    private fun connectedComponents(
        nodes: Set<String>,
        adjacency: Map<String, Map<String, Int>>,
    ): List<List<String>> {
        val remaining = nodes.toMutableSet()
        val components = ArrayList<List<String>>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.first()
            val component = ArrayList<String>()
            val queue = ArrayDeque(listOf(seed))
            remaining.remove(seed)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component.add(current)
                for ((neighbor, weight) in adjacency[current].orEmpty()) {
                    if (weight >= minPartnerLink && neighbor in remaining) {
                        remaining.remove(neighbor)
                        queue.add(neighbor)
                    }
                }
            }
            components.add(component)
        }
        return components
    }
}
