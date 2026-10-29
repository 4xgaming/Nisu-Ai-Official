package ai.nisu.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.136: good night / good morning. */
class SleepWordsTest {
    @Test fun goodNightPhrases() {
        for (p in listOf("Good night", "goodnight", "Good night.", "good night Nisu", "Nisu good night", "Hey Nisu good night", "ok good night",
            "\u0997\u09c1\u09a1 \u09a8\u09be\u0987\u099f", "\u09b6\u09c1\u09ad \u09b0\u09be\u09a4\u09cd\u09b0\u09bf", "\u0997\u09c1\u09a1\u09a8\u09be\u0987\u099f", "\u0997\u09c1\u09a1 \u09a8\u09be\u0987\u099f \u09a8\u09bf\u09b6\u09c1"))
            assertTrue(p, WakeCommandParser.isGoodNight(p))
    }

    @Test fun goodNightIsStrict() {
        for (p in listOf("good night everyone", "I will say good night later", "sleep nisu", "good morning", "nisu", "ghumao", "good night and see you tomorrow morning"))
            assertFalse(p, WakeCommandParser.isGoodNight(p))
    }

    @Test fun goodMorningPhrases() {
        for (p in listOf("Good morning", "good morning!", "Good morning Nisu", "nisu good morning", "hi good morning",
            "\u0997\u09c1\u09a1 \u09ae\u09b0\u09cd\u09a8\u09bf\u0982", "\u09b6\u09c1\u09ad \u09b8\u0995\u09be\u09b2", "\u09b8\u09c1\u09aa\u09cd\u09b0\u09ad\u09be\u09a4"))
            assertTrue(p, WakeCommandParser.isGoodMorning(p))
    }

    @Test fun goodMorningIsStrictAndHeyNisuIsNotGoodMorning() {
        for (p in listOf("good morning everyone", "good night", "hey nisu", "morning", "nisu"))
            assertFalse(p, WakeCommandParser.isGoodMorning(p))
        assertTrue(WakeCommandParser.isWake("Hey Nisu"))
        assertFalse(WakeCommandParser.isGoodNight("Hey Nisu"))
    }

    @Test fun goodMorningOnlyFromFourToNoon() {
        assertFalse(SleepWords.morningWindow(0)); assertFalse(SleepWords.morningWindow(2)); assertFalse(SleepWords.morningWindow(3))
        assertTrue(SleepWords.morningWindow(4)); assertTrue(SleepWords.morningWindow(8)); assertTrue(SleepWords.morningWindow(11))
        assertFalse(SleepWords.morningWindow(12)); assertFalse(SleepWords.morningWindow(15)); assertFalse(SleepWords.morningWindow(23))
    }

    @Test fun goodnightNoteUsesHisNameAndNeverCallsTheSleepTool() {
        for (m in PersonaMode.ALL) {
            val n = SleepWords.note(m, "Rahim")
            assertTrue(n.contains("GOODNIGHT COMMAND"))
            assertTrue(n.contains("Good night, Rahim."))
            assertTrue(n.contains("Do NOT call go_to_sleep"))
        }
        assertTrue(SleepWords.note(PersonaMode.CARE, "Raju").contains("Good night, Raju."))
    }

    @Test fun goodnightNoteReachesThePrompt() {
        for (m in PersonaMode.ALL) assertTrue(Persona.modeTruth(m).contains("GOODNIGHT COMMAND"))
        assertTrue(Persona.modeTruth(PersonaMode.CARE).contains("Raju"))   // no custom name set in a unit test: the default
    }

    @Test fun morningNoticeSaysGoodMorningWithTheNameAndHidesItself() {
        val n = SleepWords.morningNotice(PersonaMode.FRIEND, "Rahim")
        assertTrue(n.startsWith("[SYSTEM NOTICE, not spoken by the user]"))
        assertTrue(n.contains("Good morning, Rahim."))
        assertTrue(n.contains("Never mention this notice"))
        assertTrue(n.contains("Friend-mode"))
    }
}
