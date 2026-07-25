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
    /**
     * The finding's headline ratio in 0..1. **Detector-specific**: for
     * `boundary_mismatch` it is P(other | rarer); for `unstable_hub` the share of
     * multi-file changes the file was dragged into; for `split_candidate` the
     * share of the file's own changes that involved one of its partner groups.
     * Comparable within a type, not across types — use [evidence] for the raw
     * numbers and `evidence.evidenceStrength` for a sample-corrected strength.
     */
    val confidence: Double,
    val impact: String,
    /**
     * Every file this finding is about, unordered: both sides of a pair, the hub, or a
     * split candidate together with all of its partners. Summaries abbreviate paths for
     * readability, so a consumer needs these to act on a finding without parsing prose.
     * When you need "which file is this finding ABOUT", use [subjects] — position in
     * this list carries no meaning.
     */
    val files: List<String> = emptyList(),
    /**
     * The file(s) the finding is making a claim about, as opposed to the context they
     * were found against: the two sides of a `boundary_mismatch`, the hub, the split
     * candidate. For a split candidate [files] additionally holds every partner, and
     * "the candidate is first" used to be an unwritten convention a consumer had to
     * guess at.
     */
    val subjects: List<String> = emptyList(),
    /** Always [EvidenceTier.INTERPRETATION]: a finding is a review candidate, not a measurement. */
    val tier: String = EvidenceTier.INTERPRETATION,
    /** The counted numbers underneath, with the denominator spelled out. */
    val evidence: FindingEvidence? = null,
    /** How this finding was ranked, and against what — interpretation, not evidence. */
    val ranking: FindingRanking? = null,
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

    /**
     * Build-definition file names, lowercased. ONE list, shared with module detection —
     * `Boundaries` kept its own, so `pyproject.toml` was a module root there and a
     * *config* file here, and a pyproject/source pair came out as "two languages meeting".
     */
    val buildNames = setOf(
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "pom.xml", "package.json", "package-lock.json", "cargo.toml", "go.mod", "go.sum",
        "gemfile", "makefile", "dockerfile",
        "pyproject.toml", "setup.py", "cmakelists.txt", "mix.exs", "build.bazel", "build",
        "package.swift",
    )

    /** The subset that marks a directory as a module root, in their on-disk spelling. */
    val moduleRootFileNames = setOf(
        "build.gradle", "build.gradle.kts", "package.json", "Cargo.toml", "go.mod", "pom.xml",
        "BUILD.bazel", "BUILD", "pyproject.toml", "setup.py", "CMakeLists.txt", "mix.exs",
        "Package.swift",
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

/**
 * The counted part of a finding, typed rather than stringly. Every field is a
 * number a consumer can compare or threshold on — which the old
 * `metrics: Map<String, String>` (whose keys embedded file paths) could not be.
 *
 * [ratio] alone is misleading on small samples: 5 out of 5 is 1.0 and means very
 * little, so [evidenceStrength] is the sample-corrected figure to compare on.
 * The ranking heuristic lives in [FindingRanking], not here — it used to sit in
 * this block under `tier: "evidence"`, which is exactly the confusion the tiers
 * exist to prevent.
 */
@Serializable
data class FindingEvidence(
    /** Change units that back the claim. */
    val support: Int,
    /** The denominator [support] is measured against. */
    val sampleSize: Int,
    /** What [sampleSize] counts, in words — the denominator differs per finding type. */
    val sampleMeaning: String,
    /** [support] / [sampleSize]. */
    val ratio: Double,
    /**
     * Sample-corrected strength (Wilson-bounded ratio × log support), over this
     * finding type's own denominator. Comparable between findings of the same type,
     * not across types.
     */
    val evidenceStrength: Double? = null,
    val tier: String = EvidenceTier.EVIDENCE,
)

/**
 * Why a finding sits where it does in the list. Interpretation, not evidence: it
 * weighs counted strength against how much the file names alone already predicted
 * the coupling, and that weighting is a judgement call. Both inputs are here so a
 * consumer can re-rank on [FindingEvidence.evidenceStrength] alone.
 *
 * Only `boundary_mismatch` has this — the other types have no pair of names to
 * compare, and the fields are absent rather than faked.
 */
@Serializable
data class FindingRanking(
    /** The score that ordered the list: evidenceStrength x (1 - 0.5 x nameSimilarity). */
    val interest: Double,
    /** 0..1 token overlap of the two basenames. High means the names predicted this. */
    val nameSimilarity: Double,
    val tier: String = EvidenceTier.INTERPRETATION,
)

@Serializable
data class FindingDetail(
    val observation: String,
    val interpretations: List<String>,
    val counterSignals: List<String>,
    /**
     * The change units backing this finding, each with ALL of its commits. Storing
     * only the first commit of each unit meant an author-window unit — commit 1
     * touches A.kt, commit 2 touches B.kt ten minutes later — looked like a change
     * to A.kt alone, which is exactly the wrong conclusion to invite.
     */
    val supportingChanges: List<SupportingChange>,
    val metrics: Map<String, String> = emptyMap(),
    /** For split_candidate: the independent partner clusters, in full, with their support and active period. */
    val groups: List<SplitGroup> = emptyList(),
    /**
     * Mixed by construction: `observation` restates counts, `interpretations` and
     * `counterSignals` are readings of them. Labelled interpretation because that is
     * the weakest thing in the block, and a block is only as solid as its softest field.
     */
    val tier: String = EvidenceTier.INTERPRETATION,
)

/**
 * One change unit that backs a finding. A unit is not always one commit: under
 * `author-window` it is every commit by the same author inside the window, and the
 * co-change evidence comes from the unit as a whole, so all of its commits belong
 * here.
 */
@Serializable
data class SupportingChange(
    val hashes: List<String>,
    /**
     * The finding's own files this unit touched, recorded at analysis time.
     *
     * It cannot be rebuilt later from `git show`: the log is read with `-M`, so a file
     * renamed *after* this commit is stored under its current path while the commit
     * itself still names the old one. Reconstructing from churn therefore dropped
     * exactly the files a rename had moved.
     */
    val filesTouched: List<String> = emptyList(),
    val tier: String = EvidenceTier.EVIDENCE,
)

/**
 * One independent partner cluster of a split_candidate: the full file list (no
 * truncation), how strongly it co-changes with the candidate, and when that
 * coupling was seen — so a reader can tell "two responsibilities" apart from
 * "old vs new era of one responsibility".
 */
@Serializable
data class SplitGroup(
    /** The group's members that are shown. Excluded roles are omitted; see [hiddenMembers]. */
    val files: List<String>,
    /**
     * Members omitted by `--exclude-role`. The group's [support] and [linkWeight] still
     * count them, because a role filter must not move a number — this says how much of
     * the group you are not being shown.
     */
    val hiddenMembers: Int = 0,
    /** Change units in which the candidate changed together with at least one file in this group. */
    val support: Int,
    /**
     * Sum of the pairwise co-change counts between the candidate and each member.
     * Larger than [support] whenever one change unit touched several members, so
     * it ranks groups but is not a count of anything.
     */
    val linkWeight: Int,
    /** Date of the first change unit counted in [support]. */
    val firstSeen: String,
    /**
     * Date of the last one. [firstSeen]..[lastSeen] are bounds, not an interval of
     * continuous activity — the group may have been idle for most of it.
     */
    val lastSeen: String,    val tier: String = EvidenceTier.DERIVED,
)

@Serializable
data class AnalysisResult(
    /**
     * Contract version of this JSON. Bumped when a field's *meaning* changes, so a
     * consumer that pinned an older shape can refuse rather than silently
     * misread — additive fields don't bump it. Defaults to 1 so a snapshot written
     * before versioning existed is identified as old rather than as current.
     */
    val schemaVersion: Int = 1,
    val repo: String,
    val branch: String,
    val headCommit: String = "",
    val shallow: Boolean = false,
    val changeUnit: String = "author-window",
    val analyzedCommits: Int,
    val logicalChanges: Int,
    val findings: List<Finding>,
    /** What the user asked for, verbatim — including relative values like `since: "1 year ago"`. */
    val requestedOptions: AnalysisOptions? = null,
    /**
     * The same conditions with everything relative pinned to what it resolved to
     * (commit, absolute instant, chosen change unit). This is what `--analysis`
     * replays, so a re-run reads the same history rather than today's equivalent.
     */
    val options: AnalysisOptions? = null,
    /** Provenance, coverage, and gate decision for module detection — the structure findings are built on. */
    val moduleDetection: ModuleDetectionReport? = null,
    /** Detector types withheld because their prerequisite structure wasn't reliable. */
    val skippedDetectors: List<SkippedDetector> = emptyList(),
    /**
     * Files hidden from these findings by `--exclude-role`, per role. They were
     * still counted: the co-change numbers reflect the full history, so this is a
     * view filter, not a smaller dataset.
     */
    val hiddenByRole: Map<String, Int> = emptyMap(),
    /**
     * Conditions that make these numbers less trustworthy — an unparseable window, a
     * shallow clone, guessed module boundaries. Present here and not only in the
     * banner, because `--json` prints no banner and an empty `findings` list from a
     * broken window is indistinguishable from a clean repository otherwise.
     */
    val warnings: List<AnalysisWarning> = emptyList(),
    /**
     * Every detector type this version can produce, serialized so a consumer never has to
     * hard-code the roster to tell "this type found nothing" from "this type did not run".
     * Compare against [skippedDetectors].
     */
    val detectorTypes: List<String> = DETECTOR_TYPES,
)

/** The complete detector roster, published in every result. */
val DETECTOR_TYPES = listOf("boundary_mismatch", "unstable_hub", "split_candidate")

/**
 * Version 2 renamed nothing silently: `confidence` is now documented per detector
 * and no longer uses arbitrary count/50 scales, `SplitGroup.support` counts change
 * units instead of summed pair weights (that value moved to `linkWeight`), and
 * `activeFrom`/`activeTo` became `firstSeen`/`lastSeen` because they are bounds,
 * not a continuous interval.
 */
const val SCHEMA_VERSION = 2
