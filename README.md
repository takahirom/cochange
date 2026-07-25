# cochange

**Code that changes together should live together.**

cochange reads your Git history to find code that **changes together but lives apart** — the coupling your module structure hides — and turns it into evidence-backed **review candidates** for humans and AI. It looks at what actually changes together, not at static dependencies.

## What you get

- **A shortlist of where to look first, with the cost of looking.** Which files drag the most changes across module boundaries, the commits that show it, and an effort estimate (an entrenched Kotlin↔Swift coupling costs more to fix than a generated-file one) so you can sort by impact vs cost (`analyze`, `findings`).
- **Candidate module boundaries the history suggests.** Files that keep moving together — a starting point for "should this be one module?" (`clusters`).
- **A way to check whether a migration actually finished.** A parallel old/new implementation shows up as one cluster — every fix still paid twice; when the cluster disappears, the duplication is gone.
- **A structure score you can trend.** The whole history reduced to a few higher-is-better numbers, so you can tell if a refactor helped or just moved things around (`metrics`).
- **A map for AI agents.** `--json` + `guide` hand a coding agent the change structure of an unfamiliar repo before it reads a line.

### How much to trust each line

cochange mixes three kinds of statement and labels them, because reading them as equally solid is the fastest way to act on a false positive:

| Tier | What it is | How it can be wrong |
| --- | --- | --- |
| **evidence** | Counted from commits: which files appeared in the same change, how often (`pairs`, the numbers under every finding) | Only if the history was read wrong (shallow clone, wrong `--since`, wrong change unit) |
| **derived** | Structure inferred from the repo: modules, categories, roles, clusters, change units | Best-effort per repository. Each carries its detection method and coverage |
| **interpretation** | What a coupling might mean and what it might cost: findings, impact, effort | Heuristic. These are review candidates, not verified defects |

Every `--json` output carries the same facts: `schemaVersion`, the pinned conditions it ran under, module-detection provenance, a `warnings` list, and a `tier` on each block. `pairs`, `clusters`, `metrics` and `compare` group them under a `context` object; `analyze`, `findings` and `inspect` carry them at the top level, next to the findings they describe — so a consumer never has to guess which layer a number came from. The split is structural, not just a label: a pair's counted numbers sit in `evidence` and the structure cochange inferred about it (module names, whether each was *declared* or guessed, category, name similarity) sits in `derived`. A finding's `evidence` names its own denominator (`sampleMeaning`) and its sample-corrected `evidenceStrength`, while the heuristic that ordered the list lives in a separate `ranking` block marked `interpretation`. A bare `1.0` from 5-of-5 and a `0.8` from 20-of-25 are not what they look like, and a ranking score is not evidence.

When the derived layer can't support an interpretation, cochange **withholds** the finding rather than footnoting it — and it decides per finding, not per repository. A `boundary_mismatch` is only reported when *both* of its files sit in a module the repository declares (a build file, a SwiftPM target, a `--module-root` glob); a pair whose "modules" are two top-level folder names is dropped even if the rest of the repo is well declared. With fewer than two declared modules there is no boundary to cross at all, so both module-dependent detectors are withheld outright and `skippedDetectors` says so. Raw evidence (`pairs`, `clusters`, `metrics`) is never withheld.

## Install

```bash
# Homebrew
brew install takahirom/repo/cochange

# or from source
./gradlew installDist   # binary at build/install/cochange/bin/cochange
```

```bash
cochange analyze /path/to/repo --since "2 years ago"   # findings with evidence
cochange findings /path/to/repo --type unstable_hub    # filter last analysis (--json for AI)
cochange inspect finding-3 /path/to/repo               # full evidence for one finding
cochange pairs /path/to/repo --category source         # raw co-change pairs, strongest first
cochange clusters /path/to/repo --category source      # co-change neighbourhoods (grouped pairs)
cochange detectors                                     # what this tool can find
cochange metrics /path/to/repo --json                  # repo-level scores (higher = better), for trending over time
cochange pairs /path/to/repo --json                    # raw evidence as JSON (also clusters --json)
cochange compare /path/to/repo --baseline 180d --recent 30d  # what's heating up vs cooling down
cochange guide                                         # playbooks: which commands, in what order, and how to read the results
```

The only required input is a Git repository — zero config, language-agnostic, fully local.

**Named snapshots.** Without `--save`, each `analyze` overwrites the default snapshot only — named ones are untouched — and its `finding-N` numbers shift. Name a run with `--save` to keep several side by side and read a specific one with `--analysis`:

```bash
cochange analyze . --since "2 years ago" --save long-term
cochange analyze . --since "1 month ago" --save recent
cochange findings . --analysis recent
cochange inspect finding-1 . --analysis long-term
```

Re-computing commands can replay a snapshot's conditions too — `cochange metrics . --analysis long-term` (also `pairs`, `clusters`) analyzes the same window, excludes, and change-unit as that snapshot instead of silently defaulting to all-history.

A snapshot stores its conditions **pinned**: the commit it ran on, `--since` as an absolute instant, and the change unit `auto` actually picked. So replaying it reads the same history rather than today's equivalent of "1 year ago". The JSON keeps both — `requestedOptions` (what you typed) and `options` (what it resolved to).

**Hiding noise by role.** Tests, resources (`strings.xml`, `.pbxproj`), lockfiles, and generated files co-change with production code by nature. Hide them by role instead of writing globs — and note tests count as `source`, so `--category` can't remove them:

```bash
cochange analyze . --exclude-role test,resource,lockfile,generated
cochange analyze . --focus production-source   # shortcut for all four
```

This hides files from the output; it does **not** remove them from the counts, and it does **not** move a score. `P(A|B)` still means what it says over the full history; `metrics` and `compare` compute every rate over the whole population and filter only the files they *list*; and the run reports how many files each role is hiding. So switching roles on and off changes what you see, never the statistic underneath — a hidden hub still counts against `hubFreeRate`, and a hidden hotspot still counts against `boundaryIntegrity`. Roles honour the repository's own `linguist-generated` declarations too, so `--exclude-role generated` hides what `.gitattributes` marks, not just what the file names suggest.

History-reading options (`--since`, `--branch`, `--change-unit`, …) apply **per command** — each one re-reads the Git log — so pass the same `--since` to every command to compare like with like. With no `--since` the whole history is used, and the run says so in its banner.

**AI agents:** start with `cochange guide` — the playbooks (`explore`, `align-boundaries`, `reduce-change-tax`, `split-god-class`, `extract-module`, plus `reading` for how to weigh a finding) are written so an agent can operate the tool end to end without reading this README.

## Example output

Real output from [DroidKaigi conference-app-2025](https://github.com/DroidKaigi/conference-app-2025) — three finding types in one run, evidence inline (middle findings elided):

```text
$ cochange analyze conference-app-2025
change unit: merge (auto: merge-based history (94% of mainline commits are merges,
  ~4.8 commits per merge))

Analyzed 381 commits as 381 change units (unit: merge) in 0.5s

# what the findings below rest on — here, real module roots, so nothing is withheld
module detection [derived]: nearest directory with a build file, SwiftPM
  Sources/Tests target directory, repository root (build file at top level)
  coverage: 100% of 850 files under a declared module root (34 modules,
    34 declared, trust=declared)

Review candidates (12) — heuristic interpretations of the co-change evidence

# a boundary the code ignores — and how costly it is to fix (effort)
finding-1 [boundary_mismatch/source] impact=medium effort=medium confidence=1.0
  App.kt (app-android) and AndroidAppGraph.kt (app-shared) evolve as one
  change unit across a module boundary
  5 of 5 changes to AndroidAppGraph.kt also changed App.kt (100%),
  despite living in different modules (app-android vs app-shared).

  ... 5 more boundary mismatches, incl. a Swift pair across two iOS targets ...

# one file everything drags in
finding-7 [unstable_hub/source] impact=high confidence=0.08
  KaigiAppUi.androidJvm.kt participated in 8% of multi-file changes,
  spanning 20 other modules

# one file doing two unrelated jobs — with the split lines
finding-8 [split_candidate/source] impact=medium confidence=0.9
  KaigiAppUi.ios.kt belongs to 2 independent change clusters
  ...co-changes with 5 files that fall into 2 groups with fewer than 3
  co-changes between any two members of different groups:
  group 1 (3 files): AboutTabRoute.kt, AboutNavGraph.kt, AboutNavExtension.kt;
  group 2 (2 files): libs.versions.toml, KaigiAppUi.androidJvm.kt.
```

A Go repository has a single `go.mod`, so build files alone would see one module and withhold every boundary finding. The package — the directory — is the language's own unit, so it counts as a declared boundary:

```text
$ cochange analyze cli --since "2 years ago"
change unit: merge (auto: merge-based history (87% of mainline commits are merges,
  ~3.9 commits per merge))

module detection [derived]: Go package directory, repository root (build file at top level)
  coverage: 100% of 899 files under a declared module root (256 modules,
    256 declared, trust=declared)

Review candidates (36) — heuristic interpretations of the co-change evidence

finding-1 [boundary_mismatch/source] impact=medium effort=medium confidence=1.0
  issues.go (pkg/cmd/search/issues) and prs.go (pkg/cmd/search/prs) evolve as one
  change unit across a module boundary
  8 of 8 changes to issues.go also changed prs.go (100%), despite living in
  different modules (pkg/cmd/search/issues vs pkg/cmd/search/prs).
```

`confidence` is each type's own ratio: for `boundary_mismatch` P(other | rarer), for `unstable_hub` the share of multi-file changes the file was dragged into, for `split_candidate` the share of its own changes that involved a group. Use `--json` when you need the sample-corrected strength rather than the ratio.

`clusters` groups files linked by co-change into connected components. A cluster is transitive — `A`–`B` and `B`–`C` with no `A`–`C` link still yields one `{A,B,C}` cluster, and no single change need ever have touched all three — so read it as the neighbourhood a pair sits in, not as an observed change unit. It prints one headline per cluster; the full file list is one `--show` away, so a big monolith stays readable:

```text
$ cochange clusters conference-app-2025 --min-support 5 --category source
edge thresholds: min-support=5 min-jaccard=0.25
collapsed 1 synchronized sibling family (e.g. strings.xml across 2) — use --no-collapse to expand

cluster 1: 7 files across 1 module (feature/sessions), 12 strong pairs (pair-support volume 69)
  strongest pair: TimetableItemDetailScreen.kt x TimetableItemDetailFloatingMenu.kt (7 together, jaccard 0.39)
cluster 2: 4 files across 1 module (app-shared), 6 strong pairs (pair-support volume 36)
  strongest pair: AboutNavExtension.kt x AboutNavGraph.kt (6 together, jaccard 0.86)
...
cluster 4: 4 files across 3 modules, 6 strong pairs (pair-support volume 30)
  strongest pair: AndroidAppGraph.kt x JvmAppGraph.kt (5 together, jaccard 1.00)

Next: cochange clusters conference-app-2025 --show 1 --min-support 5 --min-jaccard 0.25 --category source
```

Note the `collapsed 1 synchronized sibling family` line: `strings.xml` across two locale directories always moves as a set, so it counts as one node instead of topping the list as its own "coupling". That's learned from this repository's history — no locale or ecosystem list is built in.

Each headline recommends the exact command to expand it. `--show N` opens one cluster — here cluster 4, the Kotlin Multiplatform entry points spread across three modules but always changed in lockstep:

```text
$ cochange clusters conference-app-2025 --min-support 5 --category source --show 4
cluster 4: 4 files, 6 strong pairs (pair-support volume 30)
  app-shared/src/androidMain/.../AndroidAppGraph.kt (5 changes, app-shared)
  app-shared/src/jvmMain/.../JvmAppGraph.kt (5 changes, app-shared)
  app-android/.../App.kt (6 changes, app-android)
  app-desktop/.../Main.kt (9 changes, app-desktop)
  strongest pair: AndroidAppGraph.kt x JvmAppGraph.kt (5 together, jaccard 1.00)
```

Each cluster is a co-change neighbourhood the module structure doesn't show: the session-detail screen and its floating menu (cluster 1), and the KMP entry points above (cluster 4). Whether the whole cluster ever moved as one change is a separate question — check `pairs` for the links that actually carry it.

Synchronized sibling files (e.g. `values/strings.xml` + `values-ja/strings.xml` + … — same basename, sibling directories, one module, that history shows always move together) are collapsed into a single node so a translation or variant set can't dominate a cluster or manufacture a hub. The family is learned from the repo, not from a locale/ecosystem list, and only collapsed when the co-change actually confirms it. Raw pairs are untouched; `--no-collapse` expands them.

`inspect` returns JSON with observation / interpretations / counterSignals, plus `supportingChanges` — each backing change unit with every commit in it, resolved to subject, date, author, and per-file churn, plus `filesTouched` (the finding's files that unit moved). "Did these change for the same *reason*" is answerable without running `git show` by hand, and a unit that spread the two sides across two commits still shows both. It also carries the run it came from (pinned window, change unit, module-detection provenance, `stale`), because a finding read on its own says nothing about the conditions that produced it. For `split_candidate` it also returns `groups` — each independent partner cluster in full, with its `support` (change units where the candidate moved with that group) and `firstSeen`/`lastSeen` dates, so you can tell "two responsibilities" apart from "old vs new era of one". Those dates are bounds, not a continuous interval.

`metrics` condenses the whole analysis into a few higher-is-better scores plus a one-line reading of what to do next — meant to be trended within one repo (same options, `--json`) rather than compared across repos:

```text
$ cochange metrics conference-app-2025
window: 0.3 years  multi-file change units: 244  effective modules: 13.1 (34 distinct)

module locality         30.3%  (74/244 multi-file units contained in one module)
  adjusted for chance   28.9%  (contribution of the module structure beyond random placement — a monolith scores ~0 here)
hub-free change rate    82.4%  (201/244 units avoid the 2 hub files)
                         hub: gradle/libs.versions.toml
                         hub: .../KaigiAppUi.androidJvm.kt
boundary integrity      84.1%  (143/170 cross-module units avoid the 9 recurring hotspot pairs)
                         top hotspot: build.gradle.kts x libs.versions.toml — ~35.7 double-edits/year

reading: 13.1 effective modules x 29% adjusted locality — rich structure, frequently
  violated — the lever is aligning boundaries (cochange guide align-boundaries) and
  taming hubs (guide reduce-change-tax)
```

`adjusted for chance` is the key number: reading it together with `effective modules` separates "few modules, easy to comply with" from "many modules, actually respected", so the score can't be gamed by a coarser partition.

`compare` puts a recent window next to a baseline, so a file that was central historically but quiet lately doesn't get ranked next to one that is getting worse right now:

```text
$ cochange compare conference-app-2025 --baseline 2025-06-01 --recent 2025-08-20 --category source
baseline: 2025-06-01 (244 multi-file of 381 units)   recent: 2025-08-20 (128 multi-file of 220 units)   category: source
summary: 69 heating, 145 cooling, mean shift 0.9pp per listed file (214 files at --min-count 3)

heating up — larger share of changes recently:
  .../profile/ProfileCardScreen.kt                 2% ->  4%   (baseline 5, recent 5)
  .../droidkaigiui/session/TimetableItemCard.kt    5% ->  6%   (baseline 11, recent 8)

cooling down — was more central, quieter lately:
  .../sessions/TimetableScreen.kt                  7% ->  4%   (baseline 18, recent 5)
  .../Feature/Home/HomeScreen.swift                4% ->  2%   (baseline 10, recent 2)
```

Each rate is the file's share of that window's multi-file changes. `--baseline`/`--recent` take `30d`-style shorthand or any git `--since` expression (including absolute dates).

For a weekly cadence, `compare --json` (and `metrics --json`) give machine-readable output. `compare --json` includes `heating`, `cooling`, and `meanAbsShift` (per-listed-file mean change in participation share) plus every mover, so you can track a single "how much moved this week" number over time. Trend `meanAbsShift`, not `totalAbsShift` — the latter grows with how many files clear `--min-count`. Both windows also report their resolved start and their multi-file denominator, plus the single change unit both were counted in (`changeUnit`) — `auto` is resolved once, from the baseline, because resolving it per window would compare per-PR participation against per-author-window participation. `recentIsInsideBaseline` confirms the usual nesting; the two windows always overlap, since both end at HEAD, so a shift is a change in *share*, not a before/after difference.

## How it works

1. **Reconstructing change units** (`--change-unit`) — the goal is one PR = one change unit. Renames are normalized to the newest path, and bulk commits (formatters, mass renames) are dropped by file-count caps.
   - `merge` — walks the first-parent chain so each merge commit carries its whole side branch's diff (one PR per unit, no GitHub API needed).
   - `author-window` — groups consecutive same-author commits within 30 minutes (fixups, review-comment chains) for squash-merge histories.
   - `commit` — one commit as one unit, no grouping.
   - `auto` (default) — picks `merge` when mainline is merge-heavy; falls back to `author-window` when merges each drag in so many commits that the branch looks like a git-flow release-only mainline (and suggests pointing `--branch` at the dev branch).

   Every run prints the resolved configuration (branch, chosen change unit and why, thresholds), so the analysis is never a black box.
2. **Boundary detection** — Scans the file list at HEAD for common build files (Gradle, npm, Cargo, Go, Maven, Bazel, Python, CMake, Mix) and SwiftPM target directories, and assigns each file to its nearest module root. Where a language declares its unit of organization by directory rather than by a build file, that rule is used too: every directory containing `.go` files is a Go package, and every directory with `__init__.py` or `__init__.pyi` (PEP 561 stub-only) a Python package. The go command's own exclusions are honoured — `testdata`, a `vendor` tree its own `vendor/modules.txt` identifies, and elements starting with `_` or `.` — and a Go package covers exactly its own directory, since in Go every directory of `.go` files is itself a package. A Python package covers its own directory and any descendant that is not a package itself, because a subdirectory without `__init__.py` belongs to the nearest enclosing package. Either way a package claims only its own language's files. A namespace package with no `__init__.py` is not detected rather than guessed at. Without this a Go repository — one `go.mod` at the root — looked like a single module, and every boundary finding was withheld for the whole ecosystem. An explicit `--module-root` is authoritative and is never subdivided by these language defaults. Falls back to the top-level directory. The built-in list is deliberately thin: for anything else (Xcode targets, custom monorepo layouts), declare roots with repeatable `--module-root 'ios/Targets/*'` globs — an AI agent can derive these from the directory layout.
3. **Finding detection** — each finding type is a pluggable `FindingDetector` over shared pair statistics (`AnalysisContext`). A detector declares its `type` and a one-line `description` of what it finds; `cochange detectors` lists them, so humans and AI can see what the tool is able to discover. New types plug in without touching the pipeline.
   - `boundary_mismatch`: file pairs across module boundaries with co-change count ≥ `--min-support` (5) and conditional probability ≥ `--min-confidence` (0.6). The denominator is the less frequently changed file, i.e. the stronger direction P(other | rarer). One-directional coupling is called out as a counter-signal. Findings are ranked by **architectural interest**: `evidenceStrength × (1 − 0.5 × nameSimilarity)`, where `evidenceStrength` is Wilson-lower-bounded (a small sample is discounted) and `nameSimilarity` is a graduated 0–1 token overlap, so an interface and its impl (or `CheckoutScreen`/`CheckoutViewModel`) sinks below a screen co-changing with a pricing rule at comparable evidence. This is a ranking heuristic, not a guarantee that evidence always wins: a 5-of-5 pair with unrelated names can outrank a 20-of-25 pair whose names predicted the coupling — 5-of-5 genuinely has the higher bounded similarity. The demotion is capped at half, and both inputs (`evidenceStrength`, `nameSimilarity`) are published on every finding, so re-ranking on evidence alone takes one sort. Each finding also carries an `effort` estimate and a `couplingKind` naming what the coupling is, decided from the two endpoints' categories — and from their languages only when both endpoints are code, because a language difference means "platform boundary" only between two pieces of code. `generated` and `lockfile` → none (nobody edits those by hand); `variant-set` → none (one name under sibling directories, declarative on both sides: coordinated version bumps, per-locale resources, per-target manifests — process, not architecture); `documentation`, `build-wiring`, `manifest-and-source`, `declarative-pair`, `declaration-and-code`, `companion` → low; `parallel-implementation` (the same name under sibling directories with code on both sides — possibly duplicated logic) and `same-language` → medium; `cross-language` → high, and only when both sides really are code. So findings can be read for ROI — impact vs cost — not impact alone. A `medium` impact that is `high` effort (an entrenched cross-platform coupling) sorts differently in practice than a `medium` that is `low` effort.
   - `unstable_hub`: files participating in 20+ multi-file changes spanning 5+ other modules, ranked by participation count × module spread.
   - `split_candidate`: a file whose strong co-change partners fall into multiple independent groups once the file itself is removed — a god-file signature; the partner groups suggest where to split it.
4. **Category ranking** — Each finding is classified as `source` / `config` / `build` / `docs` / `generated`. Build files, docs, and generated code co-change with everything by design (a generated file always moves with its source of truth), so they are ranked in their own buckets (top 5 each) instead of crowding production-code findings out of the list. Generated files are detected from `.gitattributes` (`linguist-generated`, resolved via `git check-attr` so repo-declared patterns win) plus a conservative built-in list of well-known artifacts (Mockolo, Sourcery, protobuf, `*.g.dart`, `*.min.js`, …). Filter with `findings --category source` (or `--category generated` to audit the noise) or `--type unstable_hub`.
5. Findings about files no longer present at HEAD are dropped. Results are cached under `~/.cache/cochange/`, so `findings` / `inspect` work without re-analyzing.

## Motivation

Whether "code that changes together should live together"\* actually holds in your codebase is already recorded in your Git history — cochange reads it from there instead of asking you to trust an architecture diagram.

\* Constantine's cohesion (1968–), Robert C. Martin's Common Closure Principle (*"gather into components those classes that change for the same reasons and at the same times"*), Kent Beck's [*"put everything that changes at the same time in one place"*](https://newsletter.kentbeck.com/p/cohesion), and the change-coupling research line from Gall et al. (ICSM 1998) through Tornhill's *Your Code as a Crime Scene*.

## Language-agnostic by design

The analysis core (log parsing, rename tracking, change grouping, pair statistics) knows nothing about any language. Ecosystem knowledge lives in one shared catalogue of build-definition file names — used both to detect module roots and to classify files, so the two can never disagree — plus two rules a language states about itself: a directory of `.go` files is a Go package (honouring the go command's own `testdata`/`vendor`/`_`/`.` exclusions), and `__init__.py` or `__init__.pyi` (PEP 561 stub-only) marks a Python package. Third-party trees (`node_modules`, `third_party`, and `vendor` where `vendor/modules.txt` proves it) are excluded from module claims rather than reported as your structure. Everything degrades gracefully: an unknown build system means files are treated as source and modules fall back to top-level directory names, which the output labels as guessed and which no finding is built on. Verified by running the whole command set against Kotlin/KMP, Go, Rust, Python, TypeScript and Java repositories.

## Current limitations

- **Change units are exact only for merge-based histories.** In `merge` mode a change unit is precisely one PR. In `author-window` mode (squash-merge histories) units are an approximation: a PR developed over several days splits into multiple units, and unrelated same-author commits in quick succession merge into one. PR metadata from a forge API would remove that approximation and is planned only as optional enrichment.
- Finding types are `boundary_mismatch`, `unstable_hub`, and `split_candidate`; `clusters` and `pairs` expose the raw structure for everything else. "Co-located but diverging" detection (files in one module that have stopped changing together) is not a detector yet.
- No enrichment from PR / issue metadata (the commit heuristics stand in for it).
- Output is CLI text / JSON only. MCP server, SARIF, and GitHub Actions annotations are not implemented.
- Thresholds are fixed defaults (tunable via options). Automatic calibration via statistical significance (e.g. lift) is future work.
- Rename tracking uses one global newest-path alias map; simultaneous renames within a commit are handled, but conflicting renames on divergent branches can mis-attribute a file's older history.
- The full `git log` output is held in memory during parsing — fine up to hundreds of thousands of commits, but not streamed.

## License

[Apache License 2.0](LICENSE)
