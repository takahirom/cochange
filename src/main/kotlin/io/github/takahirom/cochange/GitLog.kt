package io.github.takahirom.cochange

import java.io.File
import java.util.concurrent.TimeUnit

private const val COMMIT_SEP = '\u0001'
private const val FIELD_SEP = '\u0002'

object GitLog {
    fun readCommits(
        repo: File,
        branch: String?,
        since: String?,
        excludes: List<String>,
        firstParent: Boolean = false,
    ): List<Commit> {
        val args = buildList {
            add("log")
            if (firstParent) {
                // Mainline only; a merge commit carries its whole side branch's
                // diff against the first parent (≈ one PR as one entry).
                add("--first-parent")
                add("--diff-merges=first-parent")
            } else {
                add("--no-merges")
            }
            add("--name-status")
            add("-M")
            add("--pretty=format:$COMMIT_SEP%H$FIELD_SEP%ae$FIELD_SEP%at$FIELD_SEP%s")
            if (since != null) add("--since=$since")
            add(branch ?: "HEAD")
        }
        val output = runGit(repo, args)
        return parseLog(output, excludes)
    }

    fun headFiles(repo: File, branch: String?): Set<String> =
        runGit(repo, listOf("ls-tree", "-r", branch ?: "HEAD"))
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("160000") } // skip submodule gitlinks
            .map { it.substringAfter('\t') }
            .filter { it.isNotBlank() }
            .toSet()

    fun isRepository(repo: File): Boolean =
        runCatching { runGit(repo, listOf("rev-parse", "--git-dir")) }.isSuccess

    fun commitExists(repo: File, rev: String): Boolean =
        runCatching { runGit(repo, listOf("rev-parse", "--verify", "--quiet", "--end-of-options", "$rev^{commit}")) }.isSuccess

    /** Number of commits reachable from the branch, optionally restricted to the first-parent chain or merges. */
    fun countCommits(
        repo: File,
        branch: String?,
        since: String?,
        firstParent: Boolean = false,
        mergesOnly: Boolean = false,
    ): Long {
        val args = buildList {
            add("rev-list"); add("--count")
            if (firstParent) add("--first-parent")
            if (mergesOnly) add("--merges")
            if (since != null) add("--since=$since")
            add(branch ?: "HEAD")
        }
        return runGit(repo, args).trim().toLong()
    }

    fun isShallow(repo: File): Boolean =
        runGit(repo, listOf("rev-parse", "--is-shallow-repository")).trim() == "true"

    fun headCommit(repo: File, branch: String?): String =
        runGit(repo, listOf("rev-parse", (branch ?: "HEAD"))).trim()

    internal fun runGit(repo: File, args: List<String>): String {
        // quotepath=off keeps non-ASCII paths literal instead of octal-escaped.
        val process = ProcessBuilder(listOf("git", "-c", "core.quotepath=off") + args)
            .directory(repo)
            .redirectErrorStream(false)
            .start()
        // Drain stderr concurrently so a chatty child can't block on a full pipe.
        val err = StringBuilder()
        val errReader = Thread { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        errReader.start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(600, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("git ${args.first()} timed out")
        }
        errReader.join()
        check(process.exitValue() == 0) { "git ${args.joinToString(" ")} failed: ${err.toString().trim()}" }
        return out
    }

    /**
     * Parses `git log --name-status` output. Commits arrive newest-first;
     * renames (R100 old new) are followed so a file's older paths are unified
     * into its newest known path.
     */
    fun parseLog(output: String, excludes: List<String>): List<Commit> {
        val matchers = excludes.map(::globToRegex)
        // old path -> newer path (resolved transitively to the newest path)
        val renameAlias = HashMap<String, String>()

        fun canonical(path: String): String {
            var p = path
            var hops = 0
            while (hops++ < 100) {
                p = renameAlias[p] ?: return p
            }
            return p
        }

        fun excluded(path: String) = matchers.any { it.matches(path) }

        val commits = ArrayList<Commit>()
        for (block in output.splitToSequence(COMMIT_SEP)) {
            if (block.isBlank()) continue
            val lines = block.trim('\n').lines()
            val header = lines.first().split(FIELD_SEP)
            if (header.size < 4) continue
            val files = ArrayList<String>()
            // Renames within one commit are resolved against the pre-commit
            // alias state and applied afterwards, so A->B plus B->C (or swaps)
            // in the same commit can't see each other's aliases.
            val newAliases = ArrayList<Pair<String, String>>()
            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val parts = line.split('\t')
                val status = parts[0]
                when {
                    status.startsWith("R") && parts.size >= 3 -> {
                        val old = parts[1]
                        val target = canonical(parts[2])
                        if (old != target) newAliases.add(old to target)
                        if (!excluded(target)) files.add(target)
                    }
                    parts.size >= 2 -> {
                        val path = canonical(parts[1])
                        if (!excluded(path)) files.add(path)
                    }
                }
            }
            for ((old, target) in newAliases) {
                renameAlias[old] = target
                if (canonical(old) == old) renameAlias.remove(old) // would form a cycle (e.g. A<->B swap)
            }
            commits.add(
                Commit(
                    hash = header[0],
                    author = header[1],
                    epochSec = header[2].toLongOrNull() ?: 0L,
                    message = header.drop(3).joinToString(FIELD_SEP.toString()),
                    files = files.distinct(),
                )
            )
        }
        return commits
    }

    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**/", i) -> { sb.append("(?:.*/)?"); i += 3 }
                glob.startsWith("**", i) -> { sb.append(".*"); i += 2 }
                glob[i] == '*' -> { sb.append("[^/]*"); i++ }
                glob[i] == '?' -> { sb.append("[^/]"); i++ }
                else -> { sb.append(Regex.escape(glob[i].toString())); i++ }
            }
        }
        return Regex(sb.toString())
    }
}
