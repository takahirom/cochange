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
    private val maxOtherCategoryFindings: Int = 5,
) : FindingDetector {
    override val type = "split_candidate"
    override val description =
        "Files whose co-change partners form multiple independent clusters — the file likely serves several unrelated responsibilities and the clusters suggest where to split it."

    override fun detect(context: AnalysisContext): List<Finding> {
        // Co-change adjacency over all files, at the weaker partner-partner strength.
        val adjacency = HashMap<String, MutableMap<String, Int>>()
        for (p in context.pairs(minTogether = minPartnerLink)) {
            if (!context.isVisible(p.a) || !context.isVisible(p.b)) continue
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
            // Cap per category (like boundary_mismatch) so generated/build/docs files
            // can't consume every slot and push source split-candidates out.
            .groupBy { context.categoryOf(it.file) }
            .flatMap { (category, list) ->
                list.take(if (category == FileCategory.SOURCE) maxFindings else maxOtherCategoryFindings)
            }

        /**
         * The change units where the candidate moved together with this group,
         * counted once each — unlike the summed pairwise link weight, which counts
         * a unit again for every member it touched. Also yields the first and last
         * of those units, so a reader can tell separate responsibilities from an
         * old and a new era of the same one.
         */
        fun coChanges(file: String, component: List<String>): Triple<Int, Long, Long>? {
            val times = context.changes.asSequence()
                .filter { file in it.files && component.any { m -> m in it.files } }
                .map { unit -> unit.commits.minOf { it.epochSec } }
                .toList()
            return if (times.isEmpty()) null else Triple(times.size, times.min(), times.max())
        }
        fun day(epochSec: Long): String =
            java.time.Instant.ofEpochSecond(epochSec).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()

        return candidates.map { c ->
            val file = c.file
            val name = file.substringAfterLast('/')
            val links = adjacency[file].orEmpty()
            val structuredGroups = c.components.map { component ->
                val seen = coChanges(file, component)
                SplitGroup(
                    files = component,
                    support = seen?.first ?: 0,
                    linkWeight = component.sumOf { links[it] ?: 0 },
                    firstSeen = seen?.let { day(it.second) } ?: "",
                    lastSeen = seen?.let { day(it.third) } ?: "",
                )
            }
            // Change units where the candidate moved with any group at all, counted once.
            val ownChanges = context.changeCount(file)
            val groupSupport = context.changes.count { unit ->
                file in unit.files && c.components.any { comp -> comp.any { it in unit.files } }
            }
            val groups = c.components.mapIndexed { i, component ->
                val names = component.take(5).joinToString(", ") { it.substringAfterLast('/') }
                val more = if (component.size > 5) " … and ${component.size - 5} more" else ""
                "group ${i + 1} (${component.size} files): $names$more"
            }
            Finding(
                id = "",
                type = type,
                category = context.categoryOf(file),
                summary = "$name belongs to ${c.components.size} independent change clusters",
                // Share of the candidate's own changes that involved one of the groups.
                // Previously partnerSupport/50, an arbitrary scale unrelated to how
                // often the file actually changes.
                confidence = round2(groupSupport.toDouble() / ownChanges.coerceAtLeast(1)),
                impact = if (c.components.size >= 3) "high" else "medium",
                evidence = FindingEvidence(
                    support = groupSupport,
                    sampleSize = ownChanges,
                    sampleMeaning = "change units touching $file",
                    ratio = round2(groupSupport.toDouble() / ownChanges.coerceAtLeast(1)),
                ),
                detail = FindingDetail(
                    observation = "$file strongly co-changes with ${c.components.sumOf { it.size }} files that fall into " +
                        "${c.components.size} groups with fewer than $minPartnerLink co-changes between any two " +
                        "members of different groups: ${groups.joinToString("; ")}.",
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
                        "partnerLinkWeight" to c.partnerSupport.toString(),
                    ),
                    groups = structuredGroups,
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
