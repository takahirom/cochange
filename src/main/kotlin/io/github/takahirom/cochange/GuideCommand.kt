package io.github.takahirom.cochange

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional

/**
 * `cochange guide [topic]` — playbooks for using the tool well.
 *
 * Holds the situational knowledge that doesn't fit in per-command help: which
 * commands to run in what order, how to read findings without jumping to
 * conclusions, and what to do when a repo's shape (monolith, git-flow, tiny
 * history) makes the defaults misleading. Written so an AI agent can operate
 * the tool end-to-end from this text alone.
 */
class GuideCommand : CliktCommand(
    name = "guide",
    help = """Playbooks, by goal: explore, align-boundaries, reduce-change-tax, split-god-class, extract-module.
        |Mechanics: reading, change-unit, small-repo. Run without a topic for the index.""".trimMargin(),
) {
    private val topic by argument(
        name = "topic",
        help = "One of: ${GUIDE_TOPICS.keys.joinToString(", ")}. Omit to list all topics.",
    ).optional()

    override fun run() {
        val requested = topic
        if (requested == null) {
            echo(GUIDE_INDEX)
            return
        }
        val playbook = GUIDE_TOPICS[requested]
            ?: throw CliktError(
                "Unknown guide topic \"$requested\". Valid topics: ${GUIDE_TOPICS.keys.joinToString(", ")}."
            )
        echo(playbook)
    }
}

/** Appended to workflow-ish topics so the reading discipline fires at the right moment. */
private const val READING_TRAILER = "\nBefore reporting conclusions: cochange guide reading — how to weigh a finding."

/** The one-line-per-topic index printed when `guide` is run without a topic. */
private val GUIDE_INDEX = """
    cochange guide <topic> — pick the playbook that matches your goal:
      explore            understand an unfamiliar codebase, end to end
      align-boundaries   act on boundary_mismatch findings (move code, or add a seam)
      reduce-change-tax  act on unstable_hub findings (files taxing every change)
      split-god-class    act on split_candidate findings (split lines included)
      extract-module     find module-extraction candidates, incl. monolith repos
    How to read the numbers:
      reading            weigh a finding before acting on it
      change-unit        merge vs author-window vs commit, git-flow repos
      small-repo         repositories with little history
""".trimIndent()

/** Topic name -> playbook text. Terse, imperative, plain text; keep each low-token. */
private val GUIDE_TOPICS: Map<String, String> = linkedMapOf(
    "explore" to """
        == guide: explore ==
        Understand an unfamiliar codebase's change structure.

        Recipe:
          1. cochange analyze <repo> --since "2 years ago"
             Read the banner first: which change unit was chosen and why. If it
             warns about a release-only branch or a shallow clone, fix that
             before trusting any numbers (see: cochange guide change-unit).
             Then read the "module detection" block. At trust=guessed,
             boundary_mismatch and unstable_hub are WITHHELD, not empty:
             the build system isn't auto-detected, so inspect the directory
             layout and re-run with --module-root '<dir-pattern>/*'.
          2. cochange findings <repo> --json
             Findings are ordered source-first, strongest-first. Triage by
             type + impact; ignore build/docs categories on a first pass.
          3. cochange inspect <finding-id> <repo>
             Read observation, counterSignals, and evidence — not just the
             summary. supportingCommits give each backing change's subject,
             date, and per-file churn: read two or three before claiming the
             files change for the same reason.
          4. cochange clusters <repo> --category source
             The de-facto change units. Use when findings feel fragmented —
             clusters show the whole group a pair belongs to.
          5. cochange pairs <repo> --file <substring>
             Drill into one file: every partner it co-changes with.
        Then pick the goal playbook that matches what you found:
          align-boundaries / reduce-change-tax / split-god-class / extract-module.
        Done when each reported finding names the files, the evidence (counts,
        sample commits), and a next step a human could take.
    """.trimIndent() + READING_TRAILER,

    "align-boundaries" to """
        == guide: align-boundaries ==
        Act on boundary_mismatch: two files in different modules keep changing
        as one unit.

        Recipe:
          1. cochange findings <repo> --type boundary_mismatch --json
          2. For each candidate, inspect it and check metrics: both directional
             probabilities strong -> genuine shared change reason. One-sided ->
             the partner may just be a widely shared file; deprioritize.
          3. `git show --stat` two or three supportingChanges: what actually
             changed together? An interface mirrored into an implementation?
             A flag definition plus its fake?
        Options to propose, in order of preference:
          - Move the pair into the module where the change reason lives.
          - Introduce an interface/abstraction that absorbs the shared reason,
            so one side stops changing.
          - If one side is test support (a Fake), generate or colocate it.
        Done when the proposal names which file moves where (or which seam is
        added) and cites the co-change count as the expected saving.
    """.trimIndent() + READING_TRAILER,

    "reduce-change-tax" to """
        == guide: reduce-change-tax ==
        Act on unstable_hub: a file inside a large share of everyone's changes.

        Recipe:
          1. cochange findings <repo> --type unstable_hub --json
          2. For each hub, decide additive vs structural churn:
             `git -C <repo> show <hash>` a few supportingChanges. Lines only
             ADDED to a list/registry each time (DI wiring, version catalogs,
             flag registries) = additive. Logic edited each time = structural.
          3. Additive churn: acceptable, or automate the registration
             (code generation, convention plugins) so the file stops appearing
             in every PR.
             Structural churn: split by responsibility — run
             cochange pairs <repo> --file <hub-basename> to see which change
             contexts pull on it, then see: cochange guide split-god-class.
        Done when each hub has a verdict (additive / structural) with a sample
        commit as evidence, and the structural ones have a split direction.
    """.trimIndent() + READING_TRAILER,

    "split-god-class" to """
        == guide: split-god-class ==
        Act on split_candidate: a file whose co-change partners form groups
        that never co-change with each other.

        Recipe:
          1. cochange findings <repo> --type split_candidate --json
             The groups in the observation ARE the proposed split lines.
          2. Counter-check eras: groups can be old vs new caller generations
             or platform variants, not separable responsibilities. Check both
             groups appear in recent supportingChanges (`git show -s --format=%ci <hash>`).
          3. Read the file itself: do the groups map to methods/regions?
             If yes, propose extracting one group's responsibility first —
             usually the smaller, newer group is the cheaper extraction.
        Done when the proposal says: split <file> into <A> (serving group 1)
        and <B> (serving group 2), with the co-change groups as evidence.
    """.trimIndent() + READING_TRAILER,

    "extract-module" to """
        == guide: extract-module ==
        Find module-extraction candidates — especially in repos where most
        code lives in one module (a monolith `app`).

        In a monolith, boundary_mismatch is quiet by construction (no
        boundaries to cross). That silence is itself a finding: the module
        structure isn't where the change structure is.
        Recipe:
          1. cochange clusters <repo> --category source
             Each cluster is a candidate module. A self-contained cluster
             (screen + logic + tests moving together) is a ready-made
             extraction proposal.
          2. cochange findings <repo> --type unstable_hub
             Hubs work without boundaries; extracting a cluster that a hub
             belongs to won't help until the hub itself is addressed.
          3. cochange findings <repo> --type split_candidate
             God classes must be split before their cluster can be extracted
             cleanly.
        Done when you can propose: what to extract first (a self-contained
        cluster), and what blocks extraction (hubs and split candidates inside
        it).
    """.trimIndent() + READING_TRAILER,

    "reading" to """
        == guide: reading ==
        How to weigh a finding before acting on it.

        A finding is a review candidate backed by counts, not a proven defect.

        Three tiers, and every output labels which one it is (field: tier):
          evidence       counted from commits. Wrong only if the history was
                         read wrong (shallow clone, wrong window/change unit).
          derived        structure inferred from the repo: modules, categories,
                         roles, clusters. Best-effort; carries provenance.
          interpretation findings, impact, effort. Heuristic.
        Never quote an interpretation without checking the derived layer it
        stands on: read moduleDetection.trust and .coverage. At trust=guessed,
        module findings are not produced at all and skippedDetectors says so —
        that is a withheld answer, not "nothing found".

        Check a finding, in order:
          1. evidence.support vs evidence.sampleSize, and read
             evidence.sampleMeaning — the denominator differs per finding type.
             confidence 1.0 from 5 of 5 is weaker than 0.7 from 80.
             evidence.evidenceStrength is the sample-corrected number; prefer
             it over the raw ratio when comparing two findings.
          2. evidence.nameSimilarity. High means the names already predicted
             the coupling (Foo / DefaultFoo) — low architectural surprise.
             evidence.interest is what ranked the list; it is published so you
             can re-rank yourself instead of trusting order.
          3. counterSignals. One-directional coupling means the partner is
             probably just a widely shared file.
          4. Both directions in metrics: P(A|B) vs P(B|A). Coupling that only
             holds one way is a different (weaker) claim.
          5. Category. build/config/docs co-change with everything by design;
             they are ranked separately for a reason.
          6. Staleness. A WARNING about a moved HEAD or shallow clone means
             re-run analyze before quoting numbers.
        confidence is each type's own ratio and is not comparable across
        types — see the field docs, or use evidence.* instead.
        Co-change measures "changed at the same times" only. Whether the files
        changed "for the same reasons" needs the supportingChanges commits —
        read two or three before claiming a shared reason.
    """.trimIndent(),

    "change-unit" to """
        == guide: change-unit ==
        What counts as "one change" decides every number downstream.

        auto (default) picks:
          merge         — mainline is mostly PR merges. One merge = one PR.
                          Exact change units; prefer this when available.
          author-window — squash/linear history. Same author within 30 min =
                          one unit. Approximate: multi-day PRs split, rapid
                          unrelated commits merge.
        Overrides: --change-unit merge|author-window|commit; --group-window N.
        Watch for in the banner:
          "release-only branch" — you're on a git-flow main; the real PR
          granularity lives on the development branch. Re-run with
          --branch develop (or equivalent).
          "shallow clone" — history is truncated; unshallow before trusting.
        Release branches merged back into a PR-based main appear as one
        combined unit each; they're usually few and small, but check with
        `git log --first-parent --merges` if numbers look off.
    """.trimIndent(),

    "small-repo" to """
        == guide: small-repo ==
        Few commits (young repo, or a short --since window).

        Defaults assume years of history. With hundreds of changes or fewer:
          1. Drop --since entirely (analyze the whole history).
          2. Lower the bars: --min-support 3 --min-confidence 0.5, and for
             clusters: --min-support 3.
          3. Expect few or no findings — that's a valid result, not a failure.
             Say "not enough history for strong claims" instead of forcing
             weak findings into conclusions.
        Every threshold you lowered must be reported alongside the findings;
        support-3 evidence is a hint, not a case.
    """.trimIndent(),
)
