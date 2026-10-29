package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun newerTagWins() {
        assertTrue(Updater.isNewer("v3.142", "3.141"))
        assertTrue(Updater.isNewer("v3.141", "3.140"))
        assertTrue(Updater.isNewer("v4.0", "3.999"))
        assertTrue(Updater.isNewer("3.140.1", "3.140"))
        assertTrue(Updater.isNewer("v3.1000", "3.999"))   // numbers, not text: 1000 > 999
    }
    @Test fun sameOrOlderIsNotAnUpdate() {
        assertFalse(Updater.isNewer("v3.141", "3.141"))
        assertFalse(Updater.isNewer("v3.140", "3.141"))
        assertFalse(Updater.isNewer("v3.14", "3.141"))
        assertFalse(Updater.isNewer("", "3.141"))
    }
    @Test fun parsing() { assertEquals(listOf(3, 142), Updater.parts("v3.142")) }
}
