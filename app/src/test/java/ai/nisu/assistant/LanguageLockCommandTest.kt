package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageLockCommandTest {
    private fun lockId(t: String): String? = (LanguageLock.parseCommand(t) as? LanguageLock.Command.Lock)?.lang?.id
    private fun isDefault(t: String) = LanguageLock.parseCommand(t) === LanguageLock.Command.Default

    @Test fun lockCommandsInEnglishLetters() {
        assertEquals("hi", lockId("Hindi language lock koro"))
        assertEquals("bn", lockId("bangla lock koro"))
        assertEquals("bn", lockId("Bengali language lock koro"))
        assertEquals("en", lockId("inglish language lock koro"))
        assertEquals("en", lockId("english language lock koro"))
        assertEquals("ur", lockId("lock urdu"))
        assertEquals("ar", lockId("Arabic language lock koro."))
    }

    @Test fun lockCommandsInOtherScripts() {
        assertEquals("bn", lockId("বাংলা ভাষা লক করো"))
        assertEquals("hi", lockId("হিন্দি ভাষা লক করো"))
        assertEquals("en", lockId("ইংরেজি লক করো"))
        assertEquals("hi", lockId("हिंदी भाषा लॉक करो"))
    }

    @Test fun defaultCommands() {
        assertTrue(isDefault("default language koro"))
        assertTrue(isDefault("Language lock default koro"))
        assertTrue(isDefault("language lock off koro"))
        assertTrue(isDefault("unlock language"))
        assertTrue(isDefault("all language koro"))
        assertTrue(isDefault("ডিফল্ট ভাষা করো"))
    }

    @Test fun otherThingsAreNotCommands() {
        assertNull(LanguageLock.parseCommand("default theme koro"))
        assertNull(LanguageLock.parseCommand("phone lock koro"))
        assertNull(LanguageLock.parseCommand("unlock phone"))
        assertNull(LanguageLock.parseCommand("talk in hindi"))
        assertNull(LanguageLock.parseCommand("hindi gaan chalao"))
        assertNull(LanguageLock.parseCommand("how to do hindi language lock?"))
        assertNull(LanguageLock.parseCommand("hindi screen lock koro"))
        assertNull(LanguageLock.parseCommand("language lock"))
        assertNull(LanguageLock.parseCommand(""))
        assertNull(LanguageLock.parseCommand(null))
        assertNull(LanguageLock.parseCommand("ami ajke office theke ferar pothe hindi movie dekhbo ar tarpor lock korbo"))
    }

    @Test fun resolveIsForgiving() {
        assertEquals("hi", LanguageLock.resolve("Hindi")?.id)
        assertEquals("bn", LanguageLock.resolve(" bangla ")?.id)
        assertEquals("bn", LanguageLock.resolve("bn")?.id)
        assertEquals("pt", LanguageLock.resolve("Portuguese (Brazil)")?.id)
        assertNull(LanguageLock.resolve("klingon"))
        assertNull(LanguageLock.resolve(null))
    }

    @Test fun everyLanguageHasAName() {
        for (l in LanguageLock.LANGS) assertNotNull(l.english, LanguageLock.resolve(l.english))
    }

    @Test fun lockedPromptsKeepTheCommandException() {
        val bn = LanguageLock.find("bn")
        assertTrue(LanguageLock.voiceText(bn).contains(LanguageLock.TOOL))
        assertTrue(LanguageLock.chatText(bn).contains("Language Lock command"))
        assertTrue(Persona.modeTruth(PersonaMode.CARE).contains("LANGUAGE LOCK COMMANDS"))
        assertEquals("", LanguageLock.voiceText(null))
    }
}
