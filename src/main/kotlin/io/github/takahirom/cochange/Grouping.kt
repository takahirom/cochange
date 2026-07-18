package io.github.takahirom.cochange

object Grouping {
    /**
     * Groups commits into logical changes: consecutive commits by the same
     * author whose gap is within [windowSec] are one change (fixups, "address
     * review comments" chains). Commits touching more than [maxFilesPerCommit]
     * files are dropped as bulk changes (formatting, mass renames) that would
     * otherwise dominate the pair counts.
     */
    fun group(
        commits: List<Commit>,
        windowSec: Long = 30 * 60,
        maxFilesPerCommit: Int = 50,
        maxFilesPerChange: Int = 80,
    ): List<LogicalChange> {
        val filtered = commits.filter { it.files.isNotEmpty() && it.files.size <= maxFilesPerCommit }
        val byAuthor = filtered.groupBy { it.author }
        val changes = ArrayList<LogicalChange>()
        for ((_, authored) in byAuthor) {
            val sorted = authored.sortedBy { it.epochSec }
            var current = ArrayList<Commit>()
            for (commit in sorted) {
                if (current.isEmpty() || commit.epochSec - current.last().epochSec <= windowSec) {
                    current.add(commit)
                } else {
                    changes.add(LogicalChange(current))
                    current = arrayListOf(commit)
                }
            }
            if (current.isNotEmpty()) changes.add(LogicalChange(current))
        }
        return changes.filter { it.files.size in 1..maxFilesPerChange }
    }
}
