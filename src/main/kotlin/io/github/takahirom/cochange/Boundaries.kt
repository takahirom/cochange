package io.github.takahirom.cochange

/**
 * Maps a file path to its module. Modules are detected from the file list at
 * HEAD: any directory containing a build file (Gradle/npm/Cargo/Go) is a
 * module root; files resolve to their nearest module root. Falls back to the
 * top-level directory when no build files exist.
 */
class Boundaries(headFiles: Set<String>) {
    private val buildFileNames = setOf(
        "build.gradle", "build.gradle.kts", "package.json", "Cargo.toml", "go.mod", "pom.xml",
    )

    private val moduleRoots: List<String> = headFiles
        .filter { it.substringAfterLast('/') in buildFileNames }
        .map { it.substringBeforeLast('/', "") }
        .distinct()
        .sortedByDescending { it.length }

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
