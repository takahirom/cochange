package io.github.takahirom.cochange

import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Persists the last analysis per repository under ~/.cache/cochange/ so that
 * `findings` / `inspect` work without re-running the analysis.
 */
object Store {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val defaultBaseDir: File
        get() = File(System.getProperty("user.home"), ".cache/cochange")

    private fun dirFor(repo: File, baseDir: File): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(repo.canonicalPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(baseDir, digest)
    }

    fun save(repo: File, result: AnalysisResult, baseDir: File = defaultBaseDir) {
        val dir = dirFor(repo, baseDir)
        dir.mkdirs()
        File(dir, "findings.json").writeText(json.encodeToString(AnalysisResult.serializer(), result))
    }

    fun load(repo: File, baseDir: File = defaultBaseDir): AnalysisResult? {
        val file = File(dirFor(repo, baseDir), "findings.json")
        if (!file.exists()) return null
        return json.decodeFromString(AnalysisResult.serializer(), file.readText())
    }

    fun encode(result: AnalysisResult): String = json.encodeToString(AnalysisResult.serializer(), result)
    fun encode(finding: Finding): String = json.encodeToString(Finding.serializer(), finding)
}
