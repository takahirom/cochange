package io.github.takahirom.cochange

import kotlinx.serialization.Serializable

data class Commit(
    val hash: String,
    val author: String,
    val epochSec: Long,
    val message: String,
    val files: List<String>,
)

/**
 * One logical change unit: consecutive commits by the same author within a
 * time window, merged into a single set of files.
 */
data class LogicalChange(
    val commits: List<Commit>,
) {
    val files: Set<String> = commits.flatMap { it.files }.toSet()
    val author: String = commits.first().author
    val hashes: List<String> = commits.map { it.hash }
}

/**
 * Shortest path suffixes that tell two files apart: their basenames when those
 * differ, otherwise enough trailing directories to disambiguate — so a pair of
 * `strings.xml` reads as `values/strings.xml` vs `values-ja/strings.xml`, not
 * `strings.xml x strings.xml`.
 */
fun distinguishingLabels(a: String, b: String): Pair<String, String> {
    val sa = a.split('/')
    val sb = b.split('/')
    var k = 1
    while (true) {
        val la = sa.takeLast(k).joinToString("/")
        val lb = sb.takeLast(k).joinToString("/")
        if (la != lb || (k >= sa.size && k >= sb.size)) return la to lb
        k++
    }
}

@Serializable
data class Finding(
    val id: String,
    val type: String,
    val category: String = "source",
    val summary: String,
    val confidence: Double,
    val impact: String,
    /** Rough effort to act on this finding (none/low/medium/high), so it can be read for ROI, not just impact. Empty when not estimated. */
    val effort: String = "",
    val detail: FindingDetail,
)

/**
 * Classifies files so that always-coupled build files and docs don't crowd
 * production-code findings out of the ranking.
 */
object FileCategory {
    const val SOURCE = "source"
    const val BUILD = "build"
    const val DOCS = "docs"
    const val CONFIG = "config"
    const val GENERATED = "generated"

    /** Display and ranking priority: production code first, generated code last (mostly noise). */
    val priority = listOf(SOURCE, CONFIG, BUILD, DOCS, GENERATED)

    private val buildNames = setOf(
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "pom.xml", "package.json", "package-lock.json", "cargo.toml", "go.mod", "go.sum",
        "gemfile", "makefile", "dockerfile",
    )
    private val configExtensions = setOf("yml", "yaml", "json", "properties", "toml", "cfg", "ini")

    // Deliberately conservative: tool names (mockolo, sourcery) and dotted
    // markers that legitimate hand-written files don't carry, so a file called
    // GeneratedContentView.swift is NOT swept up. The authoritative signal is
    // `.gitattributes linguist-generated` (resolved per-repo); these catch the
    // common cases when a repo hasn't declared them.
    private val generatedNameMarkers = listOf(".mockolo.", "sourcery", ".generated.")
    private val generatedNameSuffixes = listOf(
        ".g.dart", ".freezed.dart", ".pb.go", ".pb.swift", ".pb.cc", ".pb.h",
        ".pbobjc.h", ".pbobjc.m", "_pb2.py", "_pb2.pyi", ".min.js", ".min.css",
    )

    /** True for files that match a built-in generated-artifact naming convention. */
    fun looksGenerated(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return generatedNameMarkers.any { name.contains(it) } ||
            generatedNameSuffixes.any { name.endsWith(it) }
    }

    fun of(path: String): String {
        val name = path.substringAfterLast('/').lowercase()
        val ext = name.substringAfterLast('.', "")
        return when {
            looksGenerated(path) -> GENERATED
            name in buildNames || name.endsWith(".gradle") || name.endsWith(".gradle.kts") ||
                name == "libs.versions.toml" || name.endsWith(".versions.toml") -> BUILD
            ext == "md" || ext == "adoc" || ext == "rst" || path.startsWith("docs/") -> DOCS
            ext in configExtensions -> CONFIG
            else -> SOURCE
        }
    }

    /** A pair inherits the least source-like side: one build file makes the pair "build". */
    fun ofPair(a: String, b: String): String {
        val (ca, cb) = of(a) to of(b)
        return if (priority.indexOf(ca) >= priority.indexOf(cb)) ca else cb
    }
}

@Serializable
data class FindingDetail(
    val observation: String,
    val interpretations: List<String>,
    val counterSignals: List<String>,
    val supportingChanges: List<String>,
    val metrics: Map<String, String> = emptyMap(),
    /** For split_candidate: the independent partner clusters, in full, with their support and active period. */
    val groups: List<SplitGroup> = emptyList(),
)

/**
 * One independent partner cluster of a split_candidate: the full file list (no
 * truncation), how strongly it co-changes with the candidate, and the span of
 * dates over which that coupling was active — so a reader can tell "two
 * responsibilities" apart from "old vs new era of one responsibility".
 */
@Serializable
data class SplitGroup(
    val files: List<String>,
    val support: Int,
    val activeFrom: String,
    val activeTo: String,
)

@Serializable
data class AnalysisResult(
    val repo: String,
    val branch: String,
    val headCommit: String = "",
    val shallow: Boolean = false,
    val changeUnit: String = "author-window",
    val analyzedCommits: Int,
    val logicalChanges: Int,
    val findings: List<Finding>,
    /** The exact conditions this snapshot was produced under, so later commands can reuse them. Null for pre-0.5 caches. */
    val options: AnalysisOptions? = null,
)
