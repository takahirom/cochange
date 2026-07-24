package io.github.takahirom.cochange

import java.io.File

/**
 * Everything the pipeline needs to know about how to read a repository's
 * history. Raw analysis facts only — finding thresholds stay with the
 * detectors and commands.
 */
data class AnalysisOptions(
    val branch: String? = null,
    val since: String? = null,
    val changeUnit: String = "auto",
    val maxFilesPerCommit: Int = 50,
    val groupWindowMin: Long = 30,
    val extraExcludes: List<String> = emptyList(),
    val moduleRoots: List<String> = emptyList(),
)

/**
 * The result of setting a repository up for analysis: the co-change index
 * plus the facts every command's banner reports (what was resolved and why,
 * and any trust caveats).
 */
class AnalysisSetup(
    val repo: File,
    val options: AnalysisOptions,
    val context: AnalysisContext,
    val changeUnitName: String,
    val changeUnitReason: String,
    val shallow: Boolean,
    val headCommit: String,
) {
    val changes: List<LogicalChange> get() = context.changes
}

/**
 * The single seam from "a repository on disk" to "an AnalysisContext ready
 * for detectors": validation, change-unit resolution, history reading,
 * grouping, boundary detection. Every command goes through here so they can
 * never analyze different logical histories while looking comparable.
 */
object Analysis {
    val DEFAULT_EXCLUDES = listOf(
        "**/*.lock", "*.lock", "**/generated/**", "**/build/**",
        "**/*.png", "**/*.jpg", "**/*.webp", "**/*.svg",
        "**/*.jar", "**/*.bin", "**/*.pb",
    )

    fun contextFor(repo: File, options: AnalysisOptions): AnalysisSetup {
        check(GitLog.isRepository(repo)) { "$repo is not a git repository" }
        val rev = options.branch ?: "HEAD"
        check(GitLog.commitExists(repo, rev)) { "no commits found on '$rev' in $repo" }

        val resolved = ChangeUnits.resolve(options.changeUnit, repo, options.branch, options.since)
        val strategy = when (resolved.strategy) {
            is AuthorWindowChangeUnit -> AuthorWindowChangeUnit(
                windowSec = options.groupWindowMin * 60,
                maxFilesPerCommit = options.maxFilesPerCommit,
            )
            is CommitChangeUnit -> CommitChangeUnit(maxFilesPerCommit = options.maxFilesPerCommit)
            else -> resolved.strategy
        }
        val excludes = DEFAULT_EXCLUDES + options.extraExcludes
        val changes = strategy.changeUnits(repo, options.branch, options.since, excludes)
        val headFiles = GitLog.headFiles(repo, options.branch)
        val generated = GitLog.generatedFiles(repo, rev, headFiles)
        return AnalysisSetup(
            repo = repo,
            options = options,
            context = AnalysisContext(changes, Boundaries(headFiles, options.moduleRoots), headFiles, generated),
            changeUnitName = strategy.name,
            changeUnitReason = resolved.reason,
            shallow = GitLog.isShallow(repo),
            headCommit = GitLog.headCommit(repo, options.branch),
        )
    }
}
