package io.github.takahirom.cochange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One descriptor answers all three questions about a path, because when they were answered
 * separately they disagreed — and every such disagreement produced a finding that said
 * something false about the files it named. These cases are the disagreements that actually
 * happened.
 */
class FileFactsTest {
    @Test
    fun `a manifest is a build file to everyone that asks`() {
        // pyproject.toml was a module root to Boundaries and a *config* file to
        // FileCategory, so a manifest paired with its own code read as two languages meeting.
        for (name in FileCategory.moduleRootFileNames) {
            assertEquals(
                FileCategory.BUILD, FileFacts.of("pkg/$name").category,
                "$name marks a module root, so it must be a build file everywhere",
            )
        }
    }

    @Test
    fun `a test file is code, a resource is not`() {
        // Both live in the SOURCE category; only one of them is code.
        val test = FileFacts.of("pkg/cmd/list_test.go")
        assertEquals(FileRole.TEST, test.role)
        assertTrue(test.isCode, "a test is code — calling it 'not code' mislabelled real couplings")

        val resource = FileFacts.of("app/src/main/res/values-ja/strings.xml")
        assertEquals(FileCategory.SOURCE, resource.category, "the SOURCE category is the default bucket")
        assertEquals(FileRole.RESOURCE, resource.role)
        assertFalse(resource.isCode, "a translation is not a parallel implementation")
    }

    @Test
    fun `a path with no language cannot be half of a language boundary`() {
        assertEquals(null, FileFacts.of("LICENSE").language)
        assertEquals(null, FileFacts.of("tools/.gitignore").language)
        assertEquals("jvm", FileFacts.of("a/App.kt").language)
        // Related languages share a family, so foo.c x foo.h is a companion, not a boundary.
        assertEquals(FileFacts.of("a/foo.c").language, FileFacts.of("a/foo.h").language)
        // An unmapped but real extension keeps its own key.
        assertEquals("php", FileFacts.of("legacy/Foo.php").language)
    }

    @Test
    fun `a repo-declared generated file is generated to both the category and the role`() {
        // The role used to ignore .gitattributes, so `--exclude-role generated` missed these.
        val declared = setOf("src/Api.kt")
        val facts = FileFacts.of("src/Api.kt", declared)
        assertEquals(FileCategory.GENERATED, facts.category)
        assertEquals(FileRole.GENERATED, facts.role)
        assertFalse(facts.isCode)
        // Without the declaration it is ordinary code.
        assertTrue(FileFacts.of("src/Api.kt").isCode)
    }

    @Test
    fun `the context answers from one cached descriptor`() {
        val head = setOf("src/Api.kt", "src/App.kt")
        val context = AnalysisContext(emptyList(), Boundaries(head), head, generated = setOf("src/Api.kt"))
        val facts = context.facts("src/Api.kt")
        assertEquals(facts.category, context.categoryOf("src/Api.kt"), "categoryOf must not compute its own answer")
        assertTrue(context.facts("src/Api.kt") === facts, "the descriptor is cached, not recomputed per call")
    }
}
