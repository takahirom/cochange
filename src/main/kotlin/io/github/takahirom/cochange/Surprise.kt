package io.github.takahirom.cochange

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Ranks a co-change pair by *architectural interest* rather than raw strength,
 * so a strong-but-boring coupling (an interface and its impl, a rarely-changed
 * pair that happened to move together a handful of times) sinks below a coupling
 * that is genuinely surprising. All signals are ecosystem-agnostic: evidence
 * reliability, whether names predict the coupling, and how far apart the files
 * sit. Interest only reorders — it never changes the underlying evidence.
 */
object Surprise {
    /**
     * Continuous name overlap in [0,1]: tokenized on camelCase/separators/digits,
     * Jaccard over the token sets. Foo/DefaultFoo score high; a screen and a
     * pricing rule score zero. Replaces a yes/no "companion" flag with a gradient.
     */
    fun nameSimilarity(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val intersection = ta.intersect(tb).size.toDouble()
        val union = (ta + tb).size.toDouble()
        return intersection / union
    }

    private fun tokens(path: String): Set<String> =
        path.substringAfterLast('/').substringBefore('.')
            .split(Regex("(?<=[a-z0-9])(?=[A-Z])|[ _\\-]+"))
            .map { it.lowercase() }
            .filter { it.length >= 2 }
            .toSet()

    /** Wilson score lower bound of k/n — trusts a ratio less when the sample is small. */
    fun wilsonLower(k: Int, n: Int, z: Double = 1.96): Double {
        if (n <= 0) return 0.0
        val p = k.toDouble() / n
        val z2 = z * z
        val center = p + z2 / (2 * n)
        val margin = z * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n))
        return ((center - margin) / (1 + z2 / n)).coerceIn(0.0, 1.0)
    }

    /** Reliable coupling strength: sample-corrected symmetric similarity scaled by how much evidence there is. */
    fun evidenceStrength(together: Int, countA: Int, countB: Int): Double {
        val union = countA + countB - together
        return ln(1.0 + together) * wilsonLower(together, union)
    }

    /**
     * Interest = evidenceStrength × (0.5 + distance) × (1 − 0.75 × nameSimilarity).
     * [architecturalDistance] is 1.0 across a module boundary, else a 0..1 how-far-apart
     * signal. Floors are deliberate: expected/near pairs are demoted, never erased, so
     * overwhelming evidence can still surface a same-named or co-located coupling.
     */
    fun interest(pair: AnalysisContext.PairStat, architecturalDistance: Double): Double {
        val e = evidenceStrength(pair.together, pair.countA, pair.countB)
        val x = nameSimilarity(pair.a, pair.b)
        return e * (0.5 + architecturalDistance) * (1 - 0.75 * x)
    }
}
