package io.github.takahirom.cochange

/**
 * Coarse role of a file for *exclusion* purposes — orthogonal to [FileCategory]
 * (which ranks findings). Tests, resources, lockfiles and generated artifacts
 * co-change with production code as a matter of course, so being able to drop
 * them by role gives a cleaner signal than writing a pile of `--exclude` globs.
 * Note tests are `source` under [FileCategory], so `--category` alone can't drop
 * them — that's why role exists.
 */
object FileRole {
    const val TEST = "test"
    const val RESOURCE = "resource"
    const val LOCKFILE = "lockfile"
    const val GENERATED = "generated"
    const val SOURCE = "source"

    /** Roles a user may exclude. `production-source` (via `--focus`) means "all of these". */
    val EXCLUDABLE = listOf(TEST, RESOURCE, LOCKFILE, GENERATED)

    private val lockfileNames = setOf(
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "podfile.lock",
        "cargo.lock", "gemfile.lock", "go.sum", "poetry.lock", "composer.lock",
    )
    private val resourceExtensions = setOf("xml", "strings", "xcstrings", "storyboard", "xib", "pbxproj", "plist")

    fun of(path: String): String {
        val name = path.substringAfterLast('/')
        val lower = path.lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            isTest(lower, name) -> TEST
            name.lowercase() in lockfileNames || ext == "lock" -> LOCKFILE
            FileCategory.looksGenerated(path) -> GENERATED
            isResource(lower, ext) -> RESOURCE
            else -> SOURCE
        }
    }

    private fun isTest(lower: String, name: String): Boolean {
        if (TEST_DIR_SEGMENTS.any { lower.contains(it) }) return true
        // Dotted forms (foo.test.ts, foo.spec.ts).
        if (lower.contains(".test.") || lower.contains(".spec.")) return true
        // Suffix/prefix forms on the stem, case-sensitive so `latest.kt` isn't a test:
        // FooTest / FooTests / FooSpec, foo_test / foo_spec, test_foo.
        val stem = name.substringBefore('.')
        return stem.endsWith("Test") || stem.endsWith("Tests") || stem.endsWith("Spec") ||
            stem.endsWith("_test") || stem.endsWith("_tests") || stem.endsWith("_spec") ||
            stem.startsWith("test_")
    }

    private val TEST_DIR_SEGMENTS = listOf(
        "/test/", "/tests/", "/androidtest/", "/androidunittest/", "/commontest/",
        "/itest/", "/integrationtest/", "/__tests__/", "/__mocks__/", "/spec/",
    )

    private fun isResource(lower: String, ext: String): Boolean =
        ext in resourceExtensions || lower.contains("/res/") || lower.contains("/resources/") ||
            lower.contains("/composeresources/") || lower.contains("/assets/")

    /** Validate a user-supplied role name for `--exclude-role`. */
    fun validateExcludable(role: String): String {
        require(role in EXCLUDABLE) { "unknown role '$role' — choose from: ${EXCLUDABLE.joinToString(", ")}" }
        return role
    }
}
