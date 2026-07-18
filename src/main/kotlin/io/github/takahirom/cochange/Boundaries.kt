package io.github.takahirom.cochange

/**
 * Maps a file path to its module. Modules are detected from the file list at
 * HEAD: any directory containing a build file (Gradle/npm/Cargo/Go) is a
 * module root; files resolve to their nearest module root. Falls back to the
 * top-level directory when no build files exist.
 */
class Boundaries(headFiles: Set<String>, moduleRootGlobs: List<String> = emptyList()) {
    // A deliberately thin list of common cases — not a catalog of every build
    // system. Anything else is covered by --module-root globs, and the guide
    // tells AI agents to derive those from the repository layout.
    private val buildFileNames = setOf(
        "build.gradle", "build.gradle.kts", "package.json", "Cargo.toml", "go.mod", "pom.xml",
        "BUILD.bazel", "BUILD", "pyproject.toml", "setup.py", "CMakeLists.txt", "mix.exs",
    )

    private val moduleRoots: List<String> = run {
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

        (fromBuildFiles + fromSwiftTargets + fromGlobs)
            .distinct()
            .sortedByDescending { it.length }
    }

    private val cache = HashMap<String, String>()

    /** True when the repository root itself is a module (build file at top level). */
    private val rootIsModule = moduleRoots.contains("")

    fun moduleOf(path: String): String = cache.getOrPut(path) {
        val dir = path.substringBeforeLast('/', "")
        val root = moduleRoots.firstOrNull { it.isNotEmpty() && (dir == it || dir.startsWith("$it/")) }
        when {
            root != null -> root
            // Single-module repo: paths not claimed by a nested module all belong
            // to the root module, not to their top-level directory.
            rootIsModule -> "<root>"
            path.contains('/') -> path.substringBefore('/')
            else -> "<root>"
        }
    }
}
