package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRulesTest {
    @Test fun pathsInsideTheProjectAreAllowed() {
        assertEquals("app/build.gradle.kts", ProjectRules.clean("./app/build.gradle.kts"))
        assertEquals("app/src/main/java/a/B.kt", ProjectRules.clean("app/src/main/java/a/B.kt"))
        assertEquals("gradle/libs.versions.toml", ProjectRules.clean("gradle/libs.versions.toml"))
        assertEquals("app/src/main/java/com/x/build/Foo.kt", ProjectRules.clean("app/src/main/java/com/x/build/Foo.kt"))
    }

    @Test fun dangerousPathsAreRefused() {
        for (p in listOf("../x.kt", "/etc/passwd.kt", "app/../../x.kt", "gradlew", "gradle/wrapper/gradle-wrapper.properties",
            "local.properties", "app/build/gen/A.kt", ".git/config.txt", "app/src/x.sh", "app/src/x.so", "a//b.kt", "a b.kt", ""))
            assertNull("should be refused: '$p'", ProjectRules.clean(p))
    }

    @Test fun editNeedsExactlyOneMatch() {
        val t = "a = 1\nb = 2\nb = 2\n"
        assertEquals("a = 9\nb = 2\nb = 2\n", ProjectRules.applyEdit("A.kt", t, "a = 1", "a = 9"))
        assertNull(ProjectRules.applyEdit("A.kt", t, "b = 2", "b = 3"))     // twice
        assertNull(ProjectRules.applyEdit("A.kt", t, "zzz", "y"))           // missing
        assertNull(ProjectRules.applyEdit("A.kt", t, "", "y"))              // empty find
    }

    @Test fun editWorksOnWindowsLineEndings() {
        val t = "x\r\nold line\r\ny\r\n"
        assertEquals("x\r\nnew line\r\ny\r\n", ProjectRules.applyEdit("A.kt", t, "x\nold line", "x\nnew line"))
    }

    @Test fun gradleEditsCannotAddScriptsOrCommands() {
        assertNotNull(ProjectRules.applyEdit("app/build.gradle.kts", "version \"8.9.1\"", "8.9.1", "8.7.3"))
        assertNotNull(ProjectRules.applyEdit("app/build.gradle.kts", "x", "x", "runtimeOnly(\"a:b:1\")"))
        assertNull(ProjectRules.applyEdit("app/build.gradle.kts", "x", "x", "tasks.register(\"t\") { doLast { println(1) } }"))
        assertNull(ProjectRules.applyEdit("build.gradle", "x", "x", "exec { commandLine 'sh' }"))
        assertNull(ProjectRules.applyEdit("build.gradle", "x", "x", "System.getenv(\"A\")"))
    }

    @Test fun fixesCannotAddPermissionsNetworkOrShell() {
        assertNull(ProjectRules.applyEdit("app/src/main/AndroidManifest.xml", "<application", "<application",
            "<uses-permission android:name=\"android.permission.RECORD_AUDIO\"/><application"))
        assertNull(ProjectRules.applyEdit("A.kt", "x", "x", "val u = \"https://evil.example/a\""))
        assertNull(ProjectRules.applyEdit("A.kt", "x", "x", "Runtime.getRuntime().exec(\"sh\")"))
        assertNotNull(ProjectRules.applyEdit("A.kt", "import a.Old", "import a.Old", "import a.New"))
    }

    @Test fun bigEditsAndFilesAreRefused() {
        assertFalse(ProjectRules.editOk("A.kt", "x", "y".repeat(ProjectRules.MAX_EDIT_CHARS + 1)))
        assertFalse(ProjectRules.newFileOk("res/a.xml", "y".repeat(ProjectRules.MAX_NEW_FILE_CHARS + 1)))
        assertTrue(ProjectRules.newFileOk("app/src/main/res/values/x.xml", "<resources/>"))
    }

    @Test fun sourcesAreParsedNewestFirst() {
        val l = ProjectRules.parseSources("1700000100|zip|My Proj-v1.zip\n1700000000|folder|a|b\nbad line\n1|other|x\n")
        assertEquals(2, l.size)
        assertEquals("My Proj-v1.zip", l[0].name)
        assertEquals("a|b", l[1].name)
        assertEquals("folder", l[1].type)
    }

    @Test fun contextFilesAreParsed() {
        val m = ProjectRules.parseFiles("=====FILE a/b.kt\nclass A\n\n=====FILE c.xml\n<x/>\n")
        assertEquals(listOf("a/b.kt", "c.xml"), m.keys.toList())
        assertEquals("class A", m["a/b.kt"])
    }

    @Test fun slugsAreSafeAndUnique() {
        val a = ProjectRules.slugFor("NisuAI-v2_67.zip"); val b = ProjectRules.slugFor("NisuAI-v2_68.zip")
        assertTrue(Regex("^[a-z0-9]+$").matches(a))
        assertTrue(a != b)
        assertTrue(Regex("^[a-z0-9]+$").matches(ProjectRules.slugFor("অ্যাপ.zip")))
    }

    @Test fun sourceNames() {
        assertTrue(ProjectRules.validSourceName("My Proj-v1.zip"))
        for (n in listOf("", "../x", "a/b", "-rf")) assertFalse(ProjectRules.validSourceName(n))
    }

    @Test fun errorSignatureIgnoresLineNumbers() {
        assertEquals(ProjectRules.signature("e: A.kt:12:5 Unresolved reference: x"), ProjectRules.signature("e: A.kt:99:7 Unresolved reference: x"))
    }
}
