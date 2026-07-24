# cochange

**Code that changes together should live together.**

cochange reads your Git history to find code that **changes together but lives apart** — the coupling your module structure hides — and turns it into evidence-backed findings for humans and AI. It looks at what actually changes together, not at static dependencies.

## What you get

- **Where to start refactoring — ranked by ROI, not vibes.** Which files drag the most changes across module boundaries, with the commits that prove it, plus an effort estimate (an entrenched Kotlin↔Swift coupling costs more to fix than a generated-file one) so you sort by impact vs cost (`analyze`, `findings`).
- **Module boundaries the code is asking for.** Files that always move together — ready-made "extract a module" proposals hiding in a monolith (`clusters`).
- **Proof a migration finished.** A parallel old/new implementation shows up as one cluster — every fix still paid twice; when the cluster disappears, the migration is actually done.
- **A structure score you can trend.** The whole history reduced to a few higher-is-better numbers, so you can tell if a refactor helped or just moved things around (`metrics`).
- **A map for AI agents.** `--json` + `guide` hand a coding agent the change structure of an unfamiliar repo before it reads a line.

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
cochange clusters /path/to/repo --category source      # de-facto change units (grouped pairs)
cochange detectors                                     # what this tool can find
cochange metrics /path/to/repo --json                  # repo-level scores (higher = better), for trending over time
cochange guide                                         # playbooks: which commands, in what order, and how to read the results
```

The only required input is a Git repository — zero config, language-agnostic, fully local.

History-reading options (`--since`, `--branch`, `--change-unit`, …) apply **per command** — each one re-reads the Git log — so pass the same `--since` to every command to compare like with like. With no `--since` the whole history is used, and the run says so in its banner.

**AI agents:** start with `cochange guide` — the playbooks (`explore`, `align-boundaries`, `reduce-change-tax`, `split-god-class`, `extract-module`, plus `reading` for how to weigh a finding) are written so an agent can operate the tool end to end without reading this README.

## Example output

Real output from [DroidKaigi conference-app-2025](https://github.com/DroidKaigi/conference-app-2025):

```text
$ cochange analyze conference-app-2025
change unit: merge (auto: merge-based history (94% of mainline commits are merges,
  ~4.8 commits per merge))

Analyzed 381 commits as 381 change units (unit: merge) in 0.5s

finding-1 [boundary_mismatch/source] impact=medium effort=medium confidence=1.0
  App.kt (app-android) and AndroidAppGraph.kt (app-shared) evolve as one
  change unit across a module boundary
  5 of 5 changes to AndroidAppGraph.kt also changed App.kt (100%),
  despite living in different modules (app-android vs app-shared).

finding-6 [unstable_hub/source] impact=high
  KaigiAppUi.androidJvm.kt participated in 8% of multi-file changes,
  spanning 21 other modules

finding-7 [split_candidate/source] impact=medium
  KaigiAppUi.ios.kt belongs to 2 independent change clusters
  ...strongly co-changes with 5 files that fall into 2 groups with no
  co-change between them: group 1 (3 files): AboutTabRoute.kt,
  AboutNavGraph.kt, AboutNavExtension.kt; group 2 (2 files):
  libs.versions.toml, KaigiAppUi.androidJvm.kt.
```

`clusters` groups strongly co-changing files into the codebase's de-facto change units. It prints one headline per cluster; the full file list is one `--show` away, so a big monolith stays readable:

```text
$ cochange clusters conference-app-2025 --min-support 5 --category source
cluster 1: 8 files across 1 module (feature/sessions), 14 strong pairs (pair-support volume 81)
  strongest pair: strings.xml x strings.xml (7 together, jaccard 0.88)
cluster 2: 4 files across 1 module (app-shared), 6 strong pairs (pair-support volume 36)
  strongest pair: KaigiAppUi.ios.kt x AboutNavExtension.kt (6 together, jaccard 0.60)
...
cluster 4: 4 files across 3 modules, 6 strong pairs (pair-support volume 30)
  strongest pair: App.kt x AndroidAppGraph.kt (5 together, jaccard 0.83)

Next: cochange clusters conference-app-2025 --show 1 --min-support 5 --min-jaccard 0.25 --category source
```

Each headline recommends the exact command to expand it. `--show N` opens one cluster — here cluster 4, the Kotlin Multiplatform entry points spread across three modules but always changed in lockstep:

```text
$ cochange clusters conference-app-2025 --min-support 5 --category source --show 4
cluster 4: 4 files, 6 strong pairs (pair-support volume 30)
  app-android/.../App.kt (6 changes, app-android)
  app-shared/src/androidMain/.../AndroidAppGraph.kt (5 changes, app-shared)
  app-shared/src/jvmMain/.../JvmAppGraph.kt (5 changes, app-shared)
  app-desktop/.../Main.kt (9 changes, app-desktop)
  strongest pair: App.kt x AndroidAppGraph.kt (5 together, jaccard 0.83)
```

Each cluster is a real change unit the module structure doesn't show: the session-detail screen with its translated strings (cluster 1), and the KMP entry points above (cluster 4).

`inspect` returns JSON with observation / interpretations / counterSignals / supportingChanges (commit hashes), giving an AI a concrete starting point before it reads any code.

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

## How it works

1. **Reconstructing change units** (`--change-unit auto|merge|author-window|commit`) — Renames are normalized to the newest path, and bulk changes (formatters, mass renames) are dropped by file-count caps. `merge` walks the first-parent chain so each merge commit carries its whole side branch's diff — one PR becomes one change unit, with no GitHub API needed. `author-window` groups consecutive commits by the same author within 30 minutes (fixups, review-comment chains) for squash-merge histories. `auto` (default) picks `merge` when a meaningful share of mainline commits are merges — unless each merge drags in so many commits that the branch looks like a git-flow release-only mainline, in which case it falls back to `author-window` and suggests pointing `--branch` at the development branch. Every run starts by printing the resolved configuration (branch, chosen change unit and why, thresholds), so the analysis is never a black box.
2. **Boundary detection** — Scans the file list at HEAD for common build files (Gradle, npm, Cargo, Go, Maven, Bazel, Python, CMake, Mix) and SwiftPM target directories, and assigns each file to its nearest module root; falls back to the top-level directory. The built-in list is deliberately thin: for anything else (Xcode targets, custom monorepo layouts), declare roots with repeatable `--module-root 'ios/Targets/*'` globs — an AI agent can derive these from the directory layout.
3. **Finding detection** — each finding type is a pluggable `FindingDetector` over shared pair statistics (`AnalysisContext`). A detector declares its `type` and a one-line `description` of what it finds; `cochange detectors` lists them, so humans and AI can see what the tool is able to discover. New types plug in without touching the pipeline.
   - `boundary_mismatch`: file pairs across module boundaries with co-change count ≥ `--min-support` (5) and conditional probability ≥ `--min-confidence` (0.6). The denominator is the less frequently changed file, i.e. the stronger direction P(other | rarer). One-directional coupling is called out as a counter-signal. Companion pairs whose names predict the coupling (Foo / DefaultFoo / FakeFoo) are ranked below pairs with unrelated names — a screen and a pricing rule co-changing is architecturally surprising; an interface and its implementation is not. Each finding also carries an `effort` estimate (`couplingKind`: generated → none, same-language interface/impl → low, unrelated same-language → medium, cross-language e.g. Kotlin↔Swift → high) so findings can be read for ROI — impact vs cost — not impact alone. A `medium` impact that is `high` effort (an entrenched cross-platform coupling) sorts differently in practice than a `medium` that is `low` effort.
   - `unstable_hub`: files participating in 20+ multi-file changes spanning 5+ other modules, ranked by participation count × module spread.
   - `split_candidate`: a file whose strong co-change partners fall into multiple independent groups once the file itself is removed — a god-file signature; the partner groups suggest where to split it.
4. **Category ranking** — Each finding is classified as `source` / `config` / `build` / `docs` / `generated`. Build files, docs, and generated code co-change with everything by design (a generated file always moves with its source of truth), so they are ranked in their own buckets (top 5 each) instead of crowding production-code findings out of the list. Generated files are detected from `.gitattributes` (`linguist-generated`, resolved via `git check-attr` so repo-declared patterns win) plus a conservative built-in list of well-known artifacts (Mockolo, Sourcery, protobuf, `*.g.dart`, `*.min.js`, …). Filter with `findings --category source` (or `--category generated` to audit the noise) or `--type unstable_hub`.
5. Findings about files no longer present at HEAD are dropped. Results are cached under `~/.cache/cochange/`, so `findings` / `inspect` work without re-analyzing.

## Motivation

Whether "code that changes together should live together"\* actually holds in your codebase is already recorded in your Git history — cochange reads it from there instead of asking you to trust an architecture diagram.

\* Constantine's cohesion (1968–), Robert C. Martin's Common Closure Principle (*"gather into components those classes that change for the same reasons and at the same times"*), Kent Beck's [*"put everything that changes at the same time in one place"*](https://newsletter.kentbeck.com/p/cohesion), and the change-coupling research line from Gall et al. (ICSM 1998) through Tornhill's *Your Code as a Crime Scene*.

## Language-agnostic by design

The analysis core (log parsing, rename tracking, change grouping, pair statistics) knows nothing about any language. Ecosystem knowledge is isolated in two heuristic lists — module-root markers (Gradle, npm, Cargo, Go, Maven) and build-file classification (plus Makefile, Dockerfile, etc.) — and both degrade gracefully: an unknown build system just means files are treated as source and modules fall back to top-level directories. Verified on a Kotlin multi-module Android app and a TypeScript npm-workspaces monorepo, both with histories in the tens of thousands of commits, each analyzed in a few seconds.

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
