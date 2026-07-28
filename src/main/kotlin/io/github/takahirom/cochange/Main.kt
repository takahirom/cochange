package io.github.takahirom.cochange

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import java.io.File
import kotlin.system.exitProcess

class Cochange : CliktCommand(
    name = "cochange",
    help = "Code that changes together should live together — cochange reads your Git history to show where it doesn't.",
) {
    init {
        versionOption(Cochange::class.java.`package`.implementationVersion ?: "dev")
    }

    override fun run() = Unit
}

/** History-reading options shared by every command that walks the git log. */
class HistoryOptions : OptionGroup(name = "History options") {
    val branch by option("--branch", help = "Branch to analyze (default: HEAD)")
    val since by option("--since", help = "History window, e.g. '2 years ago' (git --since syntax)")
    val changeUnit by option("--change-unit", help = "What counts as one change: auto, merge (first-parent, one merge = one PR), author-window, commit").default("auto")
    val maxFiles by option("--max-files", help = "Skip commits touching more files than this").int().restrictTo(min = 1).default(50)
    val groupWindowMin by option("--group-window", help = "Minutes within which same-author commits form one logical change (author-window mode)").long().restrictTo(1L..10_000L).default(30)
    val exclude by option("--exclude", help = "Glob to exclude (repeatable, adds to defaults)").multiple()
    val moduleRoot by option("--module-root", help = "Glob marking extra module-root directories (repeatable), e.g. 'ios/Targets/*' for build systems without per-module build files").multiple()
    val excludeRole by option("--exclude-role", help = "Hide files by role from findings/pairs/clusters: ${FileRole.EXCLUDABLE.joinToString(", ")} (comma-separated). Counting still covers them, so the remaining numbers don't shift.").split(",")
    val focus by option("--focus", help = "Shortcut: 'production-source' hides test, resource, lockfile, and generated files").choice("production-source")

    fun toAnalysisOptions() = AnalysisOptions(
        branch = branch, since = since, changeUnit = changeUnit,
        maxFilesPerCommit = maxFiles, groupWindowMin = groupWindowMin, extraExcludes = exclude,
        moduleRoots = moduleRoot, excludeRoles = resolveExcludeRoles(),
    )

    private fun resolveExcludeRoles(): List<String> = buildSet {
        excludeRole?.forEach { add(FileRole.validateExcludable(it.trim())) }
        if (focus == "production-source") addAll(FileRole.EXCLUDABLE)
    }.toList()
}

class Analyze : CliktCommand(
    name = "analyze",
    help = "Analyze a repository's change history and produce findings.",
) {
    private val path by argument(help = "Path to the Git repository").default(".")
    private val history by HistoryOptions()
    private val minSupport by option("--min-support", help = "Minimum co-change count for a pair finding").int().restrictTo(min = 1).default(5)
    private val minConfidence by option("--min-confidence", help = "Minimum co-change ratio for a pair finding, P(other | rarer)").double().restrictTo(0.0, 1.0).default(0.6)
    private val save by option("--save", help = "Name this analysis snapshot so it isn't overwritten by later runs (default: ${Store.DEFAULT_NAME})").default(Store.DEFAULT_NAME)
    private val asJson by option("--json", help = "Print the full result as JSON").flag()

    override fun run() {
        val repo = File(path).canonicalFile
        Store.validateName(save)
        val start = System.currentTimeMillis()
        val setup = Analysis.contextFor(repo, history.toAnalysisOptions())
        if (!asJson) {
            printBanner(setup, { m, e -> echo(m, err = e) })
            echo("thresholds: min-support=$minSupport min-confidence=$minConfidence")
            echo("")
        }
        val changes = setup.changes
        val run = Analyzer(minSupport, minConfidence).run(setup.context)

        // A support-N finding drawn from very few change units would have to rest on a
        // large share of the entire analyzed history. Report the ratio rather than a bar:
        // it is what tells a reader whether "no findings" means "clean" or "no sample".
        val sampleWarning = if (changes.isNotEmpty() && changes.size < minSupport * 4) {
            val reach = if (minSupport > changes.size) {
                "--min-support $minSupport is unreachable: no coupling can appear in more units than exist, " +
                    "so no pair finding is possible at all"
            } else {
                "a finding at --min-support $minSupport would rest on " +
                    "${pct(minSupport.toDouble() / changes.size)} of the whole analyzed history"
            }
            listOf(AnalysisWarning(
                AnalysisWarning.FEW_CHANGE_UNITS, "warning",
                "Only ${changes.size} change units in this window, so $reach. Read \"no findings\" as " +
                    "\"not enough independent changes to say\", not as \"nothing to fix\" — " +
                    "see: cochange guide small-repo.",
            ))
        } else emptyList()

        val result = AnalysisResult(
            schemaVersion = SCHEMA_VERSION,
            repo = repo.path,
            branch = history.branch ?: "HEAD",
            headCommit = setup.headCommit,
            shallow = setup.shallow,
            changeUnit = setup.changeUnitName,
            analyzedCommits = changes.sumOf { it.commits.size },
            logicalChanges = changes.size,
            findings = run.findings,
            requestedOptions = history.toAnalysisOptions(),
            options = setup.resolvedOptions,
            moduleDetection = run.moduleDetection,
            skippedDetectors = run.skipped,
            hiddenByRole = run.hiddenByRole,
            warnings = setup.warnings + sampleWarning,
        )
        Store.save(repo, result, save)

        if (asJson) {
            echo(Store.encode(result))
        } else {
            val elapsed = (System.currentTimeMillis() - start) / 1000.0
            echo("Analyzed ${result.analyzedCommits} commits as ${changes.size} change units (unit: ${setup.changeUnitName}) in ${"%.1f".format(elapsed)}s")
            if (save != Store.DEFAULT_NAME) echo("saved as analysis '$save' — read it with: cochange findings $path --analysis $save")
            echo("")
            printFindingsSummary(result, ::echo)
            echo("")
            val analysisFlag = if (save != Store.DEFAULT_NAME) " --analysis $save" else ""
            echo("Next: cochange inspect <finding-id> [$repo]$analysisFlag — or cochange guide for the full playbook")
        }
    }
}

class Findings : CliktCommand(
    name = "findings",
    help = "List findings from the last analysis.",
) {
    private val path by argument(help = "Path to the Git repository").default(".")
    private val type by option("--type", help = "Only findings of this type (e.g. boundary_mismatch, unstable_hub)")
    private val category by option("--category", help = "Only findings in this category (source, config, build, docs, generated)")
    private val analysis by option("--analysis", help = "Which saved analysis snapshot to read (default: ${Store.DEFAULT_NAME})").default(Store.DEFAULT_NAME)
    private val asJson by option("--json").flag()

    override fun run() {
        val loaded = loadOrFail(path, analysis)
        val result = loaded.copy(findings = loaded.findings
            .filter { type == null || it.type == type }
            .filter { category == null || it.category == category })
        if (asJson) {
            echo(Store.encode(result))
        } else if (result.findings.isEmpty() && loaded.findings.isNotEmpty()) {
            echo("No findings match the filters (${loaded.findings.size} findings in total; try without --type/--category).")
        } else {
            printFindingsSummary(result, ::echo)
        }
    }
}

class Pairs : CliktCommand(
    name = "pairs",
    help = "List raw co-change pairs (no finding thresholds), strongest first.",
) {
    private val path by argument(help = "Path to the Git repository").default(".")
    private val history by HistoryOptions()
    private val minSupport by option("--min-support", help = "Minimum co-change count to list").int().restrictTo(min = 1).default(5)
    private val top by option("--top", help = "Number of pairs to show").int().restrictTo(min = 1).default(50)
    private val file by option("--file", help = "Only pairs involving a path containing this substring")
    private val category by option("--category", help = "Only pairs in this category (source, config, build, docs, generated)")
    private val analysis by option("--analysis", help = "Reuse the conditions (window/excludes/change-unit) from a saved analysis snapshot. Replaces the history options above rather than combining with them")
    private val asJson by option("--json", help = "Print the pairs as JSON, with the run's pinned conditions and provenance").flag()

    override fun run() {
        val repo = File(path).canonicalFile
        val setup = Analysis.contextFor(repo, resolveOptions(path, analysis, history))
        val context = setup.context
        val boundaries = context.boundaries

        val selected = context.pairs(minTogether = minSupport)
            .filter { context.isVisible(it.a) && context.isVisible(it.b) }
            .filter { file == null || it.a.contains(file!!) || it.b.contains(file!!) }
            .filter { category == null || context.categoryOfPair(it.a, it.b) == category }
            .sortedByDescending { it.together }
            .take(top)
            .toList()

        if (asJson) {
            echo(reportJson.encodeToString(
                PairsReport.serializer(),
                PairsReport(RunContext.of(setup), minSupport, selected.map { pairReport(it, context) }),
            ))
            return
        }

        printBanner(setup, { m, e -> echo(m, err = e) }, compact = true)
        echo("together  conf   modules                  pair")
        selected.forEach { p ->
            val modA = boundaries.moduleOf(p.a)
            val modB = boundaries.moduleOf(p.b)
            val modules = if (modA == modB) "same ($modA)" else "$modA <-> $modB"
            echo("%8d  %.2f   %-22s  %s (%d)  +  %s (%d)".format(
                p.together, p.confidence, modules, p.a, p.countA, p.b, p.countB))
        }
    }
}

class ClustersCommand : CliktCommand(
    name = "clusters",
    help = "Group co-changing files into clusters (connected components of strong co-change pairs; files may connect transitively).",
) {
    private val path by argument(help = "Path to the Git repository").default(".")
    private val history by HistoryOptions()
    private val minSupport by option("--min-support", help = "Minimum co-change count for an edge").int().restrictTo(min = 1).default(10)
    private val minJaccard by option("--min-jaccard", help = "Minimum Jaccard similarity for an edge (together / either)").double().restrictTo(0.0, 1.0).default(0.25)
    private val top by option("--top", help = "Number of clusters to show").int().restrictTo(min = 1).default(10)
    private val category by option("--category", help = "Only files in this category (source, config, build, docs, generated)")
    private val show by option("--show", help = "Expand one cluster (by its number) to its full file list").int().restrictTo(min = 1)
    private val analysis by option("--analysis", help = "Reuse the conditions (window/excludes/change-unit) from a saved analysis snapshot. Replaces the history options above rather than combining with them")
    private val noCollapse by option("--no-collapse", help = "Don't collapse synchronized sibling families (e.g. strings.xml across locales) into one node").flag()
    private val asJson by option("--json", help = "Print the clusters as JSON, with full file lists, the run's pinned conditions, and provenance").flag()

    override fun run() {
        val repo = File(path).canonicalFile
        val setup = Analysis.contextFor(repo, resolveOptions(path, analysis, history))
        val rawContext = setup.context
        val boundaries = rawContext.boundaries
        val families = if (noCollapse) emptyList() else Families.detect(rawContext, boundaries)
        val famByRep = families.associateBy { it.representative }
        // Collapse families to their representative so a locale/variant set is one node.
        val context = Families.project(rawContext, boundaries, families)
        val clusters = Clusters.build(context, minSupport, minJaccard) {
            category == null || context.categoryOf(it) == category
        }

        fun label(file: String): String {
            val fam = famByRep[file] ?: return file
            // Sibling directory names are paths too: an excluded role must not be named
            // here, only counted, or the text announces what the JSON is hiding.
            val members = fam.members.filter { it != file }
            // setup.context, not the projected one: project() aliases every
            // non-representative member to the representative, so those paths are no
            // longer in the projected headFiles and would all look role-hidden.
            val shown = members.filter(setup.context::isVisible)
            val hidden = members.size - shown.size
            val names = shown.map { it.substringBeforeLast('/').substringAfterLast('/') }
            val withheld = if (hidden > 0) {
                (if (names.isEmpty()) "" else ", ") + "$hidden hidden by --exclude-role"
            } else ""
            return "$file (+${members.size} sibling${if (members.size == 1) "" else "s"}: " +
                "${names.joinToString(", ")}$withheld)"
        }

        if (asJson) {
            // JSON gets every cluster's full file list: the text overview truncates
            // because a monolith would flood a terminal, but a consumer needs it all.
            echo(reportJson.encodeToString(
                ClustersReport.serializer(),
                ClustersReport(
                    context = RunContext.of(setup),
                    minSupport = minSupport,
                    minJaccard = minJaccard,
                    clusters = clusters.mapIndexed { i, cluster ->
                        ClusterReport(
                            index = i + 1,
                            files = cluster.files,
                            fileCount = cluster.fileCount,
                            hiddenFiles = cluster.hiddenFiles,
                            modules = cluster.files.map { boundaries.moduleOf(it) }.distinct().sorted(),
                            declaredModules = cluster.files
                                .filter { setup.context.moduleIsDeclared(it) }
                                .map { boundaries.moduleOf(it) }.distinct().sorted(),
                            guessedFiles = cluster.files
                                .filterNot { setup.context.moduleIsDeclared(it) }.sorted(),
                            strongPairs = cluster.edges.size,
                            pairSupportVolume = cluster.pairSupportVolume,
                            // The strongest edge whose endpoints are both shown; an
                            // excluded role must not be named here either.
                            strongest = cluster.edges
                                .firstOrNull { setup.context.isVisible(it.a) && setup.context.isVisible(it.b) }
                                ?.let { ClusterEdgeReport(it.a, it.b, it.together, round2(it.jaccard)) },
                            // Members are paths, so an excluded role must not appear here
                            // either — the family itself is unaffected by the filter.
                            collapsedFamilies = cluster.files
                                .mapNotNull { f ->
                                    famByRep[f]?.let { fam -> f to fam.members.filter(setup.context::isVisible) }
                                }
                                .toMap(),
                        )
                    },
                ),
            ))
            return
        }

        printBanner(setup, { m, e -> echo(m, err = e) }, compact = true)
        echo("edge thresholds: min-support=$minSupport min-jaccard=$minJaccard")
        if (families.isNotEmpty()) echo("collapsed ${families.size} synchronized sibling ${if (families.size == 1) "family" else "families"} (e.g. ${famByRep.keys.first().substringAfterLast('/')} across ${famByRep.values.first().members.size}) — use --no-collapse to expand", err = true)
        if (clusters.isEmpty()) {
            echo("No clusters above thresholds. Try lowering --min-support / --min-jaccard.")
            return
        }

        // The strongest VISIBLE edge: this line names two paths, so an excluded role must
        // not appear in it. The cluster's counts above still include those edges.
        fun strongestVisible(cluster: Clusters.Cluster) = cluster.edges
            .firstOrNull { context.isVisible(it.a) && context.isVisible(it.b) }

        fun strongestOf(cluster: Clusters.Cluster): String {
            val s = strongestVisible(cluster) ?: return "(every strong pair here is hidden by --exclude-role)"
            val (la, lb) = distinguishingLabels(s.a, s.b)
            return "$la x $lb (${s.together} together, jaccard ${"%.2f".format(s.jaccard)})"
        }

        val showIdx = show
        if (showIdx != null) {
            if (showIdx > clusters.size) {
                echo("Only ${clusters.size} clusters above thresholds; --show $showIdx is out of range.")
                return
            }
            val cluster = clusters[showIdx - 1]
            echo("")
            echo("cluster $showIdx: ${cluster.fileCount} files${hiddenNote(cluster)}, ${cluster.edges.size} strong pair${if (cluster.edges.size == 1) "" else "s"} (pair-support volume ${cluster.pairSupportVolume})")
            for (file in cluster.files) {
                echo("  ${label(file)} (${context.changeCount(file)} changes, ${boundaries.moduleOf(file)})")
            }
            echo("  strongest pair: ${strongestOf(cluster)}")
            return
        }

        // Overview: one headline per cluster. The full file list is one --show away,
        // so a big monolith doesn't dump thousands of lines by default.
        clusters.take(top).forEachIndexed { i, cluster ->
            val modules = cluster.files.map { boundaries.moduleOf(it) }.distinct()
            val span = if (modules.size == 1) "1 module (${modules.first()})" else "${modules.size} modules"
            echo("")
            echo("cluster ${i + 1}: ${cluster.fileCount} files${hiddenNote(cluster)} across $span, ${cluster.edges.size} strong pair${if (cluster.edges.size == 1) "" else "s"} (pair-support volume ${cluster.pairSupportVolume})")
            echo("  strongest pair: ${strongestOf(cluster)}")
        }
        echo("")
        // Reproducing the clusters above needs the conditions they were computed under. With
        // --analysis that is the snapshot, and emitting --since alongside it would print two
        // conflicting sources for the same window.
        // Every option that changes the clustering, or the command does not reproduce what
        // was just printed. With --analysis the snapshot supplies the history conditions, so
        // repeating them would name two sources for the same window.
        val opts = buildString {
            if (analysis != null) {
                append(" --analysis $analysis")
            } else {
                history.since?.let { append(" --since \"$it\"") }
                history.branch?.let { append(" --branch $it") }
                if (history.changeUnit != "auto") append(" --change-unit ${history.changeUnit}")
                history.excludeRole?.let { append(" --exclude-role ${it.joinToString(",")}") }
                history.focus?.let { append(" --focus $it") }
            }
            if (noCollapse) append(" --no-collapse")
            append(" --min-support $minSupport --min-jaccard $minJaccard")
            category?.let { append(" --category $it") }
        }
        echo("Next: cochange clusters $path --show 1$opts — full file list for a cluster (keep these options to address the same one)")
    }
}

class MetricsCommand : CliktCommand(
    name = "metrics",
    help = "Repository-level scores (all higher-is-better): module locality, hub-free change rate, boundary integrity.",
) {
    private val path by argument(help = "Path to the Git repository").default(".")
    private val history by HistoryOptions()
    private val asJson by option("--json", help = "Machine-readable output for recording runs over time").flag()
    private val analysis by option("--analysis", help = "Reuse the conditions (window/excludes/change-unit) from a saved analysis snapshot. Replaces the history options above rather than combining with them")

    override fun run() {
        val repo = File(path).canonicalFile
        val setup = Analysis.contextFor(repo, resolveOptions(path, analysis, history))
        val m = Metrics.compute(setup.context)

        if (asJson) {
            echo(Metrics.encode(setup, m))
            return
        }
        printBanner(setup, { msg, e -> echo(msg, err = e) }, compact = true)
        echo("window: ${"%.1f".format(m.windowYears)} years  multi-file change units: ${m.multiFileUnits} " +
            "(${m.declaredMultiFileUnits} within declared modules)  " +
            "effective modules: ${"%.1f".format(m.effectiveModules)} (${m.distinctModules} distinct)")
        if (m.lowResolution) {
            echo("WARNING: low-resolution module partition (effective modules < ${Metrics.LOW_RESOLUTION}) — module-based scores below are weak evidence.", err = true)
        }
        echo("")
        echo("module locality        ${fmt(m.moduleLocality)}  (${m.localUnits}/${m.declaredMultiFileUnits} declared-module units contained in one module)")
        echo("  adjusted for chance  ${fmt(m.adjustedLocality)}  (contribution of the module structure beyond random placement; N/A when one module makes the question meaningless)")
        // isVisible also drops files absent at HEAD, so attribute this only when a role
        // filter is actually in play.
        val hiddenHubs = m.hubCount - m.hubFiles.size
        echo("hub-free change rate   ${fmt(m.hubFreeRate)}  (${m.hubAvoidingUnits}/${m.multiFileUnits} units avoid the " +
            "${m.hubCount} hub files${if (hiddenHubs > 0) ", $hiddenHubs not listed" else ""})" +
            if (m.hubFreeRate == null) "  [needs >= 6 declared modules in multi-file changes]" else "")
        m.hubFiles.take(3).forEach { echo("                         hub: $it") }
        echo("boundary integrity     ${fmt(m.boundaryIntegrity)}  (${m.hotspotFreeCrossUnits}/${m.crossModuleUnits} cross-module units avoid the ${m.boundaryHotspots} recurring hotspot pairs)")
        m.topHotspot?.let { p ->
            val perYear = "%.1f".format(p.together / m.windowYears)
            val (la, lb) = distinguishingLabels(p.a, p.b)
            echo("                         top hotspot: $la x $lb — ~$perYear double-edits/year")
        }
        echo("")
        // Every score above is a function of the module partition. When that partition
        // is directory names, "rich structure" would be a statement about folders —
        // the same reason the module findings are withheld.
        val modules = ModuleGate.report(setup.context.moduleDetection)
        val adjusted = m.adjustedLocality
        if (adjusted != null && !modules.moduleFindingsEnabled) {
            echo(
                "reading: withheld — these scores are computed over ${modules.declaredModuleCount} declared " +
                    "module${if (modules.declaredModuleCount == 1) "" else "s"}, so the partition above is mostly " +
                    "directory names. Declare roots with --module-root and re-run before reading them as structure.",
            )
            echo("")
        } else if (adjusted != null) {
            val richStructure = m.effectiveModules >= 3
            val respected = adjusted >= 0.5
            val reading = when {
                !richStructure && respected ->
                    "little declared structure, well respected — the lever is extracting modules (cochange guide extract-module); expect effective modules to rise without adjusted locality collapsing"
                richStructure && !respected ->
                    "rich structure, frequently violated — the lever is aligning boundaries (cochange guide align-boundaries) and taming hubs (guide reduce-change-tax)"
                richStructure && respected ->
                    "rich structure, well respected — watch the trend; hotspot and hub diagnostics above point at the residual friction"
                else ->
                    "little structure and low compliance — start from clusters (cochange guide extract-module) before trusting these scores"
            }
            echo("reading: ${"%.1f".format(m.effectiveModules)} effective modules x ${"%.0f".format(adjusted * 100)}% adjusted locality — $reading")
            echo("")
        }
        echo("Scores are higher-is-better. All but one are shares of change units; adjusted locality " +
            "is chance-corrected and can go negative (below what random placement would give). Read module locality TOGETHER with effective modules: a coarse partition is easy to comply with, so raising structure and keeping compliance is the goal. Trend within one repository under the same options; absolute values are not comparable across repos.")
    }

    private fun fmt(v: Double?) = if (v == null) "  N/A" else "%5.1f%%".format(v * 100)
}

class CompareCommand : CliktCommand(
    name = "compare",
    help = "Compare co-change participation between a baseline and a recent window — what's heating up vs cooling down.",
) {
    private val path by argument(help = "Path to the Git repository").default(".")
    private val history by HistoryOptions()
    private val baseline by option("--baseline", help = "Baseline window, e.g. '180d' or '6 months ago'").required()
    private val recent by option("--recent", help = "Recent window, e.g. '30d' or '1 month ago'").required()
    private val minCount by option("--min-count", help = "Minimum participation in either window to be listed").int().restrictTo(min = 1).default(3)
    private val top by option("--top", help = "Number of movers to show per direction").int().restrictTo(min = 1).default(10)
    private val category by option("--category", help = "Only files in this category (source, config, build, docs, generated)")
    private val asJson by option("--json", help = "Machine-readable output for weekly tracking").flag()

    override fun run() {
        val repo = File(path).canonicalFile
        val base = history.toAnalysisOptions()
        // --since is inherited from the shared history options but has no meaning here:
        // this command defines its own two windows. Silently overriding it would leave an
        // accepted argument with no effect on the answer.
        if (base.since != null) {
            throw CliktError(
                "compare defines its own windows — use --baseline and --recent instead of --since " +
                    "(--since '${base.since}' would have been ignored).",
            )
        }
        val baseSetup = Analysis.contextFor(repo, base.copy(since = windowSince(baseline)))
        // Both windows must be counted in the same unit. --change-unit auto resolves
        // per window, so a repo that moved from merge commits to squashes would have
        // its recent window counted at a finer granularity — and every rate would
        // shift for that reason alone, with nothing in the output saying so. Pin the
        // recent window to whatever the baseline resolved to.
        val recentSetup = Analysis.contextFor(
            repo,
            base.copy(since = windowSince(recent), changeUnit = baseSetup.changeUnitName),
        )
        val baseCtx = baseSetup.context
        val recentCtx = recentSetup.context

        // What the recent window would have chosen on its own. A difference means the
        // history's shape changed mid-window — worth stating, because it is the kind of
        // change that moves rates without any file becoming more central.
        val recentAlone = if (base.changeUnit == "auto") {
            ChangeUnits.resolve("auto", repo, base.branch, windowSince(recent)).strategy.name
        } else {
            baseSetup.changeUnitName
        }

        // --category is passed in, not applied afterwards: the summary has to describe the
        // same set the listing does, or `--category source` reports movers it does not show.
        val computed = Compare.of(baseCtx, recentCtx, minCount) { file ->
            category == null || recentCtx.categoryOf(file) == category || baseCtx.categoryOf(file) == category
        }
        val moves = computed.moves

        // Report the denominator the rates were actually divided by (multi-file units),
        // alongside each window's total activity — printing only the latter next to a
        // percentage made every line look like it didn't add up.
        val baseWindow = Compare.Window(baseline, baseSetup.resolvedOptions.since, baseCtx.changes.size, computed.baselineMultiFile)
        val recentWindow = Compare.Window(recent, recentSetup.resolvedOptions.since, recentCtx.changes.size, computed.recentMultiFile)

        if (asJson) {
            echo(Compare.encode(
                // Both setups' caveats: a malformed --recent resolves to now and every
                // established file appears to cool to zero, which must not look clean.
                context = RunContext.of(baseSetup).let { ctx ->
                    ctx.copy(
                        warnings = baseSetup.warnings.map { it.copy(scope = "baseline") } +
                            recentSetup.warnings.map { it.copy(scope = "recent") },
                    )
                },
                minCount = minCount, category = category,
                baseline = baseWindow, recent = recentWindow,
                comparison = computed.copy(moves = moves),
                recentWindowAloneWouldUse = recentAlone,
            ))
            return
        }

        for ((label, setup) in listOf("baseline" to baseSetup, "recent" to recentSetup)) {
            for (w in setup.warnings) {
                val tag = if (w.severity == AnalysisWarning.WARNING) "WARNING" else "note"
                echo("$tag ($label window): ${w.message}", err = true)
            }
        }
        // Both windows resolve their own conditions, so a caveat belongs to one of them.
        if (recentAlone != baseSetup.changeUnitName) {
            echo(
                "WARNING: the recent window alone would be counted as \"$recentAlone\", not " +
                    "\"${baseSetup.changeUnitName}\" — the history's shape changed. Both windows are counted as " +
                    "\"${baseSetup.changeUnitName}\" so the rates stay comparable, but the recent window's units are " +
                    "a worse fit for it than the baseline's.",
                err = true,
            )
        }
        echo("baseline: $baseline (${baseWindow.multiFileChanges} multi-file of ${baseWindow.logicalChanges} units)   " +
            "recent: $recent (${recentWindow.multiFileChanges} multi-file of ${recentWindow.logicalChanges} units)" +
            if (category != null) "   category: $category" else "")
        echo("change unit: ${baseSetup.changeUnitName} (${baseSetup.changeUnitReason}), applied to both windows")
        if (moves.isEmpty()) {
            echo("No files reached --min-count ($minCount) in either window. Widen the windows or lower --min-count.")
            return
        }
        // Computed over every mover, so a role filter changes the listing and not the trend.
        val summary = computed.summary
        echo("summary: ${summary.heating} heating, ${summary.cooling} cooling, " +
            "mean shift ${"%.1f".format(summary.meanAbsShift * 100)}pp per mover " +
            "(over ${summary.files} movers at --min-count $minCount" +
            (if (summary.files != moves.size) "; ${moves.size} listed below" else "") + ")")

        fun pct(v: Double) = "%3.0f%%".format(v * 100)
        fun line(m: Compare.Move) = "  " + m.file.padEnd(52) +
            " ${pct(m.baselineRate)} -> ${pct(m.recentRate)}   (baseline ${m.baselineCount}, recent ${m.recentCount})"

        val heating = moves.filter { it.delta > 0 }.sortedByDescending { it.delta }.take(top)
        val cooling = moves.filter { it.delta < 0 }.sortedBy { it.delta }.take(top)

        echo("")
        echo("heating up — larger share of changes recently:")
        if (heating.isEmpty()) echo("  (none)") else heating.forEach { echo(line(it)) }
        echo("")
        echo("cooling down — was more central, quieter lately:")
        if (cooling.isEmpty()) echo("  (none)") else cooling.forEach { echo(line(it)) }
        echo("")
        echo("Rates are each file's share of that window's multi-file changes. Overlapping windows are fine — the point is the shift in share, not absolute counts.")
    }

    /** Accept both `30d` shorthand and raw git --since expressions. */
    private fun windowSince(value: String): String {
        val days = Regex("^(\\d+)d$").find(value.trim())
        return if (days != null) "${days.groupValues[1]} days ago" else value
    }
}

class Detectors : CliktCommand(
    name = "detectors",
    help = "List what this tool can find.",
) {
    override fun run() {
        for (d in defaultDetectors()) {
            echo("${d.type}")
            echo("  ${d.description}")
        }
    }
}

class Inspect : CliktCommand(
    name = "inspect",
    help = "Show full evidence for one finding (JSON).",
) {
    private val id by argument(help = "Finding id, e.g. finding-3")
    private val path by argument(help = "Path to the Git repository").default(".")
    private val analysis by option("--analysis", help = "Which saved analysis snapshot to read (default: ${Store.DEFAULT_NAME})").default(Store.DEFAULT_NAME)

    override fun run() {
        val repo = File(path).canonicalFile
        val result = loadOrFail(path, analysis)
        val finding = result.findings.find { it.id == id }
            ?: throw CliktError("no finding '$id' — available: ${result.findings.joinToString(", ") { it.id }}")
        // Resolve the hashes now rather than storing subjects and churn in the cache:
        // an older snapshot benefits too, and the cache stays a cache.
        val findingFiles = finding.files.toSet()
        val byHash = runCatching {
            GitLog.commitSummaries(repo, finding.detail.supportingChanges.flatMap { it.hashes }, findingFiles)
                .associateBy { it.hash }
        }.getOrDefault(emptyMap())
        val supporting = finding.detail.supportingChanges.map { unit ->
            SupportingChangeReport(
                hashes = unit.hashes,
                commits = unit.hashes.mapNotNull { byHash[it] },
                // Recorded when the analysis ran. Rebuilding it from churn was wrong for
                // any file renamed after the commit: the log is read with -M, so the
                // finding names the current path while the commit names the old one.
                filesTouched = unit.filesTouched,
            )
        }
        val current = runCatching { GitLog.headCommit(repo, null) }.getOrNull()
        echo(reportJson.encodeToString(
            InspectReport.serializer(),
            InspectReport(
                analysis = analysis,
                repo = result.repo,
                branch = result.branch,
                headCommit = result.headCommit,
                shallow = result.shallow,
                changeUnit = result.changeUnit,
                analyzedCommits = result.analyzedCommits,
                logicalChanges = result.logicalChanges,
                requestedOptions = result.requestedOptions,
                options = result.options,
                moduleDetection = result.moduleDetection,
                skippedDetectors = result.skippedDetectors,
                hiddenByRole = result.hiddenByRole,
                warnings = result.warnings,
                stale = current != null && result.headCommit.isNotEmpty() && current != result.headCommit,
                finding = finding,
                supportingChanges = supporting,
            ),
        ))
    }
}

/**
 * The options a re-computing command (metrics/pairs/clusters) should use: its
 * own [history] options normally, or — when [analysis] is given — the exact
 * conditions the named snapshot was produced under, so its numbers line up with
 * that analysis instead of silently defaulting to all-history/no-excludes.
 */
private fun resolveOptions(path: String, analysis: String?, history: HistoryOptions): AnalysisOptions {
    if (analysis == null) return history.toAnalysisOptions()
    val loaded = loadOrFail(path, analysis)
    return loaded.options
        ?: throw CliktError(
            "analysis '$analysis' predates recorded conditions — re-run: cochange analyze $path --save $analysis",
        )
}

private fun loadOrFail(path: String, analysis: String = Store.DEFAULT_NAME): AnalysisResult {
    val repo = File(path).canonicalFile
    Store.validateName(analysis)
    val result = Store.load(repo, analysis) ?: run {
        val saved = Store.list(repo)
        val hint = when {
            saved.isEmpty() -> "run: cochange analyze $path" + if (analysis != Store.DEFAULT_NAME) " --save $analysis" else ""
            analysis !in saved -> "saved analyses: ${saved.joinToString(", ")}"
            else -> "run: cochange analyze $path --save $analysis"
        }
        throw CliktError("no analysis '$analysis' for $repo — $hint")
    }
    if (result.schemaVersion != SCHEMA_VERSION) {
        throw CliktError(
            "analysis '$analysis' uses schema v${result.schemaVersion}; this cochange reads v$SCHEMA_VERSION. " +
                "Field meanings changed, so it won't be reinterpreted — re-run: cochange analyze $path" +
                if (analysis != Store.DEFAULT_NAME) " --save $analysis" else "",
        )
    }
    if (result.headCommit.isNotEmpty()) {
        val current = runCatching { GitLog.headCommit(repo, null) }.getOrNull()
        if (current != null && current != result.headCommit) {
            System.err.println("WARNING: analysis is stale (ran at ${result.headCommit.take(10)}, HEAD is now ${current.take(10)}) — re-run cochange analyze")
        }
    }
    return result
}

private fun printBanner(setup: AnalysisSetup, echo: (String, Boolean) -> Unit, compact: Boolean = false) {
    fun out(line: String) = echo(line, false)
    fun err(line: String) = echo(line, true)
    val since = setup.options.since
    if (!compact) out("repo: ${setup.repo}")
    // Compact commands (clusters/metrics/pairs) also recompute from the git log, so the
    // window must stay visible here too or an unset --since silently means all history.
    out("branch: ${setup.options.branch ?: "HEAD"}  since: ${since ?: "(all history)"}")
    val pinned = setup.resolvedOptions.since
    if (pinned != null && pinned != since && setup.warnings.none { it.code == AnalysisWarning.WINDOW_IS_NOW }) {
        out("  window pinned to: $pinned")
    }
    out("change unit: ${setup.changeUnitName} (${setup.changeUnitReason})" +
        if (compact) "; ${setup.changes.size} change units" else "")
    if (!compact && setup.changeUnitName == "author-window") {
        out("  window: ${setup.options.groupWindowMin}m same-author, max ${setup.options.maxFilesPerCommit} files/commit")
    }
    // One source for the caveats, so the banner and the JSON can never disagree
    // about whether these numbers are quotable.
    for (warning in setup.warnings) {
        val label = if (warning.severity == AnalysisWarning.WARNING) "WARNING" else "note"
        err("$label: ${warning.message}")
    }
}

/** "(N hidden by --exclude-role)" when a cluster's listing is shorter than its counts. */
private fun hiddenNote(cluster: Clusters.Cluster): String =
    if (cluster.hiddenFiles > 0) " (${cluster.hiddenFiles} hidden by --exclude-role)" else ""

/**
 * Reports how the structure the findings depend on was resolved, and whether any detector
 * was withheld because it wasn't resolved well enough. Printed before the findings so the
 * reader knows what they are trusting, and printed even when there are no findings —
 * "nothing found" and "nothing was allowed to run" are different answers.
 */
private fun printTrustNotes(result: AnalysisResult, echo: (String) -> Unit) {
    // Warnings the ANALYSIS added (not the setup, which the banner already printed):
    // the sample-size caveat is only knowable once the thresholds are known.
    for (w in result.warnings.filter { it.code == AnalysisWarning.FEW_CHANGE_UNITS }) {
        echo("WARNING: ${w.message}")
    }
    if (result.hiddenByRole.isNotEmpty()) {
        val breakdown = result.hiddenByRole.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }
        echo("hidden by --exclude-role: ${result.hiddenByRole.values.sum()} files ($breakdown)")
        echo("  These files were still counted — the co-change numbers cover the full history; only the output hides them.")
    }
    val modules = result.moduleDetection ?: return
    echo("module detection [derived]: ${modules.methods.joinToString(", ").ifEmpty { "none — no build files or --module-root globs matched" }}")
    echo("  coverage: ${pct(modules.coverage)} of ${modules.totalFiles} files under a declared module root " +
        "(${modules.moduleCount} module${if (modules.moduleCount == 1) "" else "s"}, " +
        "${modules.declaredModuleCount} declared, trust=${modules.trust})")
    echo("  ${modules.note}")
    for (s in result.skippedDetectors) {
        echo("  WITHHELD ${s.type}: findings of this type were not produced — see above.")
    }
    echo("")
}

private fun printFindingsSummary(result: AnalysisResult, echo: (String) -> Unit) {
    printTrustNotes(result, echo)
    if (result.findings.isEmpty()) {
        // "No findings" has several very different causes, and telling the reader to lower
        // a threshold is the right advice for only one of them.
        val withheld = result.skippedDetectors.map { it.type }
        val ran = (result.detectorTypes.toSet() - withheld.toSet()).size
        echo(
            when {
                withheld.isNotEmpty() && ran == 0 ->
                    "No findings: every detector was withheld (${withheld.joinToString(", ")}) — see the reason above. " +
                        "This says nothing about the repository yet."
                withheld.isNotEmpty() ->
                    "No findings from the detectors that ran; ${withheld.joinToString(", ")} " +
                        "${if (withheld.size == 1) "was" else "were"} withheld (see above). " +
                        "For what did run, try lowering --min-support / --min-confidence."
                else ->
                    "No findings above thresholds. Try lowering --min-support / --min-confidence, " +
                        "or widen the window — see: cochange guide small-repo."
            },
        )
        // Raw evidence is never gated, so point at what is still available.
        echo("Raw evidence is unaffected: cochange pairs . --min-support 2  /  cochange clusters . --min-support 2")
        return
    }
    // "Review candidates", not "findings you should act on": every line below is
    // an interpretation of the raw co-change evidence, not a verified defect.
    echo("Review candidates (${result.findings.size}) — heuristic interpretations of the co-change evidence")
    for (f in result.findings) {
        echo("")
        echo("${f.id} [${f.type}/${f.category}] impact=${f.impact}${if (f.effort.isNotEmpty()) " effort=${f.effort}" else ""} confidence=${f.confidence}")
        echo("  ${f.summary}")
        echo("  ${f.detail.observation}")
    }
}

fun main(args: Array<String>) {
    try {
        Cochange()
            .subcommands(Analyze(), Findings(), Inspect(), Pairs(), ClustersCommand(), MetricsCommand(), CompareCommand(), Detectors(), GuideCommand())
            .main(args)
    } catch (e: IllegalStateException) {
        // Expected operational failures (git errors, empty repos) — no stack trace.
        System.err.println("error: ${e.message}")
        exitProcess(1)
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}
