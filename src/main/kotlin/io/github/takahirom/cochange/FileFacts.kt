package io.github.takahirom.cochange

/**
 * Everything the tool infers about one path, decided once and in one place.
 *
 * These three questions used to be answered independently — [FileCategory] for ranking,
 * [FileRole] for `--exclude-role`, and a private `langKey` for effort — and nearly every
 * defect found while hardening this tool was two of them disagreeing:
 *
 * - `pyproject.toml` was a module root to `Boundaries` and a *config* file to
 *   `FileCategory`, so a manifest paired with its own code read as "two languages meeting".
 * - `list_test.go` is a different [FileRole] but is still code, and was described as
 *   "configuration or a resource rather than code".
 * - A per-locale `strings.xml` is in the SOURCE *category* but is a resource, and a pair of
 *   translations was called "parallel implementations, check for duplicated logic".
 *
 * Answering all three from one descriptor means the next classification change lands in
 * one place and cannot leave the others behind.
 */
data class FileFacts(
    val path: String,
    /** Ranking bucket: source / config / build / docs / generated. */
    val category: String,
    /** What kind of file this is for `--exclude-role`: source / test / resource / lockfile / generated. */
    val role: String,
    /**
     * Language family, or null when the path carries no language: no extension
     * (`LICENSE`, `Makefile`) or a dotfile with no stem (`.gitignore`). A file with no
     * language is not a platform away from anything, so it can never be half of a
     * cross-language coupling.
     */
    val language: String?,
) {
    /**
     * True when this is code someone writes by hand. Narrower than
     * `category == SOURCE`, which is the default bucket and therefore catches resources,
     * and wider than `role == SOURCE`, which excludes tests — and a test is code.
     */
    val isCode: Boolean
        get() = category == FileCategory.SOURCE && role !in NOT_CODE_ROLES

    companion object {
        private val NOT_CODE_ROLES = setOf(FileRole.RESOURCE, FileRole.LOCKFILE, FileRole.GENERATED)

        /**
         * Language families. Related languages share a key so a companion pair inside one
         * toolchain (`foo.c` / `foo.h`) is not mistaken for a platform boundary. An
         * unmapped but real extension keeps its own key, so a genuine boundary is not
         * understated just because the table is thin.
         */
        private fun languageOf(path: String): String? {
            val name = path.substringAfterLast('/')
            // "LICENSE" has no dot; ".gitignore" has one but nothing before it.
            if ('.' !in name || name.substringBeforeLast('.', "").isEmpty()) return null
            return when (val ext = name.substringAfterLast('.').lowercase()) {
                "kt", "kts", "java" -> "jvm"
                "swift" -> "swift"
                // C, C++, Objective-C and their shared headers are one native family.
                "c", "h", "hh", "cpp", "cc", "cxx", "hpp", "hxx", "m", "mm" -> "native"
                "ts", "tsx", "js", "jsx" -> "js"
                "py", "pyi" -> "py"
                "go" -> "go"
                "rs" -> "rust"
                "dart" -> "dart"
                "rb" -> "ruby"
                else -> ext
            }
        }

        /**
         * [generated] is the set the repository itself declares via `.gitattributes`
         * (`linguist-generated`), which both the category and the role must honour — the
         * role used to ignore it, so `--exclude-role generated` missed those files.
         */
        fun of(path: String, generated: Set<String> = emptySet()): FileFacts = FileFacts(
            path = path,
            category = if (path in generated) FileCategory.GENERATED else FileCategory.of(path),
            role = FileRole.of(path, generated),
            language = languageOf(path),
        )
    }
}
