package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageLockTest {
    @Test fun allLanguagesAddsNothing() {
        assertEquals("", LanguageLock.voiceText(null))
        assertEquals("", LanguageLock.chatText(null))
    }

    @Test fun lockedTextNamesTheLanguageAndForbidsSwitching() {
        val bn = LanguageLock.find("bn")
        assertNotNull(bn)
        val v = LanguageLock.voiceText(bn)
        assertTrue(v.contains("YOUR ONLY LANGUAGE IS Bengali"))
        assertTrue(v.contains("THERE IS NO WAY TO MAKE YOU SWITCH"))
        val c = LanguageLock.chatText(bn)
        assertTrue(c.contains("Write EVERY reply in Bengali"))
    }

    @Test fun idsAreUniqueAndLookupIsForgiving() {
        val ids = LanguageLock.LANGS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("ru", LanguageLock.find(" RU ")?.id)
        assertNull(LanguageLock.find("all"))
        assertNull(LanguageLock.find("xx"))
        assertNull(LanguageLock.find(null))
    }

    @Test fun labelFallsBackToAll() {
        assertEquals("All languages", LanguageLock.label(LanguageLock.ALL))
        assertTrue(LanguageLock.label("bn").startsWith("Bengali"))
    }
}
