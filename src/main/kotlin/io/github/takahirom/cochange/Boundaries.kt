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
    PYTHON_PACKAGE(true, "Python package directory (__init__.py)"),
    USER_GLOB(true, "--module-root glob"),
    ROOT_BUILD_FILE(true, "repository root (build file at top level)"),
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
    private companion object {
        /** Sources whose roots cover one directory and one language, not a subtree. */
        val LANGUAGE_PACKAGE_SOURCES = setOf(ModuleSource.GO_PACKAGE, ModuleSource.PYTHON_PACKAGE)
    }

    // A deliberately thin list of common cases — not a catalog of every build
    // system. Anything else is covered by --module-root globs, and the guide
    // tells AI agents to derive those from the repository layout.
    private val buildFileNames = setOf(
        "build.gradle", "build.gradle.kts", "package.json", "Cargo.toml", "go.mod", "pom.xml",
        "BUILD.bazel", "BUILD", "pyproject.toml", "setup.py", "CMakeLists.txt", "mix.exs",
    )

    /** Detected module roots, longest-first (nearest wins), each tagged with the signal that found it. */
    private val moduleRoots: List<Pair<String, ModuleSource>> = run {
        val fromBuildFiles = headFiles
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
        // Go ignores directories named testdata, and any path element starting with
        // "_" or "." — a fixture directory is not a package, and two synchronized
        // fixture updates must not become a boundary finding between "modules".
        fun goIgnored(dir: String) = dir.split('/')
            .any { it == "testdata" || it.startsWith("_") || it.startsWith(".") }
        val fromGoPackages = headFiles
            .filter { it.endsWith(".go") }
            .map { it.substringBeforeLast('/', "") }
            .filterNot(::goIgnored)
        val fromPythonPackages = headFiles
            .filter { it.substringAfterLast('/') == "__init__.py" }
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
        tagged
            .distinctBy { it.first }
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
        ModuleSource.GO_PACKAGE -> dir == root && path.endsWith(".go")
        ModuleSource.PYTHON_PACKAGE -> dir == root && path.endsWith(".py")
        else -> root.isNotEmpty() && (dir == root || dir.startsWith("$root/"))
    }

    private fun resolve(path: String): Pair<String, ModuleSource> = cache.getOrPut(path) {
        val dir = path.substringBeforeLast('/', "")
        // Nearest root wins, EXCEPT that an explicit --module-root is authoritative:
        // a user who says `--module-root 'internal/*'` means internal/a is the module,
        // and the Go packages nested inside it must not subdivide it further.
        val root = moduleRoots.firstOrNull { (r, src) ->
            src == ModuleSource.USER_GLOB && covers(r, src, path, dir)
        } ?: moduleRoots.firstOrNull { (r, src) -> covers(r, src, path, dir) }
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
