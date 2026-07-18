# cochange

**Code that changes together should live together.**

A CLI that discovers the *actual* change units of a codebase from Git history and presents them as evidence-backed findings for humans and AI. It looks at what actually changes together, not at static dependencies.

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
cochange guide                                         # playbooks: which commands, in what order, and how to read the results
```

**AI agents:** start with `cochange guide` — the playbooks (`explore`, `align-boundaries`, `reduce-change-tax`, `split-god-class`, `extract-module`, plus `reading` for how to weigh a finding) are written so an agent can operate the tool end to end without reading this README.

The only required input is a Git repository — zero config, language-agnostic, fully local.

## Motivation

"Code that changes together should live together" — plenty of people have said some version of this principle*, and I think they're right. Whether it actually holds in your codebase is already recorded in your Git history, so this tool reads it from there.

\* Constantine's cohesion (1968–), Robert C. Martin's Common Closure Principle (*"gather into components those classes that change for the same reasons and at the same times"*), Kent Beck's [*"put everything that changes at the same time in one place"*](https://newsletter.kentbeck.com/p/cohesion), and the change-coupling research line from Gall et al. (ICSM 1998) through Tornhill's *Your Code as a Crime Scene*.

## Example output

Real output from [DroidKaigi conference-app-2025](https://github.com/DroidKaigi/conference-app-2025):

```text
$ cochange analyze conference-app-2025
change unit: merge (auto: merge-based history (94% of mainline commits are merges,
  ~4.8 commits per merge))

Analyzed 381 commits as 381 change units (unit: merge) in 0.5s

finding-1 [boundary_mismatch/source] impact=medium confidence=1.0
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

`clusters` groups strongly co-changing files into the codebase's de-facto change units:

```text
$ cochange clusters conference-app-2025 --min-support 5 --category source
cluster 1: 8 files, 14 strong pairs (pair-support volume 81)
  feature/sessions/.../TimetableItemDetailContent.kt (11 changes)
  feature/sessions/.../TimetableItemDetailScreen.kt (14 changes)
  ... 4 more detail components ...
  feature/sessions/.../values-ja/strings.xml (8 changes)
  feature/sessions/.../values/strings.xml (7 changes)

cluster 4: 4 files, 6 strong pairs (pair-support volume 30)
  app-android/.../App.kt (6 changes, app-android)
  app-shared/src/androidMain/.../AndroidAppGraph.kt (5 changes, app-shared)
  app-shared/src/jvmMain/.../JvmAppGraph.kt (5 changes, app-shared)
  app-desktop/.../Main.kt (9 changes, app-desktop)
  strongest pair: App.kt x AndroidAppGraph.kt (5 together, jaccard 0.83)
```

Each cluster is a real change unit the module structure doesn't show: the session-detail screen with its translated strings (cluster 1), and the Kotlin Multiplatform entry points spread across three modules but always changed in lockstep (cluster 4).

`inspect` returns JSON with observation / interpretations / counterSignals / supportingChanges (commit hashes), giving an AI a concrete starting point before it reads any code.

## What to use it for

- **Module extraction candidates** — `clusters` shows the de-facto change units inside a monolith. A self-contained cluster (screen + logic + models + tests that always move together) is a ready-made module boundary proposal.
- **Consolidation candidates** — `boundary_mismatch` findings show code that the module structure separates but every change treats as one thing: candidates for moving into one module, or for an interface that absorbs the shared reason to change.
- **Refactoring priority by change tax** — `unstable_hub` findings quantify which files sit inside the largest share of everyone's changes. A hub participating in 7% of all PRs is a measurable, recurring cost — worth restructuring before code that merely looks ugly.
- **Migration tracking** — parallel old/new implementations showing up as one cluster means every fix is still being paid twice; the cluster disappearing is evidence the migration actually finished.
- **AI-assisted architecture work** — `findings --json` and `inspect` give a coding agent the change structure of an unfamiliar codebase before it reads a single file: where to look, what to suspect, and which commits prove it.

## How it works

1. **Reconstructing change units** (`--change-unit auto|merge|author-window|commit`) — Renames are normalized to the newest path, and bulk changes (formatters, mass renames) are dropped by file-count caps. `merge` walks the first-parent chain so each merge commit carries its whole side branch's diff — one PR becomes one change unit, with no GitHub API needed. `author-window` groups consecutive commits by the same author within 30 minutes (fixups, review-comment chains) for squash-merge histories. `auto` (default) picks `merge` when a meaningful share of mainline commits are merges — unless each merge drags in so many commits that the branch looks like a git-flow release-only mainline, in which case it falls back to `author-window` and suggests pointing `--branch` at the development branch. Every run starts by printing the resolved configuration (branch, chosen change unit and why, thresholds), so the analysis is never a black box.
2. **Boundary detection** — Scans the file list at HEAD for build files (build.gradle(.kts), package.json, Cargo.toml, go.mod, pom.xml) and assigns each file to its nearest module root; falls back to the top-level directory.
3. **Finding detection** — each finding type is a pluggable `FindingDetector` over shared pair statistics (`AnalysisContext`). A detector declares its `type` and a one-line `description` of what it finds; `cochange detectors` lists them, so humans and AI can see what the tool is able to discover. New types (e.g. split candidates) plug in without touching the pipeline.
   - `boundary_mismatch`: file pairs across module boundaries with co-change count ≥ `--min-support` (5) and conditional probability ≥ `--min-confidence` (0.6). The denominator is the less frequently changed file, i.e. the stronger direction P(other | rarer). One-directional coupling is called out as a counter-signal. Companion pairs whose names predict the coupling (Foo / DefaultFoo / FakeFoo) are ranked below pairs with unrelated names — a screen and a pricing rule co-changing is architecturally surprising; an interface and its implementation is not.
   - `unstable_hub`: files participating in 20+ multi-file changes spanning 5+ other modules, ranked by participation count × module spread.
4. **Category ranking** — Each finding is classified as `source` / `config` / `build` / `docs`. Build files and docs co-change with everything by design, so they are ranked in their own buckets (top 5 each) instead of crowding production-code findings out of the list. Filter with `findings --category source` or `--type unstable_hub`.
5. Findings about files no longer present at HEAD are dropped. Results are cached under `~/.cache/cochange/`, so `findings` / `inspect` work without re-analyzing.

## Language-agnostic by design

The analysis core (log parsing, rename tracking, change grouping, pair statistics) knows nothing about any language. Ecosystem knowledge is isolated in two heuristic lists — module-root markers (Gradle, npm, Cargo, Go, Maven) and build-file classification (plus Makefile, Dockerfile, etc.) — and both degrade gracefully: an unknown build system just means files are treated as source and modules fall back to top-level directories. Verified on a Kotlin multi-module Android app and a TypeScript npm-workspaces monorepo, both with histories in the tens of thousands of commits, each analyzed in a few seconds.

## Current limitations

- **Change units are exact only for merge-based histories.** In `merge` mode a change unit is precisely one PR. In `author-window` mode (squash-merge histories) units are an approximation: a PR developed over several days splits into multiple units, and unrelated same-author commits in quick succession merge into one. PR metadata from a forge API would remove that approximation and is planned only as optional enrichment.
- Finding types are `boundary_mismatch` and `unstable_hub`; `clusters` and `pairs` expose the raw structure for everything else. Split candidates (one file belonging to multiple independent change clusters) and "co-located but diverging" detection are not detectors yet.
- No enrichment from PR / issue metadata (the commit heuristics stand in for it).
- Output is CLI text / JSON only. MCP server, SARIF, and GitHub Actions annotations are not implemented.
- Thresholds are fixed defaults (tunable via options). Automatic calibration via statistical significance (e.g. lift) is future work.
- Rename tracking uses one global newest-path alias map; simultaneous renames within a commit are handled, but conflicting renames on divergent branches can mis-attribute a file's older history.
- The full `git log` output is held in memory during parsing — fine up to hundreds of thousands of commits, but not streamed.

## License

[Apache License 2.0](LICENSE)
