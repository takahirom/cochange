package io.github.takahirom.cochange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FileRoleTest {
    @Test
    fun `classifies tests by directory and by name, without false positives`() {
        assertEquals(FileRole.TEST, FileRole.of("app/src/test/kotlin/Foo.kt"))
        assertEquals(FileRole.TEST, FileRole.of("shared/src/commonTest/kotlin/Foo.kt"))
        assertEquals(FileRole.TEST, FileRole.of("app/HomeScreenTest.kt"))
        assertEquals(FileRole.TEST, FileRole.of("web/home.test.ts"))
        assertEquals(FileRole.TEST, FileRole.of("api/user_test.go"))
        assertEquals(FileRole.TEST, FileRole.of("py/test_user.py"))
        // Not tests: `latest.kt` merely ends in "test", `Contest.kt` is production.
        assertEquals(FileRole.SOURCE, FileRole.of("app/latest.kt"))
        assertEquals(FileRole.SOURCE, FileRole.of("app/HomeScreen.kt"))
    }

    @Test
    fun `classifies lockfiles, resources, and generated`() {
        assertEquals(FileRole.LOCKFILE, FileRole.of("package-lock.json"))
        assertEquals(FileRole.LOCKFILE, FileRole.of("ios/Podfile.lock"))
        assertEquals(FileRole.RESOURCE, FileRole.of("feature/home/values/strings.xml"))
        assertEquals(FileRole.RESOURCE, FileRole.of("app.xcodeproj/project.pbxproj"))
        assertEquals(FileRole.GENERATED, FileRole.of("mocks/zzz.mockolo.swift"))
        assertEquals(FileRole.SOURCE, FileRole.of("app/PaymentScreen.kt"))
    }

    @Test
    fun `rejects unknown excludable roles`() {
        assertFailsWith<IllegalArgumentException> { FileRole.validateExcludable("bogus") }
        assertFailsWith<IllegalArgumentException> { FileRole.validateExcludable("source") }
        assertEquals("test", FileRole.validateExcludable("test"))
    }
}

/**
 * Role detection had two blind spots. Every directory pattern required a leading slash,
 * so a top-level `test/` or `assets/` was missed; and `--exclude-role generated` ignored
 * the repository's own `linguist-generated` declarations, which `FileCategory` honours.
 */
class FileRoleBlindSpotsTest {
    @Test
    fun `a top-level role directory is recognised`() {
        assertEquals(FileRole.TEST, FileRole.of("test/helpers.kt"))
        assertEquals(FileRole.TEST, FileRole.of("tests/conftest.py"))
        assertEquals(FileRole.RESOURCE, FileRole.of("assets/logo.bin"))
        assertEquals(FileRole.RESOURCE, FileRole.of("resources/messages.properties"))
        // ...and an ordinary path is still source.
        assertEquals(FileRole.SOURCE, FileRole.of("src/latest.kt"))
    }

    @Test
    fun `a repo-declared generated file takes the generated role`() {
        // Nothing in the name says generated; .gitattributes does.
        assertEquals(FileRole.SOURCE, FileRole.of("src/Api.kt"))
        assertEquals(FileRole.GENERATED, FileRole.of("src/Api.kt", setOf("src/Api.kt")))
    }

    @Test
    fun `--exclude-role generated hides a repo-declared generated file`() {
        val head = setOf("src/Api.kt", "src/App.kt")
        val context = AnalysisContext(
            emptyList(), Boundaries(head), head,
            generated = setOf("src/Api.kt"),
            excludedRoles = setOf(FileRole.GENERATED),
        )
        assertTrue(!context.isVisible("src/Api.kt"), "the repo declared it generated")
        assertTrue(context.isVisible("src/App.kt"))
    }
}

/**
 * The stem suffixes are matched case-sensitively, which is what keeps ordinary words apart
 * from test names: `Contest` ends in lowercase `test`, not `Test`. A reviewer flagged this
 * as a false positive; it is not, and an attempt to "fix" it broke real test detection —
 * hence this test, so the case-sensitivity is not softened by accident later.
 */
class TestSuffixCaseSensitivityTest {
    @Test
    fun `an ordinary word ending in lowercase test is not a test file`() {
        for (name in listOf("Contest.kt", "Protest.kt", "Latest.kt", "Manifest.kt", "Greatest.kt")) {
            assertEquals(FileRole.SOURCE, FileRole.of("app/$name"), name)
        }
    }

    @Test
    fun `real test names are recognised`() {
        for (name in listOf("FooTest.kt", "FooTests.kt", "FooSpec.kt", "Test.kt", "foo_test.go", "test_foo.py")) {
            assertEquals(FileRole.TEST, FileRole.of("app/$name"), name)
        }
    }
}
