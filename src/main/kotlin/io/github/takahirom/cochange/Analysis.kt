package io.github.takahirom.cochange

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Everything the pipeline needs to know about how to read a repository's
 * history. Raw analysis facts only — finding thresholds stay with the
 * detectors and commands. Serializable so a saved snapshot can record the exact
 * conditions it was produced under, and later commands can reuse them.
 */
@Serializable
data class AnalysisOptions(
    val branch: String? = null,
    val since: String? = null,
    val changeUnit: String = "auto",
    val maxFilesPerCommit: Int = 50,
    val groupWindowMin: Long = 30,
    val extraExcludes: List<String> = emptyList(),
    val moduleRoots: List<String> = emptyList(),
    /** File roles to hide from the output (test/resource/lockfile/generated); see [FileRole]. Never removed from the counts. */
    val excludeRoles: List<String> = emptyList(),
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
    /** When the window was resolved — the reference point for [Analysis.windowLooksUnparsed]. */
    val resolvedAt: java.time.Instant = java.time.Instant.now(),
) {
    val changes: List<LogicalChange> get() = context.changes

    /**
     * The options with every relative or auto-resolved value replaced by what it
     * actually resolved to: the commit instead of the branch tip, an absolute
     * instant instead of "1 year ago", the chosen change unit instead of "auto".
     * Replaying these re-reads the same history; replaying the raw request does
     * not, because the tip moves and "1 year ago" means something else tomorrow.
     */
    val resolvedOptions: AnalysisOptions by lazy {
        options.copy(
            branch = headCommit.ifEmpty { options.branch },
            since = options.since?.let { GitLog.resolveSince(repo, it) ?: it },
            changeUnit = changeUnitName,
        )
    }

    /**
     * Everything that makes the numbers below less trustworthy, as data rather than
     * as a banner line. These used to be printed only, and `--json` suppresses the
     * banner — so `analyze --since "las year" --json` looked like a valid analysis
     * that simply found nothing, which is the worst possible thing to hand an agent.
     */
    val warnings: List<AnalysisWarning> by lazy {
        buildList {
            val since = options.since
            if (since == null) {
                add(AnalysisWarning(
                    AnalysisWarning.NO_WINDOW, "note",
                    "No --since: computing over all history. Pass --since (e.g. '1 year ago') to match the window used elsewhere.",
                ))
            } else {
                val pinned = resolvedOptions.since
                if (pinned != null && Analysis.windowLooksUnparsed(pinned, resolvedAt)) {
                    add(AnalysisWarning(
                        AnalysisWarning.WINDOW_IS_NOW, "warning",
                        "--since '$since' resolved to the current instant, so almost no history was analyzed. " +
                            "git accepts any string here and falls back to 'now' when it cannot read it — use a form " +
                            "it understands, e.g. '1 year ago' or '2025-01-01'.",
                    ))
                }
            }
            // author-window merges consecutive same-author commits, and a repository worked
            // on in rapid bursts can collapse a lot of history into very few units. The
            // ratio is the thing to report: it is what decides whether a "pattern" is
            // several independent decisions or one afternoon.
            val commits = changes.sumOf { it.commits.size }
            val perUnit = if (changes.isEmpty()) 0.0 else commits.toDouble() / changes.size
            if (changeUnitName == "author-window" && changes.isNotEmpty() && perUnit >= 3.0) {
                add(AnalysisWarning(
                    AnalysisWarning.COARSE_GROUPING, "note",
                    "$commits commits grouped into ${changes.size} change units " +
                        "(${"%.1f".format(perUnit)} commits per unit). If unrelated work is being merged into " +
                        "one unit, compare against --change-unit commit or a smaller --group-window.",
                ))
            }
            if (shallow) add(AnalysisWarning(
                AnalysisWarning.SHALLOW_CLONE, "warning",
                "Shallow clone: history is truncated, so every ratio is biased. Run 'git fetch --unshallow'.",
            ))
            val modules = ModuleGate.report(context.moduleDetection)
            if (modules.trust != ModuleGate.DECLARED) add(AnalysisWarning(
                AnalysisWarning.MODULE_DETECTION, "note",
                "Module detection is ${modules.trust} (${pct(modules.coverage)} of files under a declared module root) — ${modules.note}",
            ))
        }
    }
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

    /**
     * True when a resolved `--since` landed on the instant it was resolved at — the
     * window selects (almost) nothing.
     *
     * git does not reject an unparseable date: it falls back to the current time, so
     * `--since "las year"` quietly analyzes an empty history. Comparing the pinned
     * instant against the moment of resolution is the only way to notice. [resolvedAt]
     * must be that moment, not "now" at call time, or a slow setup makes a genuinely
     * malformed date look fine. This deliberately also fires for a literal
     * `--since now`, which is likewise a window over nothing — the reported message
     * says "resolved to now", not "git could not read it", because both are true of
     * the outcome and only one is true of the cause.
     */
    fun windowLooksUnparsed(resolvedSince: String, resolvedAt: java.time.Instant): Boolean {
        val instant = runCatching { java.time.Instant.parse(resolvedSince) }.getOrNull() ?: return false
        return !instant.isBefore(resolvedAt.minusSeconds(2))
    }

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
            context = AnalysisContext(
                changes,
                Boundaries(headFiles, options.moduleRoots),
                headFiles,
                generated,
                // Roles hide files from the *views* — findings, listed pairs, clusters.
                // The co-change counts underneath still include them, so a later run
                // with a different --exclude-role reads the same history, and no
                // number ever silently rests on evidence that was thrown away.
                excludedRoles = options.excludeRoles.toSet(),
            ),
            changeUnitName = strategy.name,
            changeUnitReason = resolved.reason,
            shallow = GitLog.isShallow(repo),
            headCommit = GitLog.headCommit(repo, options.branch),
        )
    }
}
