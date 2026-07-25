package io.github.takahirom.cochange

/**
 * How one file's module was resolved. The distinction that matters is
 * [declared]: a module root backed by something the repository actually says
 * (a build file, a SwiftPM target directory, a user-supplied glob) versus a
 * guess made because nothing said anything. `moduleOf` always answers, so
 * without this the two are indistinguishable in the output.
 */
enum class ModuleSource(val declared: Boolean, val label: String) {
    BUILD_FILE(true, "nearest directory with a build file"),
    SWIFT_TARGET(true, "SwiftPM Sources/Tests target directory"),
    GO_PACKAGE(true, "Go package directory"),
    PYTHON_PACKAGE(true, "Python package directory (__init__.py or __init__.pyi)"),
    USER_GLOB(true, "--module-root glob"),
    ROOT_BUILD_FILE(true, "repository root (build file at top level)"),
    NOT_OUR_CODE(false, "vendored or fixture directory (not this repository's structure)"),
    TOP_LEVEL_DIR(false, "top-level directory (guessed — no build file covers this path)"),
    UNRESOLVED(false, "repository root (guessed — no module roots detected at all)"),
}

/**
 * What module detection actually managed to resolve, so a reader (or an agent)
 * can tell "these are real module boundaries" from "these are directory names".
 * [coverage] is the share of files whose module came from a declared boundary.
 */
data class ModuleDetection(
    val totalFiles: Int,
    val bySource: Map<ModuleSource, Int>,
    val moduleCount: Int,
    /**
     * Distinct modules among files whose module was *declared*. This, not
     * [moduleCount], is what decides whether a boundary claim is possible at all:
     * two guessed top-level folders are two modules by count and none by provenance.
     */
    val declaredModuleCount: Int = moduleCount,
) {
    val declaredFiles: Int = bySource.entries.filter { it.key.declared }.sumOf { it.value }
    val coverage: Double = if (totalFiles == 0) 0.0 else declaredFiles.toDouble() / totalFiles

    /** The declared signals that were actually used, best-first — the "method" half of provenance. */
    val methods: List<ModuleSource> = ModuleSource.entries.filter { it.declared && (bySource[it] ?: 0) > 0 }
}

/**
 * Maps a file path to its module. Modules are detected from the file list at
 * HEAD: any directory containing a build file (Gradle/npm/Cargo/Go) is a
 * module root; files resolve to their nearest module root. Falls back to the
 * top-level directory when no build files exist — [sourceOf] and [detection]
 * report which of the two happened, because a fallback answer looks exactly
 * like a real one otherwise.
 */
class Boundaries(headFiles: Set<String>, moduleRootGlobs: List<String> = emptyList()) {
    /**
     * `vendor` means "third-party" only on evidence. `vendor/modules.txt` is written by
     * `go mod vendor`, so it settles the question; without it a directory called `vendor`
     * may well be first-party code, and rejecting it by name alone would silently drop it
     * from every module claim.
     */
    private val vendoredTrees: Set<String> = headFiles
        .filter { it == "vendor/modules.txt" || it.endsWith("/vendor/modules.txt") }
        .map { it.removeSuffix("/modules.txt") }
        .toSet()

    /**
     * The excluded tree a path sits inside, or null. Unambiguous directory names count
     * anywhere; `vendor` counts only for the specific tree whose own `vendor/modules.txt`
     * proves it, so one Go service's vendored dependencies cannot condemn an unrelated
     * first-party `apps/vendor/`.
     */
    private fun excludedTreeOf(path: String): String? {
        val parts = path.split('/').dropLast(1)
        var prefix = ""
        for (part in parts) {
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            if (part in NOT_OUR_STRUCTURE) return prefix
            if (part == "vendor" && prefix in vendoredTrees) return prefix
        }
        return null
    }

    private fun isNotOurStructure(path: String): Boolean = excludedTreeOf(path) != null

    private companion object {
        /** Sources whose roots cover one directory and one language, not a subtree. */
        val LANGUAGE_PACKAGE_SOURCES = setOf(ModuleSource.GO_PACKAGE, ModuleSource.PYTHON_PACKAGE)

        /** Extensions a Python package claims. `.pyi` is Python; a stub is not a module of its own. */
        val PYTHON_EXTENSIONS = setOf(".py", ".pyi")

        /**
         * Third-party code and test fixtures. Their internal layout is not this
         * repository's module structure, so cochange must not claim declared provenance
         * for it — a vendored dependency bump would otherwise read as cross-module churn.
         */
        val NOT_OUR_STRUCTURE = setOf("testdata", "node_modules", "third_party")

        /**
         * Paths the go command itself skips: `testdata`, `vendor`, and any element — a
         * directory OR a file name — starting with `_` or `.`.
         */
        fun isGoIgnored(path: String): Boolean = path.split('/').any {
            it == "testdata" || it == "vendor" || it.startsWith("_") || it.startsWith(".")
        }
    }

    // A deliberately thin list of common cases — not a catalog of every build
    // system. Anything else is covered by --module-root globs, and the guide
    // tells AI agents to derive those from the repository layout.
    // Shared with FileCategory, so a file cannot be a module root here and a config file
    // there — that disagreement made a manifest/source pair read as a language boundary.
    private val buildFileNames = FileCategory.moduleRootFileNames

    /** Detected module roots, longest-first (nearest wins), each tagged with the signal that found it. */
    private val moduleRoots: List<Pair<String, ModuleSource>> = run {
        val fromBuildFiles = headFiles
            .filterNot(::isNotOurStructure)
            .filter { it.substringAfterLast('/') in buildFileNames }
            .map { it.substringBeforeLast('/', "") }

        // SwiftPM convention: below a Package.swift, each Sources/<Target> and
        // Tests/<Target> directory is a target — a module boundary — without
        // parsing the manifest.
        val packageDirs = headFiles
            .filter { it.substringAfterLast('/') == "Package.swift" }
            .map { it.substringBeforeLast('/', "") }
        val fromSwiftTargets = packageDirs.flatMap { pkg ->
            val prefix = if (pkg.isEmpty()) "" else "$pkg/"
            listOf("Sources", "Tests").flatMap { kind ->
                headFiles
                    .filter { it.startsWith("$prefix$kind/") }
                    .mapNotNull { path ->
                        val rest = path.removePrefix("$prefix$kind/")
                        val target = rest.substringBefore('/', "")
                        if (target.isEmpty()) null else "$prefix$kind/$target"
                    }
            }
        }

        // Some languages declare their unit of code organization by directory rather
        // than by a per-directory build file. Go has exactly one go.mod for a whole
        // repository, so build files alone see one module and every boundary finding
        // is withheld — while the language's own unit, the package, is "the directory".
        // Python says the same thing with __init__.py. These are language rules, not
        // guesses about a layout, which is why they count as declared.
        // The go command ignores directories named testdata or vendor, any path element
        // starting with "_" or ".", and FILES whose names start with those characters.
        // A directory of fixtures is not a package, so two synchronized fixture updates
        // must not read as two modules changing together.
        val fromGoPackages = headFiles
            .filter { it.endsWith(".go") && !isGoIgnored(it) }
            .map { it.substringBeforeLast('/', "") }
        // `__init__.pyi` marks a PEP 561 stub-only package; without it a stubs tree had
        // no package root at all and every stub collapsed into the root module.
        val fromPythonPackages = headFiles
            .filterNot(::isNotOurStructure)
            .filter { it.substringAfterLast('/') in setOf("__init__.py", "__init__.pyi") }
            .map { it.substringBeforeLast('/', "") }

        // User-supplied module roots: globs matched against every directory
        // prefix of the tracked files (e.g. --module-root 'legacy/ios/Targets/*').
        val globs = moduleRootGlobs.map(GitLog::globToRegex)
        val fromGlobs = if (globs.isEmpty()) emptyList() else {
            val dirs = HashSet<String>()
            for (file in headFiles) {
                var slash = file.indexOf('/')
                while (slash >= 0) {
                    dirs.add(file.substring(0, slash))
                    slash = file.indexOf('/', slash + 1)
                }
            }
            dirs.filter { dir -> globs.any { it.matches(dir) } }
        }

        val tagged = fromGlobs.map { it to ModuleSource.USER_GLOB } +
            fromBuildFiles.map { it to ModuleSource.BUILD_FILE } +
            fromSwiftTargets.map { it to ModuleSource.SWIFT_TARGET } +
            fromGoPackages.map { it to ModuleSource.GO_PACKAGE } +
            fromPythonPackages.map { it to ModuleSource.PYTHON_PACKAGE }
        // Deduplicate by (directory, source), NOT by directory: Go and Python roots have
        // different coverage predicates, so `services/pkg` declared by both must keep both
        // entries or `app.py` loses its Python package to the Go one and falls back to a
        // guessed top-level folder.
        tagged
            .distinct()
            .sortedByDescending { it.first.length }
    }

    private val cache = HashMap<String, Pair<String, ModuleSource>>()

    /**
     * True when a BUILD FILE at the top level makes the repository root a module, so
     * files no nested module claims belong to it. A root Go package must not do this:
     * `main.go` at the top level says nothing about `scripts/deploy.sh`, and treating
     * it as declared provenance manufactured boundaries out of unrelated files.
     */
    private val rootIsModule = moduleRoots.any { (path, source) ->
        path.isEmpty() && source !in LANGUAGE_PACKAGE_SOURCES
    }

    /**
     * A language package covers exactly its own directory and its own language's files:
     * Go and Python packages are per-directory, never recursive, and a shell script
     * sitting in a Go package directory is not part of that package.
     */
    private fun covers(root: String, source: ModuleSource, path: String, dir: String): Boolean = when (source) {
        // A Go package is exactly one directory: every directory holding .go files is
        // itself a package, so exact matching is complete.
        ModuleSource.GO_PACKAGE -> dir == root && path.endsWith(".go") && !isGoIgnored(path)
        // A Python package is not. A subdirectory without __init__.py is not a package of
        // its own, and its modules belong to the nearest ancestor that is — matching
        // exactly meant `src/flask/sansio/app.py` fell all the way back to the root
        // module and appeared to cross a boundary with its own package's files.
        ModuleSource.PYTHON_PACKAGE ->
            // root == "" is the repository root, where "$root/" would be "/" and match
            // nothing, so a root-level package failed to cover sansio/app.py.
            PYTHON_EXTENSIONS.any { path.endsWith(it) } &&
                (dir == root || root.isEmpty() || dir.startsWith("$root/"))
        else -> root.isNotEmpty() && (dir == root || dir.startsWith("$root/"))
    }

    private fun resolve(path: String): Pair<String, ModuleSource> = cache.getOrPut(path) {
        val dir = path.substringBeforeLast('/', "")
        // "Not our structure" is decided FIRST. A dependency tree carries its own manifests
        // (node_modules/pkg/package.json), so letting roots match first turned every
        // vendored package into a declared module of this repository — and a broad
        // `--module-root 'apps/*'` swallowed the dependencies underneath it.
        val excludedTree = excludedTreeOf(path)
        // An explicit --module-root is authoritative over its whole subtree — but only
        // over an excluded tree it actually points at or into. A broad
        // `--module-root 'apps/*'` must not silently adopt `apps/a/node_modules/...`,
        // while `--module-root 'vendor/x'` is exactly how a user says "analyse this".
        val explicit = moduleRoots.firstOrNull { (r, src) ->
            src == ModuleSource.USER_GLOB && covers(r, src, path, dir) &&
                (excludedTree == null || r == excludedTree || r.startsWith("$excludedTree/"))
        }
        val notOurs = when {
            excludedTree != null -> excludedTree to ModuleSource.NOT_OUR_CODE
            // A Go file the go command itself skips must not be claimed by a build root
            // either: paired against a real package it looks like two declared modules.
            path.endsWith(".go") && isGoIgnored(path) ->
                path.substringBeforeLast('/', "<root>") to ModuleSource.NOT_OUR_CODE
            else -> null
        }
        // Nearest root wins, and an explicit --module-root outranks a language default:
        // `--module-root 'internal/*'` means internal/a is the module, and the Go packages
        // nested inside it must not subdivide it further.
        val root = explicit
            ?: notOurs?.let { return@getOrPut it }
            ?: moduleRoots.firstOrNull { (r, src) -> covers(r, src, path, dir) }
        when {
            root != null -> (if (root.first.isEmpty()) "<root>" else root.first) to root.second
            // Single-module repo: paths not claimed by a nested module all belong
            // to the root module, not to their top-level directory.
            rootIsModule -> "<root>" to ModuleSource.ROOT_BUILD_FILE
            path.contains('/') -> path.substringBefore('/') to ModuleSource.TOP_LEVEL_DIR
            else -> "<root>" to ModuleSource.UNRESOLVED
        }
    }

    fun moduleOf(path: String): String = resolve(path).first

    /** Which signal produced [moduleOf]'s answer for [path]. */
    fun sourceOf(path: String): ModuleSource = resolve(path).second

    /** Provenance and coverage of module detection over [files]. */
    fun detection(files: Collection<String>): ModuleDetection {
        val bySource = HashMap<ModuleSource, Int>()
        val modules = HashSet<String>()
        val declaredModules = HashSet<String>()
        for (file in files) {
            val (module, source) = resolve(file)
            bySource.merge(source, 1, Int::plus)
            modules.add(module)
            if (source.declared) declaredModules.add(module)
        }
        return ModuleDetection(
            totalFiles = files.size,
            bySource = bySource,
            moduleCount = modules.size,
            declaredModuleCount = declaredModules.size,
        )
    }
}
