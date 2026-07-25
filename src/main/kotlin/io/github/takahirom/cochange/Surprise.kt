package io.github.takahirom.cochange

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Ranks a co-change pair by *architectural interest* rather than raw strength,
 * so a strong-but-boring coupling (an interface and its impl, a rarely-changed
 * pair that happened to move together a handful of times) sinks below a coupling
 * that is genuinely surprising. Both signals are ecosystem-agnostic: evidence
 * reliability, and whether the names already predicted the coupling. Interest only
 * reorders — it never changes the underlying evidence.
 *
 * There used to be a third input, an "architectural distance" factor. Every caller
 * passed the constant 1.0, so it varied nothing and only made the formula look more
 * informed than it was.
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
     * How far the name penalty can pull a score down. Half, not more: a name is a
     * weaker signal than counted history, so it must not swing the score further
     * than the evidence term itself typically ranges. Even at 1.0 similarity the
     * pair is demoted, never erased.
     */
    const val MAX_NAME_DEMOTION = 0.5

    /**
     * Interest = evidenceStrength × (1 − 0.5 × nameSimilarity) — the order the
     * findings list uses.
     *
     * This is a ranking heuristic (tier: interpretation), and it is NOT a claim that
     * evidence always wins: two files that changed five times and always together
     * have a higher Wilson-bounded similarity than a pair that co-changed 20 of 25
     * times, and a large surprise gap can outrank a modest evidence gap in either
     * direction. Both inputs are published on every finding (`evidenceStrength`,
     * `nameSimilarity`) precisely so a consumer can re-rank instead of trusting this.
     */
    fun interest(pair: AnalysisContext.PairStat): Double {
        val e = evidenceStrength(pair.together, pair.countA, pair.countB)
        return e * (1 - MAX_NAME_DEMOTION * nameSimilarity(pair.a, pair.b))
    }
}
