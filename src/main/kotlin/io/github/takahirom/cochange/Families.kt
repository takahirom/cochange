package io.github.takahirom.cochange

/**
 * Learns "synchronized sibling families" from the repo itself — sets of files
 * with the same basename living in direct sibling directories (e.g.
 * values/strings.xml, values-ja/strings.xml, values-fr/strings.xml) that in
 * practice always change together. Collapsing such a family to one node keeps a
 * translation set (or any structural variant set) from dominating clusters and
 * manufacturing hubs, WITHOUT any locale/ecosystem-specific pattern list: the
 * family is a purely structural candidate, accepted only if the history shows it
 * actually moves as a set.
 */
object Families {
    data class Family(val representative: String, val members: List<String>)

    fun detect(
        context: AnalysisContext,
        boundaries: Boundaries,
        // 2 is safe here because grouping is by *same basename*, so interface/impl
        // pairs (Foo / DefaultFoo — different basenames) never group; the synchrony
        // test below, not the member count, is what prevents wrong collapses.
        minMembers: Int = 2,
    ): List<Family> {
        // Candidate grouping: same grandparent dir + same basename, i.e. the same
        // file name under sibling directories.
        val candidates = context.headFiles
            .filter { it.count { c -> c == '/' } >= 2 }
            // A Pair key, not a joined string: any separator character can legally
            // appear in a path, and the NUL that made the joined key unambiguous also
            // made git treat this source file as binary and hide its diffs.
            .groupBy { path ->
                val parent = path.substringBeforeLast('/')
                parent.substringBeforeLast('/', "") to path.substringAfterLast('/')
            }

        return candidates.values.mapNotNull { members ->
            if (members.size < minMembers) return@mapNotNull null
            // Distinct sibling directories (not several files in one dir).
            if (members.map { it.substringBeforeLast('/') }.toSet().size < minMembers) return@mapNotNull null
            // One module and one category, so we never collapse across a boundary — and
            // that module must be DECLARED. Under guessed boundaries every sibling
            // service directory shares one top-level "module", so config.yml files from
            // two independent services looked like one variant set and their edge was
            // collapsed away.
            if (members.any { !boundaries.sourceOf(it).declared }) return@mapNotNull null
            if (members.map { boundaries.moduleOf(it) }.toSet().size != 1) return@mapNotNull null
            if (members.map { context.categoryOf(it) }.toSet().size != 1) return@mapNotNull null
            if (!movesAsASet(context, members)) return@mapNotNull null
            // Representative: shortest directory (the base variant, e.g. `values/`), tie-break by path.
            val representative = members.minWith(compareBy({ it.substringBeforeLast('/').length }, { it }))
            Family(representative, members.sorted())
        }
    }

    /** History check: the members really move together, not just share a naming shape. */
    private fun movesAsASet(context: AnalysisContext, members: List<String>): Boolean {
        val memberSet = members.toSet()
        val units = context.changes.filter { change -> change.files.any { it in memberSet } }
        if (units.size < 5) return false
        // Most family-touching units touch most of the family...
        val mostlyComplete = units.count { u -> memberSet.count { it in u.files } >= 0.8 * members.size } >= 0.8 * units.size
        // ...and no member is a passenger that rarely participates.
        val everyMemberActive = members.all { m -> units.count { m in it.files } >= 0.6 * units.size }
        return mostlyComplete && everyMemberActive
    }

    /** Non-representative member -> representative, for aliasing files during projection. */
    fun aliasMap(families: List<Family>): Map<String, String> = buildMap {
        for (family in families) for (member in family.members) if (member != family.representative) put(member, family.representative)
    }

    /**
     * A view of [context] with every family member replaced by its representative,
     * so a family shows up as a single node and its internal pairs disappear.
     * Raw evidence is untouched — this is a derived projection, not a mutation.
     *
     * The projection carries [AnalysisContext.excludedRoles] forward. Dropping it made
     * `clusters --exclude-role` show the very files it reported as hidden.
     */
    fun project(context: AnalysisContext, boundaries: Boundaries, families: List<Family>): AnalysisContext {
        if (families.isEmpty()) return context
        val alias = aliasMap(families)
        fun map(path: String) = alias[path] ?: path
        val changes = context.changes.map { change ->
            LogicalChange(change.commits.map { it.copy(files = it.files.map(::map).distinct()) })
        }
        return AnalysisContext(
            changes,
            boundaries,
            context.headFiles.map(::map).toSet(),
            context.generated.map(::map).toSet(),
            excludedRoles = context.excludedRoles,
        )
    }
}
