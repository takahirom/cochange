package io.github.takahirom.cochange

import java.io.File

/**
 * Defines what counts as "one change" for co-change statistics. Selected via
 * `analyze --change-unit`; AUTO picks MERGE when the history is merge-based
 * (each merge's side branch is one PR) and AUTHOR_WINDOW for squash-merge
 * histories where commits arrive flattened.
 */
interface ChangeUnitStrategy {
    val name: String
    fun changeUnits(repo: File, branch: String?, since: String?, excludes: List<String>): List<LogicalChange>
}

class CommitChangeUnit(
    private val maxFilesPerCommit: Int = 50,
) : ChangeUnitStrategy {
    override val name = "commit"
    override fun changeUnits(repo: File, branch: String?, since: String?, excludes: List<String>): List<LogicalChange> =
        GitLog.readCommits(repo, branch, since, excludes)
            .filter { it.files.size in 1..maxFilesPerCommit }
            .map { LogicalChange(listOf(it)) }
}

class AuthorWindowChangeUnit(
    private val windowSec: Long = 30 * 60,
    private val maxFilesPerCommit: Int = 50,
) : ChangeUnitStrategy {
    override val name = "author-window"
    override fun changeUnits(repo: File, branch: String?, since: String?, excludes: List<String>): List<LogicalChange> =
        Grouping.group(
            GitLog.readCommits(repo, branch, since, excludes),
            windowSec = windowSec,
            maxFilesPerCommit = maxFilesPerCommit,
        )
}

/**
 * Walks the first-parent chain: each merge commit becomes one change unit
 * carrying the entire side branch's diff (≈ one PR), and direct mainline
 * commits count as their own unit.
 */
class MergeBasedChangeUnit(
    private val maxFilesPerChange: Int = 80,
) : ChangeUnitStrategy {
    override val name = "merge"
    override fun changeUnits(repo: File, branch: String?, since: String?, excludes: List<String>): List<LogicalChange> =
        GitLog.readCommits(repo, branch, since, excludes, firstParent = true)
            .filter { it.files.size in 1..maxFilesPerChange }
            .map { LogicalChange(listOf(it)) }
}

data class ResolvedChangeUnit(val strategy: ChangeUnitStrategy, val reason: String)

object ChangeUnits {
    /** A mainline merge dragging in more side commits than this looks like a release-branch merge, not a PR. */
    private const val RELEASE_BRANCH_COMMITS_PER_MERGE = 15.0

    /**
     * AUTO: merge-based history if a meaningful share of mainline commits are
     * merges (30% keeps occasional manual merges in a squash-merge repo from
     * flipping the mode) — unless each merge drags in so many commits that the
     * branch looks like a git-flow release-only mainline, where PR granularity
     * lives on a development branch instead; then fall back to author-window.
     */
    fun resolve(mode: String, repo: File, branch: String?, since: String?): ResolvedChangeUnit = when (mode) {
        "merge" -> ResolvedChangeUnit(MergeBasedChangeUnit(), "explicitly requested")
        "author-window" -> ResolvedChangeUnit(AuthorWindowChangeUnit(), "explicitly requested")
        "commit" -> ResolvedChangeUnit(CommitChangeUnit(), "explicitly requested")
        "auto" -> decide(
            mainline = GitLog.countCommits(repo, branch, since, firstParent = true),
            merges = GitLog.countCommits(repo, branch, since, firstParent = true, mergesOnly = true),
            total = GitLog.countCommits(repo, branch, since),
        )
        else -> error("Unknown change unit '$mode' (expected auto, merge, author-window, or commit)")
    }

    /** The AUTO decision as a pure function of commit counts, so it is testable without a repository. */
    fun decide(mainline: Long, merges: Long, total: Long): ResolvedChangeUnit {
        val commitsPerMerge = if (merges > 0) (total - mainline).toDouble() / merges else 0.0
        return when {
            mainline == 0L || merges.toDouble() / mainline < 0.3 -> ResolvedChangeUnit(
                AuthorWindowChangeUnit(),
                "auto: linear history (${pct(if (mainline > 0) merges.toDouble() / mainline else 0.0)} of mainline commits are merges)",
            )
            commitsPerMerge > RELEASE_BRANCH_COMMITS_PER_MERGE -> ResolvedChangeUnit(
                AuthorWindowChangeUnit(),
                "auto: mainline looks like a release-only branch (~${commitsPerMerge.toInt()} commits per merge) — " +
                    "PR granularity likely lives on a development branch; consider --branch develop",
            )
            else -> ResolvedChangeUnit(
                MergeBasedChangeUnit(),
                "auto: merge-based history (${pct(merges.toDouble() / mainline)} of mainline commits are merges, " +
                    "~${"%.1f".format(commitsPerMerge)} commits per merge)",
            )
        }
    }
}
