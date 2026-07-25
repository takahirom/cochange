package io.github.takahirom.cochange

import kotlin.test.Test
import kotlin.test.assertFalse
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

    /**
     * A layered vertical slice — Screen / UseCase / Repository for one feature —
     * shares the feature token, so name similarity demotes it. That must stay a
     * mild demotion: "this feature's layers always move together, so introduce a
     * mechanism" is exactly the kind of finding worth reporting, unlike an
     * interface and its single implementation.
     */
    @Test
    fun `a layered vertical slice is only mildly demoted, not treated as a companion pair`() {
        val slice = Surprise.nameSimilarity("feature/payment/PaymentScreen.kt", "domain/PaymentUseCase.kt")
        val companion = Surprise.nameSimilarity("domain/PaymentRepository.kt", "data/DefaultPaymentRepository.kt")
        assertTrue(slice < 0.5, "one shared token out of three must not read as an interface/impl pair (was $slice)")
        assertTrue(slice < companion, "$slice should be below the companion pair's $companion")
        assertFalse(
            BoundaryMismatchDetector.namesRelated("feature/payment/PaymentScreen.kt", "domain/PaymentUseCase.kt"),
            "a vertical slice must not carry the 'names already predicted this' counter-signal",
        )

        // Same evidence on both: the slice must still outrank the companion pair.
        val slicePair = pair("feature/payment/PaymentScreen.kt", "domain/PaymentUseCase.kt", 15, 20, 18)
        val companionPair = pair("domain/PaymentRepository.kt", "data/DefaultPaymentRepository.kt", 15, 20, 18)
        assertTrue(Surprise.interest(slicePair, 1.0) > Surprise.interest(companionPair, 1.0))
    }

    @Test
    fun `even a fully predictable name pair is demoted, never erased`() {
        // Screen.kt / Screen.swift scores 1.0 similarity — the maximum demotion is
        // x0.25, so overwhelming evidence can still surface it.
        val identical = pair("android/Screen.kt", "ios/Screen.swift", 15, 20, 18)
        assertTrue(Surprise.interest(identical, 1.0) > 0.0)
        val weakUnrelated = pair("app/A.kt", "core/B.kt", 3, 20, 18)
        assertTrue(
            Surprise.interest(identical, 1.0) > Surprise.interest(weakUnrelated, 1.0),
            "strong evidence on a predictable pair must beat weak evidence on a surprising one",
        )
    }

    @Test
    fun `a well-supported coupling outranks a small-sample fluke`() {
        val solid = pair("app/A.kt", "core/B.kt", together = 20, countA = 25, countB = 25)
        val fluke = pair("app/C.kt", "core/D.kt", together = 5, countA = 5, countB = 5) // confidence 1.0 but tiny
        assertTrue(Surprise.evidenceStrength(20, 25, 25) > Surprise.evidenceStrength(5, 5, 5))
        assertTrue(Surprise.interest(solid, 1.0) > Surprise.interest(fluke, 1.0))
    }
}
