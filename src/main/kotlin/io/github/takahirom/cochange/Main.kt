package io.github.takahirom.cochange

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
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

class Analyze : CliktCommand(
    name = "analyze",
    help = "Analyze a repository's change history and produce findings.",
) {
    private val path by argument(help = "Path to the Git repository").default(".")
    private val branch by option("--branch", help = "Branch to analyze (default: HEAD)")
    private val since by option("--since", help = "History window, e.g. '2 years ago' (git --since syntax)")
    private val changeUnit by option("--change-unit", help = "What counts as one change: auto, merge (first-parent, one merge = one PR), author-window, commit").default("auto")
    private val maxFiles by option("--max-files", help = "Skip commits touching more files than this").int().restrictTo(min = 1).default(50)
    private val groupWindowMin by option("--group-window", help = "Minutes within which same-author commits form one logical change (author-window mode)").long().restrictTo(1L..10_000L).default(30)
    private val minSupport by option("--min-support", help = "Minimum co-change count for a pair finding").int().restrictTo(min = 1).default(5)
    private val minConfidence by option("--min-confidence", help = "Minimum co-change ratio for a pair finding").double().restrictTo(0.0, 1.0).default(0.6)
    private val exclude by option("--exclude", help = "Glob to exclude (repeatable, adds to defaults)").multiple()
    private val asJson by option("--json", help = "Print the full result as JSON").flag()

    override fun run() {
        val repo = File(path).canonicalFile
        val start = System.currentTimeMillis()
        val setup = Analysis.contextFor(repo, AnalysisOptions(
            branch = branch, since = since, changeUnit = changeUnit,
            maxFilesPerCommit = maxFiles, groupWindowMin = groupWindowMin, extraExcludes = exclude,
        ))
        if (!asJson) {
            printBanner(setup, { m, e -> echo(m, err = e) })
            echo("thresholds: min-support=$minSupport min-confidence=$minConfidence")
            echo("")
        }
        val changes = setup.changes
        val findings = Analyzer(minSupport, minConfidence).analyze(setup.context)

        val result = AnalysisResult(
            repo = repo.path,
            branch = branch ?: "HEAD",
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
    private val branch by option("--branch")
    private val since by option("--since", help = "History window, e.g. '2 years ago'")
    private val changeUnit by option("--change-unit").default("auto")
    private val minSupport by option("--min-support", help = "Minimum co-change count to list").int().default(5)
    private val top by option("--top", help = "Number of pairs to show").int().default(50)
    private val file by option("--file", help = "Only pairs involving a path containing this substring")
    private val category by option("--category", help = "Only pairs in this category (source, config, build, docs)")
    private val exclude by option("--exclude").multiple()

    override fun run() {
        val repo = File(path).canonicalFile
        val setup = Analysis.contextFor(repo, AnalysisOptions(
            branch = branch, since = since, changeUnit = changeUnit, extraExcludes = exclude,
        ))
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
                val conf = p.together.toDouble() / minOf(p.countA, p.countB)
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
    private val branch by option("--branch")
    private val since by option("--since", help = "History window, e.g. '2 years ago'")
    private val changeUnit by option("--change-unit").default("auto")
    private val minSupport by option("--min-support", help = "Minimum co-change count for an edge").int().restrictTo(min = 1).default(10)
    private val minConfidence by option("--min-confidence", help = "Minimum Jaccard similarity for an edge (together / either)").double().restrictTo(0.0, 1.0).default(0.25)
    private val top by option("--top", help = "Number of clusters to show").int().default(10)
    private val category by option("--category", help = "Only files in this category (source, config, build, docs)")
    private val exclude by option("--exclude").multiple()

    override fun run() {
        val repo = File(path).canonicalFile
        val setup = Analysis.contextFor(repo, AnalysisOptions(
            branch = branch, since = since, changeUnit = changeUnit, extraExcludes = exclude,
        ))
        val context = setup.context
        val boundaries = context.boundaries
        val clusters = Clusters.build(context, minSupport, minConfidence) {
            category == null || FileCategory.of(it) == category
        }

        printBanner(setup, { m, e -> echo(m, err = e) }, compact = true)
        echo("edge thresholds: min-support=$minSupport min-confidence=$minConfidence")
        if (clusters.isEmpty()) {
            echo("No clusters above thresholds. Try lowering --min-support / --min-confidence.")
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
    if (!compact) {
        out("repo: ${setup.repo}")
        out("branch: ${setup.options.branch ?: "HEAD"}  since: ${setup.options.since ?: "(all history)"}")
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
            .subcommands(Analyze(), Findings(), Inspect(), Pairs(), ClustersCommand(), Detectors(), GuideCommand())
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
