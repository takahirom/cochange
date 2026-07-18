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

@Serializable
data class Finding(
    val id: String,
    val type: String,
    val category: String = "source",
    val summary: String,
    val confidence: Double,
    val impact: String,
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

    /** Display and ranking priority: production code first. */
    val priority = listOf(SOURCE, CONFIG, BUILD, DOCS)

    private val buildNames = setOf(
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "pom.xml", "package.json", "package-lock.json", "cargo.toml", "go.mod", "go.sum",
        "gemfile", "makefile", "dockerfile",
    )
    private val configExtensions = setOf("yml", "yaml", "json", "properties", "toml", "cfg", "ini")

    fun of(path: String): String {
        val name = path.substringAfterLast('/').lowercase()
        val ext = name.substringAfterLast('.', "")
        return when {
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
)
