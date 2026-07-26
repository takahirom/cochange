package io.github.takahirom.cochange

import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Persists analyses per repository under ~/.cache/cochange/ so that
 * `findings` / `inspect` work without re-running the analysis. Each analysis is
 * a named snapshot (default: "findings"), so runs over different windows can be
 * kept side by side instead of overwriting each other.
 */
object Store {
    /** The snapshot used when the user passes no `--save` / `--analysis` name. */
    const val DEFAULT_NAME = "findings"

    // encodeDefaults so schemaVersion and every `tier` are always present in the JSON
// a consumer reads; ignoreUnknownKeys so a newer field doesn't break an older reader.
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val defaultBaseDir: File
        get() = File(System.getProperty("user.home"), ".cache/cochange")

    private fun dirFor(repo: File, baseDir: File): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(repo.canonicalPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(baseDir, digest)
    }

    /** Snapshot names are used as filenames, so keep them to a safe, predictable set. */
    fun validateName(name: String): String {
        require(name.isNotEmpty() && name.length <= 100 && name.all { it.isLetterOrDigit() || it in "._-" }) {
            "invalid analysis name '$name' — use letters, digits, '.', '_', '-' (max 100 chars)"
        }
        return name
    }

    fun save(repo: File, result: AnalysisResult, name: String = DEFAULT_NAME, baseDir: File = defaultBaseDir) {
        val dir = dirFor(repo, baseDir)
        dir.mkdirs()
        File(dir, "${validateName(name)}.json").writeText(json.encodeToString(AnalysisResult.serializer(), result))
    }

    fun load(repo: File, name: String = DEFAULT_NAME, baseDir: File = defaultBaseDir): AnalysisResult? {
        val file = File(dirFor(repo, baseDir), "${validateName(name)}.json")
        if (!file.exists()) return null
        return json.decodeFromString(AnalysisResult.serializer(), file.readText())
    }

    /** Names of saved snapshots for this repo, newest first. */
    fun list(repo: File, baseDir: File = defaultBaseDir): List<String> =
        (dirFor(repo, baseDir).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { it.nameWithoutExtension }

    fun encode(result: AnalysisResult): String = json.encodeToString(AnalysisResult.serializer(), result)
    fun encode(finding: Finding): String = json.encodeToString(Finding.serializer(), finding)
}
