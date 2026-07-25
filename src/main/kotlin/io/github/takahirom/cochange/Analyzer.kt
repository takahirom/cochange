package io.github.takahirom.cochange

import kotlin.math.ln

fun defaultDetectors(minSupport: Int = 5, minConfidence: Double = 0.6): List<FindingDetector> = listOf(
    BoundaryMismatchDetector(minSupport = minSupport, minConfidence = minConfidence),
    UnstableHubDetector(),
    SplitCandidateDetector(minSupport = minSupport),
)

/**
 * One analysis pass: the findings that ran, plus the detectors that were
 * deliberately withheld and why. Reporting the skips is part of the result —
 * an absent finding type otherwise reads as "nothing to see here".
 */
class AnalysisRun(
    val findings: List<Finding>,
    val skipped: List<SkippedDetector>,
    val moduleDetection: ModuleDetectionReport,
    /** Analyzed files hidden from the findings by `--exclude-role`, per role. */
    val hiddenByRole: Map<String, Int>,
)

class Analyzer(
    private val detectors: List<FindingDetector>,
) {
    constructor(minSupport: Int = 5, minConfidence: Double = 0.6) : this(defaultDetectors(minSupport, minConfidence))

    fun run(context: AnalysisContext): AnalysisRun {
        val modules = ModuleGate.report(context.moduleDetection)
        // The gate withholds, it does not merely warn: a finding whose whole
        // claim is "these two files live in different modules" is worthless when
        // "module" means "top-level folder name". Raw pairs, clusters and
        // metrics are untouched — only this interpretation layer is gated.
        val (allowed, blocked) = detectors.partition { modules.moduleFindingsEnabled || !it.requiresModuleBoundaries }
        val findings = allowed.flatMap { it.detect(context) }
            .sortedBy { FileCategory.priority.indexOf(it.category) }
            .mapIndexed { i, f -> f.copy(id = "finding-${i + 1}") }
        return AnalysisRun(
            findings = findings,
            skipped = blocked.map { SkippedDetector(it.type, modules.note) },
            moduleDetection = modules,
            hiddenByRole = context.hiddenByRole,
        )
    }

    fun analyze(context: AnalysisContext): List<Finding> = run(context).findings

    fun analyze(
        changes: List<LogicalChange>,
        boundaries: Boundaries,
        headFiles: Set<String>,
    ): List<Finding> = analyze(AnalysisContext(changes, boundaries, headFiles))
}

internal fun pct(v: Double) = "${(v * 100).toInt()}%"
internal fun round2(v: Double) = kotlin.math.round(v * 100) / 100

/**
 * Rough cost-to-fix of a co-change coupling, so findings can be read for ROI
 * (impact vs effort), not just for impact. The kind is derived from the two
 * files' categories, languages, and names — cheap signals, no extra git reads.
 */
object CouplingKind {
    data class Estimate(val kind: String, val effort: String, val note: String)

    /**
     * A comparison key for a file's language. Known extensions map to a language
     * family; unknown ones fall back to the raw extension so an unrecognized
     * language is never silently treated as "same" as a different one.
     */
    private fun langKey(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts", "java" -> "jvm"
            "swift" -> "swift"
            // C, C++, Objective-C and their shared headers are one native family: a
            // foo.c / foo.h or foo.mm / foo.h pair is a companion, not a platform boundary.
            "c", "h", "hh" -> "native"
            "ts", "tsx", "js", "jsx" -> "js"
            "py", "pyi" -> "py"
            "go" -> "go"
            "rs" -> "rust"
            "dart" -> "dart"
            "rb" -> "ruby"
            "cpp", "cc", "cxx", "hpp", "hxx", "m", "mm" -> "native"
            else -> ext.ifEmpty { "?" }
        }
    }

    /**
     * Same file name, and the two parent directories are siblings — `crates/a/Cargo.toml`
     * and `crates/b/Cargo.toml`, `values/strings.xml` and `values-ja/strings.xml`. Purely
     * structural, no per-ecosystem name list.
     */
    fun siblingVariants(a: String, b: String): Boolean {
        if (a.substringAfterLast('/') != b.substringAfterLast('/')) return false
        val parentA = a.substringBeforeLast('/', "")
        val parentB = b.substringBeforeLast('/', "")
        if (parentA == parentB) return false
        return parentA.substringBeforeLast('/', "") == parentB.substringBeforeLast('/', "")
    }

    /**
     * What it would cost to act on this coupling, and an honest name for what it is.
     *
     * Decided from the two endpoints' own categories, then — only when both are source —
     * from their languages. Reading extensions first was the source of a long line of
     * false descriptions: `config/app.yaml` × `src/App.kt` is not "a platform boundary
     * expensive to break", and `pyproject.toml` × `app.py` is not two languages meeting.
     * A language difference only means something when both sides are code.
     *
     * [category] is the PAIR's category, which `categoryOfPair` takes from the least
     * source-like side, so it cannot stand in for either endpoint.
     */
    fun of(a: String, b: String, category: String, namesRelated: Boolean): Estimate {
        val ca = FileCategory.of(a)
        val cb = FileCategory.of(b)
        fun bothAre(c: String) = ca == c && cb == c
        fun eitherIs(c: String) = ca == c || cb == c
        // "Code" is narrower than FileCategory.SOURCE, which is its default bucket: a
        // localized strings.xml lands in SOURCE but is a resource, and calling a pair of
        // translations "parallel implementations, check for duplicated logic" was exactly
        // the false positive the variant-set rule exists to prevent. FileRole knows.
        fun isCode(path: String, category: String) =
            category == FileCategory.SOURCE && FileRole.of(path) == FileRole.SOURCE
        val aIsCode = isCode(a, ca)
        val bIsCode = isCode(b, cb)
        val bothSource = aIsCode && bIsCode
        return when {
            category == FileCategory.GENERATED -> Estimate(
                "generated", "none",
                "one side is generated — the coupling is inherent; fixing the source regenerates it, so this is not a refactoring target.",
            )
            // A variant set is DECLARATIVE files sharing one name under sibling
            // directories: per-crate Cargo.toml bumped by one release, per-locale
            // strings.xml translated together. Neither side may be source.
            siblingVariants(a, b) && !aIsCode && !bIsCode -> Estimate(
                "variant-set", "none",
                "the same file name under sibling directories, declarative on both sides — a variant or lockstep set (coordinated version bumps, translations, per-target manifests). The coupling is the process, not an architectural boundary problem.",
            )
            // Same name, sibling directories, source on BOTH sides: one shape implemented
            // once per feature or service, so possibly duplicated logic.
            siblingVariants(a, b) && bothSource -> Estimate(
                "parallel-implementation", "medium",
                "the same file name under sibling directories, source on both sides — one shape implemented once per feature or service. Check for duplicated logic that belongs in a shared place, rather than assuming the coupling is expected.",
            )
            eitherIs(FileCategory.DOCS) -> Estimate(
                "documentation", "low",
                "one side is documentation — a changelog or a README moving with the code it describes is documentation maintenance, not a design coupling. Worth knowing which docs a module drags along; not a refactoring target.",
            )
            bothAre(FileCategory.BUILD) -> Estimate(
                "build-wiring", "low",
                "both files are build definitions — adding a dependency or bumping a version routinely touches several of them at once. Still worth reading as a boundary signal, but cheap to act on and partly inherent to the build system.",
            )
            eitherIs(FileCategory.BUILD) && (aIsCode || bIsCode) -> Estimate(
                "manifest-and-source", "low",
                "one side is a build definition and the other the code it declares — a dependency or entry point registered alongside its implementation. Cheap to act on; not a cross-platform design coupling.",
            )
            // Neither side is code: two declarations kept in step (a config and the CI
            // file beside it, a manifest and a deployment descriptor).
            !aIsCode && !bIsCode -> Estimate(
                "declarative-pair", "low",
                "neither side is code — two declarations kept in step, such as a config file and the deployment or CI descriptor beside it. Cheap to act on, and not a design coupling across languages even when the file types differ.",
            )
            // One declaration, one implementation.
            !bothSource -> Estimate(
                "declaration-and-code", "low",
                "one side is configuration or a resource rather than code — a setting or asset declared next to whatever consumes it. Cheap to act on, and not a design coupling across languages even when the file types differ.",
            )
            // From here both sides ARE code, so a language difference is a real platform
            // boundary. Before companion: two platform-parallel files often share a name
            // (Screen.kt / Screen.swift), which is the expensive coupling, not a cheap
            // companion. Unknown extensions compare by their raw extension, so an
            // unrecognized pair (Foo.kt / Foo.php) is not understated.
            langKey(a) != langKey(b) -> Estimate(
                "cross-language", "high",
                "both sides are code, in different languages (${langKey(a)} vs ${langKey(b)}) — a design coupling across a platform boundary is expensive to break; weigh it against the impact before committing.",
            )
            namesRelated -> Estimate(
                "companion", "low",
                "the names look like an interface/implementation or companion pair — mechanical to move together, but also low architectural surprise.",
            )
            else -> Estimate("same-language", "medium", "same-language coupling within the codebase — a normal refactoring target.")
        }
    }
}

/**
 * Finds file pairs in different modules that keep changing together: the
 * module boundary and the actual change boundary disagree.
 */
class BoundaryMismatchDetector(
    private val minSupport: Int = 5,
    private val minConfidence: Double = 0.6,
    private val maxFindings: Int = 30,
    private val maxOtherCategoryFindings: Int = 5,
) : FindingDetector {
    override val type = "boundary_mismatch"
    override val requiresModuleBoundaries = true
    override val description =
        "File pairs in different modules that keep changing together — the module boundary and the actual change boundary disagree."

    override fun detect(context: AnalysisContext): List<Finding> {
        val boundaries = context.boundaries
        val candidates = context.pairs(minTogether = minSupport)
            .filter { context.isVisible(it.a) && context.isVisible(it.b) }
            // Both endpoints must sit in a module the repository declares. Without
            // this the claim degrades into "these two files are in different
            // top-level folders", which is not a boundary at all — and a repo-wide
            // coverage threshold cannot tell the difference, because coverage is a
            // file population and this claim is about two specific files.
            .filter { context.moduleIsDeclared(it.a) && context.moduleIsDeclared(it.b) }
            .filter { boundaries.moduleOf(it.a) != boundaries.moduleOf(it.b) }
            .filter { it.confidence >= minConfidence }
            // Rank by architectural interest, not raw strength: between two pairs
            // with comparable evidence, the one whose names did not already predict
            // the coupling comes first. Both inputs are published per finding, so a
            // consumer that disagrees with this weighting can re-sort.
            .sortedByDescending { Surprise.interest(it) }
            .toList()
            // Rank per category so build files, docs, and generated code, which
            // always co-change, can't crowd production-code findings out of the list.
            .groupBy { context.categoryOfPair(it.a, it.b) }
            .flatMap { (category, list) ->
                list.take(if (category == FileCategory.SOURCE) maxFindings else maxOtherCategoryFindings)
            }

        return candidates.map { pair -> toFinding(pair, context) }
    }

    companion object {
        /** True when names predict the coupling (Foo/DefaultFoo, Foo/FooTest): a threshold over the continuous similarity. */
        fun namesRelated(a: String, b: String): Boolean = Surprise.nameSimilarity(a, b) >= 0.5
    }

    private fun toFinding(p: AnalysisContext.PairStat, context: AnalysisContext): Finding {
        val boundaries = context.boundaries
        val confidence = p.confidence
        val rarer = if (p.countA <= p.countB) p.a else p.b
        val other = if (p.countA <= p.countB) p.b else p.a
        val rarerCount = minOf(p.countA, p.countB)
        val otherCount = maxOf(p.countA, p.countB)
        // Same basename on both sides (e.g. two README.md) needs a short disambiguating suffix.
        val (labelA, labelB) = distinguishingLabels(p.a, p.b)
        fun name(path: String) = if (path == p.a) labelA else labelB

        val category = context.categoryOfPair(p.a, p.b)
        val coupling = CouplingKind.of(p.a, p.b, category, namesRelated(p.a, p.b))
        val reverse = p.reverse
        val counterSignals = buildList {
            if (reverse < 0.3) add(
                "${name(other)} changed $otherCount times overall but only ${p.together} of those touched ${name(rarer)} " +
                    "(${pct(reverse)}) — the coupling is one-directional, so ${name(other)} may simply be a widely shared file."
            )
            if (rarerCount < 10) add("Only $rarerCount changes to ${name(rarer)} in the analyzed period — small sample.")
            if (CouplingKind.siblingVariants(p.a, p.b) &&
                FileCategory.of(p.a) != FileCategory.SOURCE && FileCategory.of(p.b) != FileCategory.SOURCE
            ) add(
                "Same file name under sibling directories (${p.a.substringAfterLast('/')}) — a variant or lockstep " +
                    "set. Coordinated version bumps, translations and per-target manifests move together by process, " +
                    "so crossing a module boundary here is expected rather than a design problem."
            ) else if (namesRelated(p.a, p.b)) add(
                "The file names look like an interface/implementation or companion pair — this coupling is expected " +
                    "and carries less architectural surprise than coupling between unrelated names."
            )
        }
        return Finding(
            id = "",
            type = type,
            category = category,
            summary = "${name(p.a)} (${boundaries.moduleOf(p.a)}) and ${name(p.b)} (${boundaries.moduleOf(p.b)}) " +
                "evolve as one change unit across a module boundary",
            confidence = round2(confidence),
            impact = if (confidence >= 0.8 && reverse >= 0.3 && p.together >= 10 && !namesRelated(p.a, p.b)) "high" else "medium",
            files = listOf(p.a, p.b),
            subjects = listOf(p.a, p.b),
            evidence = FindingEvidence(
                support = p.together,
                sampleSize = rarerCount,
                sampleMeaning = "change units touching ${name(rarer)}, the rarer of the two files",
                ratio = round2(confidence),
                evidenceStrength = round2(Surprise.evidenceStrength(p.together, p.countA, p.countB)),
            ),
            ranking = FindingRanking(
                interest = round2(Surprise.interest(p)),
                nameSimilarity = round2(Surprise.nameSimilarity(p.a, p.b)),
            ),
            effort = coupling.effort,
            detail = FindingDetail(
                observation = "${p.together} of $rarerCount changes to ${name(rarer)} also changed ${name(other)} " +
                    "(${pct(confidence)}), despite living in different modules " +
                    "(${boundaries.moduleOf(p.a)} vs ${boundaries.moduleOf(p.b)}).",
                interpretations = listOf(
                    "The module boundary may not match the actual change boundary.",
                    "An abstraction in one module may be leaking implementation details into the other.",
                    "Consider moving the pair into one module, or introducing an interface that absorbs the shared reason to change.",
                    "Effort (${coupling.kind}): ${coupling.note}",
                ),
                counterSignals = counterSignals,
                supportingChanges = context.sampleChanges(setOf(p.a, p.b)),
                metrics = mapOf(
                    "together" to p.together.toString(),
                    "changes(${p.a})" to p.countA.toString(),
                    "changes(${p.b})" to p.countB.toString(),
                    "P(${name(other)}|${name(rarer)})" to round2(confidence).toString(),
                    "P(${name(rarer)}|${name(other)})" to round2(reverse).toString(),
                    "couplingKind" to coupling.kind,
                    "effort" to coupling.effort,
                ),
            ),
        )
    }
}

/**
 * Finds files dragged into a large share of multi-file changes across many
 * modules: candidates for god modules, central wiring, or catch-all utilities.
 */
class UnstableHubDetector(
    private val minParticipation: Int = 20,
    private val minModuleSpread: Int = 5,
    private val maxFindings: Int = 10,
    private val maxOtherCategoryFindings: Int = 5,
) : FindingDetector {
    override val type = "unstable_hub"
    // Its threshold is "spans N *other modules*" — meaningless when modules are folder names.
    override val requiresModuleBoundaries = true
    override val description =
        "Files dragged into a large share of multi-file changes across many modules — candidates for god modules, central wiring, or catch-all utilities."

    override fun detect(context: AnalysisContext): List<Finding> {
        // Only multi-file changes: a hub is a file dragged into *other* work.
        // The predicate lives on AnalysisContext so `metrics` reads exactly the same
        // population — the two used to diverge while claiming to agree.
        val stats = context.hubStats
        val multiFileChanges = stats.multiFileChanges
        if (multiFileChanges.isEmpty()) return emptyList()
        val participation = stats.participation
        val partnerModules = stats.partnerModules

        return context.hubFiles(minParticipation, minModuleSpread).asSequence()
            // The predicate is unfiltered so rates stay stable; a hidden role is dropped
            // here, where we are choosing what to SHOW.
            .filter { context.isVisible(it) }
            .map { it to participation.getValue(it) }
            .sortedByDescending { (file, count) -> count.toLong() * partnerModules.getValue(file).size }
            .toList()
            // Cap per category (like boundary_mismatch) so generated/build/docs hubs
            // can't consume every slot and push source hubs out of the top findings.
            .groupBy { (file, _) -> context.categoryOf(file) }
            .flatMap { (category, list) ->
                list.take(if (category == FileCategory.SOURCE) maxFindings else maxOtherCategoryFindings)
            }
            .map { (file, count) ->
                val rate = count.toDouble() / multiFileChanges.size
                val modules = partnerModules.getValue(file).size
                Finding(
                    id = "",
                    type = type,
                    category = context.categoryOf(file),
                    summary = "${file.substringAfterLast('/')} participated in ${pct(rate)} of multi-file changes, " +
                        "spanning $modules other modules",
                    // The share of multi-file work this file was dragged into. Previously
                    // count/50, an arbitrary scale that reported 1.0 for any file over 50
                    // changes no matter how large the repository was.
                    confidence = round2(rate),
                    impact = if (rate >= 0.05) "high" else "medium",
                    files = listOf(file),
                    subjects = listOf(file),
                    evidence = FindingEvidence(
                        support = count,
                        sampleSize = multiFileChanges.size,
                        sampleMeaning = "change units touching more than one file",
                        ratio = round2(rate),
                        // Same sample correction as a pair's, over this type's own
                        // denominator, so the guide's "prefer evidenceStrength over the
                        // raw ratio" is followable here too. Comparable between hubs,
                        // not across finding types.
                        evidenceStrength = round2(
                            ln(1.0 + count) * Surprise.wilsonLower(count, multiFileChanges.size)
                        ),
                    ),
                    detail = FindingDetail(
                        observation = "$file was part of $count of ${multiFileChanges.size} multi-file changes " +
                            "(${pct(rate)}), together with files from $modules other modules.",
                        interpretations = listOf(
                            "This file may aggregate unrelated responsibilities (e.g. a god module, central DI wiring, or a catch-all utility).",
                            "Every feature change paying a toll here increases merge conflicts and review load.",
                            "Consider splitting it along the change clusters that pass through it.",
                        ),
                        counterSignals = listOf(
                            "Registration points (DI modules, navigation graphs, string resources) legitimately change with many features; the question is whether the churn is additive-only or structural.",
                        ),
                        supportingChanges = multiFileChanges.asSequence()
                            .filter { file in it.files }.take(10)
                            .map { SupportingChange(it.hashes, listOf(file)) }.toList(),
                        metrics = mapOf(
                            "participation" to count.toString(),
                            "multiFileChanges" to multiFileChanges.size.toString(),
                            "partnerModules" to modules.toString(),
                        ),
                    ),
                )
            }
            .toList()
    }
}
