package io.github.takahirom.cochange

import kotlin.test.Test
import kotlin.test.assertTrue

class SurpriseTest {
    private fun pair(a: String, b: String, together: Int, countA: Int, countB: Int) =
        AnalysisContext.PairStat(a, b, together, countA, countB)

    @Test
    fun `name similarity is graduated, not binary`() {
        assertTrue(Surprise.nameSimilarity("a/PaymentRepository.kt", "b/DefaultPaymentRepository.kt") >= 0.5)
        assertTrue(Surprise.nameSimilarity("a/CheckoutScreen.kt", "b/PricingRules.kt") == 0.0)
        val partial = Surprise.nameSimilarity("a/CheckoutScreen.kt", "b/CheckoutViewModel.kt")
        assertTrue(partial in 0.0..0.6 && partial > 0.0, "shared 'checkout' token gives partial, not full, similarity")
    }

    @Test
    fun `a surprising unrelated coupling outranks an equally strong companion pair`() {
        val surprising = pair("app/CheckoutScreen.kt", "core/PricingRules.kt", together = 20, countA = 22, countB = 25)
        val companion = pair("domain/PaymentRepository.kt", "data/DefaultPaymentRepository.kt", together = 20, countA = 22, countB = 25)
        assertTrue(Surprise.interest(surprising, 1.0) > Surprise.interest(companion, 1.0))
    }

    @Test
    fun `a well-supported coupling outranks a small-sample fluke`() {
        val solid = pair("app/A.kt", "core/B.kt", together = 20, countA = 25, countB = 25)
        val fluke = pair("app/C.kt", "core/D.kt", together = 5, countA = 5, countB = 5) // confidence 1.0 but tiny
        assertTrue(Surprise.evidenceStrength(20, 25, 25) > Surprise.evidenceStrength(5, 5, 5))
        assertTrue(Surprise.interest(solid, 1.0) > Surprise.interest(fluke, 1.0))
    }
}
