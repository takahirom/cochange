package io.github.takahirom.cochange

import kotlin.test.Test
import kotlin.test.assertEquals
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
