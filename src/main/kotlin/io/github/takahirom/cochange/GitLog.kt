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

    /**
     * Resolves supporting-change hashes into readable commits: subject, date, and
     * per-file churn restricted to [files]. A bare hash asks the reader to go run
     * `git show` before they can judge whether two files changed for the same
     * reason — which is the one question co-change alone cannot answer.
     *
     * Resolved on demand rather than stored, so an old snapshot gains this too and
     * the cache doesn't carry a copy of the log.
     */
    fun commitSummaries(repo: File, hashes: List<String>, files: Set<String>): List<CommitSummary> {
        if (hashes.isEmpty()) return emptyList()
        val args = buildList {
            add("show")
            add("--no-patch")
            add("--numstat")
            // A merge commit needs a diff against its FIRST PARENT — the same diff the
            // merge change unit counted, and the same flag readCommits uses. Without any
            // of this `git show --numstat` prints nothing for a merge, so in a merge-based
            // repository every supporting change came back with no files at all.
            //
            // Not `-m --first-parent`: -m asks for a separate diff against every parent
            // and --first-parent only restricts history traversal, which git show is not
            // doing here — so an octopus merge would have contributed its side parents too.
            add("--diff-merges=first-parent")
            add("--date=short")
            add("--pretty=format:$COMMIT_SEP%H$FIELD_SEP%ad$FIELD_SEP%an$FIELD_SEP%s")
            addAll(hashes)
        }
        val output = runCatching { runGit(repo, args) }.getOrNull() ?: return emptyList()
        return output.split(COMMIT_SEP)
            .filter { it.isNotBlank() }
            .mapNotNull { block ->
                val lines = block.lines()
                val fields = lines.first().split(FIELD_SEP)
                if (fields.size < 4) return@mapNotNull null
                // Churn is restricted to the finding's files, and rename forms are
                // resolved to the post-rename path so a moved file still counts.
                val churn = lines.drop(1).mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) return@mapNotNull null
                    val path = renameTarget(parts[2])
                    if (path !in files) return@mapNotNull null
                    path to ((parts[0].toIntOrNull() ?: 0) + (parts[1].toIntOrNull() ?: 0))
                }.toMap()
                CommitSummary(fields[0], fields[1], fields[2], fields[3].trim(), churn)
            }
    }

    /**
     * The post-rename path from a numstat line. With `-M`, git writes a rename as
     * `old => new` or `pre/{old => new}/post`; comparing those raw strings against the
     * finding's current paths silently dropped every renamed file's churn.
     */
    internal fun renameTarget(path: String): String {
        if ("=>" !in path) return path
        val braced = Regex("""\{([^{}]*) => ([^{}]*)\}""").find(path)
        if (braced != null) {
            return (path.substring(0, braced.range.first) + braced.groupValues[2] +
                path.substring(braced.range.last + 1)).replace("//", "/").trim('/')
        }
        return path.substringAfter("=>").trim()
    }

    fun headFiles(repo: File, branch: String?): Set<String> =
        runGit(repo, listOf("ls-tree", "-r", branch ?: "HEAD"))
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("160000") } // skip submodule gitlinks
            .map { it.substringAfter('\t') }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * Files marked `linguist-generated` in `.gitattributes` (the same signal
     * GitHub uses to fold generated code out of diffs). Resolved with
     * `git check-attr` so attribute precedence and negations are honored exactly
     * instead of re-implemented. Returns empty if the repo declares nothing.
     */
    fun generatedFiles(repo: File, rev: String, headFiles: Set<String>): Set<String> {
        if (headFiles.isEmpty()) return emptySet()
        val input = headFiles.joinToString("\n")
        // --source reads .gitattributes from the analyzed revision rather than the
        // working tree, which may be a different branch or carry local edits. Fall
        // back to working-tree attributes for git < 2.40, which lacks --source.
        val output = runCatching {
            runGit(repo, listOf("check-attr", "--source=$rev", "linguist-generated", "--stdin"), stdin = input)
        }.recoverCatching {
            runGit(repo, listOf("check-attr", "linguist-generated", "--stdin"), stdin = input)
        }.getOrElse { return emptySet() }
        val marker = ": linguist-generated: "
        val result = HashSet<String>()
        for (line in output.lineSequence()) {
            val i = line.lastIndexOf(marker)
            if (i < 0) continue
            when (line.substring(i + marker.length).trim()) {
                "set", "true" -> result.add(line.substring(0, i))
            }
        }
        return result
    }

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

    /**
     * Turns a relative window like "1 year ago" into an absolute UTC instant,
     * using git's own date parser (`git rev-parse --since=X` prints
     * `--max-age=<epoch>`). Needed so a saved snapshot pins the window it
     * actually analyzed: replaying "1 year ago" a month later reads a different
     * history and silently produces a different answer.
     */
    fun resolveSince(repo: File, since: String): String? {
        val epoch = runCatching {
            runGit(repo, listOf("rev-parse", "--since=$since"))
                .trim().substringAfter("--max-age=", "").trim().toLongOrNull()
        }.getOrNull() ?: return null
        return java.time.Instant.ofEpochSecond(epoch).toString()
    }

    internal fun runGit(repo: File, args: List<String>, stdin: String? = null): String {
        // quotepath=off keeps non-ASCII paths literal instead of octal-escaped.
        val process = ProcessBuilder(listOf("git", "-c", "core.quotepath=off") + args)
            .directory(repo)
            .redirectErrorStream(false)
            .start()
        // Feed stdin (e.g. paths for check-attr --stdin) on its own thread so a
        // large payload can't deadlock against a filling stdout pipe.
        val stdinWriter = stdin?.let { input ->
            Thread { process.outputStream.bufferedWriter().use { it.write(input) } }.apply { start() }
        }
        // Drain stderr concurrently so a chatty child can't block on a full pipe.
        val err = StringBuilder()
        val errReader = Thread { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        errReader.start()
        val out = process.inputStream.bufferedReader().readText()
        stdinWriter?.join()
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
