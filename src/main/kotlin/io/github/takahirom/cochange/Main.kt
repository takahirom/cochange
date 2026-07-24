package io.github.takahirom.cochange

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
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

    fun toAnalysisOptions() = AnalysisOptions(
        branch = branch, since = since, changeUnit = changeUnit,
        maxFilesPerCommit = maxFiles, groupWindowMin = groupWindowMin, extraExcludes = exclude,
        moduleRoots = moduleRoot,
    )
}

class Analyze : CliktCommand(
    name = "analyze",
    help = "Analyze a repository's change history and produce findings.",
) {
    private val path by argument(help = "Path to the Git repository").default(".")
    private val history by HistoryOptions()
    private val minSupport by option("--min-support", help = "Minimum co-change count for a pair finding").int().restrictTo(min = 1).default(5)
    private val minConfidence by option("--min-confidence", help = "Minimum co-change ratio for a pair finding, P(other | rarer)").double().restrictTo(0.0, 1.0).default(0.6)
    private val asJson by option("--json", help = "Print the full result as JSON").flag()

    override fun run() {
        val repo = File(path).canonicalFile
        val start = System.currentTimeMillis()
        val setup = Analysis.contextFor(repo, history.toAnalysisOptions())
        if (!asJson) {
            printBanner(setup, { m, e -> echo(m, err = e) })
            echo("thresholds: min-support=$minSupport min-confidence=$minConfidence")
            echo("")
        }
        val changes = setup.changes
        val findings = Analyzer(minSupport, minConfidence).analyze(setup.context)

        val result = AnalysisResult(
            repo = repo.path,
            branch = history.branch ?: "HEAD",
            headCommit = setup.headCommit,
            shallow = setup.shallow,
            changeUnit = setup.changeUnitName,
            analyzedCommits = changes.sumOf { it.commits.size },
            logicalChanges = changes.size,
            findings = findings,
        )
        Store.save(repo, result)

        if (asJson) {
            echo(Store.encode(result))
        } else {
            val elapsed = (System.currentTimeMillis() - start) / 1000.0
            echo("Analyzed ${result.analyzedCommits} commits as ${changes.size} change units (unit: ${setup.changeUnitName}) in ${"%.1f".format(elapsed)}s")
            echo("")
            printFindingsSummary(result, ::echo)
            echo("")
            echo("Next: cochange inspect <finding-id> [$repo] — or cochange guide for the full playbook")
        }
    }
}

class Findings : CliktCommand(
    name = "findings",
    help = "List findings from the last analysis.",
) {
    private val path by argument(help = "Path to the Git repository").default(".")
    private val type by option("--type", help = "Only findings of this type (e.g. boundary_mismatch, unstable_hub)")
    private val category by option("--category", help = "Only findings in this category (source, config, build, docs)")
    private val asJson by option("--json").flag()

    override fun run() {
        val loaded = loadOrFail(path)
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
    private val category by option("--category", help = "Only pairs in this category (source, config, build, docs)")

    override fun run() {
        val repo = File(path).canonicalFile
        val setup = Analysis.contextFor(repo, history.toAnalysisOptions())
        val context = setup.context
        val headFiles = context.headFiles
        val boundaries = context.boundaries

        printBanner(setup, { m, e -> echo(m, err = e) }, compact = true)
        echo("together  conf   modules                  pair")
        context.pairs(minTogether = minSupport)
            .filter { it.a in headFiles && it.b in headFiles }
            .filter { file == null || it.a.contains(file!!) || it.b.contains(file!!) }
            .filter { category == null || FileCategory.ofPair(it.a, it.b) == category }
            .sortedByDescending { it.together }
            .take(top)
            .forEach { p ->
                val conf = p.confidence
                val modA = boundaries.moduleOf(p.a)
                val modB = boundaries.moduleOf(p.b)
                val modules = if (modA == modB) "same ($modA)" else "$modA <-> $modB"
                echo("%8d  %.2f   %-22s  %s (%d)  +  %s (%d)".format(
                    p.together, conf, modules, p.a, p.countA, p.b, p.countB))
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
    private val category by option("--category", help = "Only files in this category (source, config, build, docs)")

    override fun run() {
        val repo = File(path).canonicalFile
        val setup = Analysis.contextFor(repo, history.toAnalysisOptions())
        val context = setup.context
        val boundaries = context.boundaries
        val clusters = Clusters.build(context, minSupport, minJaccard) {
            category == null || FileCategory.of(it) == category
        }

        printBanner(setup, { m, e -> echo(m, err = e) }, compact = true)
        echo("edge thresholds: min-support=$minSupport min-jaccard=$minJaccard")
        if (clusters.isEmpty()) {
            echo("No clusters above thresholds. Try lowering --min-support / --min-jaccard.")
            return
        }
        clusters.take(top).forEachIndexed { i, cluster ->
            echo("")
            echo("cluster ${i + 1}: ${cluster.files.size} files, ${cluster.edges.size} strong pairs (pair-support volume ${cluster.pairSupportVolume})")
            for (file in cluster.files) {
                echo("  ${file} (${context.changeCount(file)} changes, ${boundaries.moduleOf(file)})")
            }
            val strongest = cluster.edges.first()
            echo("  strongest pair: ${strongest.a.substringAfterLast('/')} x ${strongest.b.substringAfterLast('/')} " +
                "(${strongest.together} together, jaccard ${"%.2f".format(strongest.jaccard)})")
        }
    }
}

class MetricsCommand : CliktCommand(
    name = "metrics",
    help = "Repository-level scores (all higher-is-better): module locality, hub-free change rate, boundary integrity.",
) {
    private val path by argument(help = "Path to the Git repository").default(".")
    private val history by HistoryOptions()
    private val asJson by option("--json", help = "Machine-readable output for recording runs over time").flag()

    override fun run() {
        val repo = File(path).canonicalFile
        val setup = Analysis.contextFor(repo, history.toAnalysisOptions())
        val m = Metrics.compute(setup.context)

        if (asJson) {
            echo(Metrics.encode(setup, m))
            return
        }
        printBanner(setup, { msg, e -> echo(msg, err = e) }, compact = true)
        echo("window: ${"%.1f".format(m.windowYears)} years  multi-file change units: ${m.multiFileUnits}  " +
            "effective modules: ${"%.1f".format(m.effectiveModules)} (${m.distinctModules} distinct)")
        if (m.lowResolution) {
            echo("WARNING: low-resolution module partition (effective modules < ${Metrics.LOW_RESOLUTION}) — module-based scores below are weak evidence.", err = true)
        }
        echo("")
        echo("module locality        ${fmt(m.moduleLocality)}  (${m.localUnits}/${m.multiFileUnits} multi-file units contained in one module)")
        echo("  adjusted for chance  ${fmt(m.adjustedLocality)}  (contribution of the module structure beyond random placement — a monolith scores ~0 here)")
        echo("hub-free change rate   ${fmt(m.hubFreeRate)}  (${m.hubAvoidingUnits}/${m.multiFileUnits} units avoid the ${m.hubFiles.size} hub files)" +
            if (m.hubFreeRate == null) "  [needs >= 6 modules]" else "")
        m.hubFiles.take(3).forEach { echo("                         hub: $it") }
        echo("boundary integrity     ${fmt(m.boundaryIntegrity)}  (${m.hotspotFreeCrossUnits}/${m.crossModuleUnits} cross-module units avoid the ${m.boundaryHotspots} recurring hotspot pairs)")
        m.topHotspot?.let { p ->
            val perYear = "%.1f".format(p.together / m.windowYears)
            echo("                         top hotspot: ${p.a.substringAfterLast('/')} x ${p.b.substringAfterLast('/')} — ~$perYear double-edits/year")
        }
        echo("")
        val adjusted = m.adjustedLocality
        if (adjusted != null) {
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
        echo("All scores are higher-is-better shares of change units. Read module locality TOGETHER with effective modules: a coarse partition is easy to comply with, so raising structure and keeping compliance is the goal. Trend within one repository under the same options; absolute values are not comparable across repos.")
    }

    private fun fmt(v: Double?) = if (v == null) "  N/A" else "%5.1f%%".format(v * 100)
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

    override fun run() {
        val result = loadOrFail(path)
        val finding = result.findings.find { it.id == id }
            ?: error("no finding '$id' — available: ${result.findings.joinToString(", ") { it.id }}")
        echo(Store.encode(finding))
    }
}

private fun loadOrFail(path: String): AnalysisResult {
    val repo = File(path).canonicalFile
    val result = Store.load(repo) ?: error("no analysis found for $repo — run: cochange analyze $path")
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
    if (since == null) {
        err("note: no --since — computing over all history; pass --since (e.g. '1 year ago') to match the window used elsewhere.")
    }
    if (setup.shallow) err("WARNING: shallow clone — history is truncated, so every ratio below is biased. Run 'git fetch --unshallow' for accurate results.")
    out("change unit: ${setup.changeUnitName} (${setup.changeUnitReason})" +
        if (compact) "; ${setup.changes.size} change units" else "")
    if (!compact && setup.changeUnitName == "author-window") {
        out("  window: ${setup.options.groupWindowMin}m same-author, max ${setup.options.maxFilesPerCommit} files/commit")
    }
}

private fun printFindingsSummary(result: AnalysisResult, echo: (String) -> Unit) {
    if (result.findings.isEmpty()) {
        echo("No findings above thresholds. Try lowering --min-support / --min-confidence.")
        return
    }
    echo("Architecture Findings (${result.findings.size})")
    for (f in result.findings) {
        echo("")
        echo("${f.id} [${f.type}/${f.category}] impact=${f.impact} confidence=${f.confidence}")
        echo("  ${f.summary}")
        echo("  ${f.detail.observation}")
    }
}

fun main(args: Array<String>) {
    try {
        Cochange()
            .subcommands(Analyze(), Findings(), Inspect(), Pairs(), ClustersCommand(), MetricsCommand(), Detectors(), GuideCommand())
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
