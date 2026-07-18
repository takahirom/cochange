package io.github.takahirom.cochange

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GuideCommandTest {
    @Test
    fun `no topic prints the index with every topic`() {
        val result = GuideCommand().test("")
        assertEquals(0, result.statusCode)
        for (topic in listOf("explore", "align-boundaries", "reduce-change-tax", "split-god-class", "extract-module", "reading", "change-unit", "small-repo")) {
            assertTrue(result.stdout.contains(topic), "index should mention $topic")
        }
    }

    @Test
    fun `each topic renders its playbook`() {
        for (topic in listOf("explore", "align-boundaries", "reduce-change-tax", "split-god-class", "extract-module", "reading", "change-unit", "small-repo")) {
            val result = GuideCommand().test(topic)
            assertEquals(0, result.statusCode, "topic $topic should succeed")
            assertTrue(result.stdout.contains("== guide: $topic =="), "topic $topic should print its header")
        }
    }

    @Test
    fun `unknown topic fails with the valid topic list`() {
        val result = GuideCommand().test("nope")
        assertTrue(result.statusCode != 0)
        assertTrue(result.stderr.contains("explore"))
    }
}
