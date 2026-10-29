package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareNudgesTest {
    private val H = 60 * 60_000L

    @Test fun partsOfTheDay() {
        assertEquals("morning", CareNudges.slotOf(7))
        assertEquals("noon", CareNudges.slotOf(13))
        assertEquals("evening", CareNudges.slotOf(18))
        assertEquals("night", CareNudges.slotOf(21))
        assertNull(CareNudges.slotOf(0)); assertNull(CareNudges.slotOf(3)); assertNull(CareNudges.slotOf(4))
    }

    @Test fun sleepReminderOnlyAtNightAndOncePerHour() {
        val now = 100 * H
        assertFalse(CareNudges.allow(14, now, 0L, emptySet()).sleep)          // daytime: never
        assertFalse(CareNudges.allow(21, now, 0L, emptySet()).sleep)          // before 22:00: never
        assertTrue(CareNudges.allow(22, now, 0L, emptySet()).sleep)           // night, nothing said yet
        assertFalse(CareNudges.allow(23, now, now - 30 * 60_000L, emptySet()).sleep)   // said 30 min ago
        assertTrue(CareNudges.allow(23, now, now - 61 * 60_000L, emptySet()).sleep)    // said 61 min ago: due again
        assertTrue(CareNudges.allow(2, now, now - 2 * H, emptySet()).sleep)
    }

    @Test fun oneFoodQuestionPerPartOfTheDay() {
        assertTrue(CareNudges.allow(8, 0L, 0L, emptySet()).food)
        assertFalse(CareNudges.allow(8, 0L, 0L, setOf("morning")).food)
        assertTrue("noon is a new part of the day", CareNudges.allow(12, 0L, 0L, setOf("morning")).food)
        assertFalse("never in the small hours", CareNudges.allow(2, 0L, 0L, emptySet()).food)
        assertNull(CareNudges.allow(2, 0L, 0L, emptySet()).slot)
    }

    @Test fun reminderIsFoundInEveryLanguage() {
        assertTrue(CareNudges.contains("বাবু, খেয়েছো কিছু আজ?", CareNudges.Kind.FOOD))
        assertTrue(CareNudges.contains("kichu kheyecho?", CareNudges.Kind.FOOD))
        assertTrue(CareNudges.contains("Have you eaten yet?", CareNudges.Kind.FOOD))
        assertTrue(CareNudges.contains("तुमने खाना खाया?", CareNudges.Kind.FOOD))
        assertTrue(CareNudges.contains("এত রাতে জেগে আছো কেন, ঘুমাও এবার", CareNudges.Kind.SLEEP))
        assertTrue(CareNudges.contains("you should go to sleep now", CareNudges.Kind.SLEEP))
        assertFalse(CareNudges.contains("আজকের দিনটা কেমন গেল?", CareNudges.Kind.FOOD))
        assertFalse(CareNudges.contains("a great day", CareNudges.Kind.FOOD))
    }

    @Test fun whenHeAsksHimselfSheMayAnswer() {
        val closed = CareNudges.allow(14, 0L, 0L, setOf("noon"))
        assertEquals(setOf(CareNudges.Kind.FOOD), CareNudges.violations("বাবু, খেয়েছো কিছু?", "কী করছ", closed))
        assertTrue(CareNudges.violations("খেয়েছি, তুমি খেয়েছো?", "aj ki khabo bolo to", closed).isEmpty())
        assertEquals(setOf(CareNudges.Kind.SLEEP), CareNudges.violations("ঘুমাও এবার", "hmm", closed))
        assertTrue(CareNudges.violations("শুভ রাত্রি, ভালো করে ঘুমাও", "good night", closed).isEmpty())
        val open = CareNudges.allow(23, 0L, 0L, emptySet())
        assertTrue(CareNudges.violations("ঘুমাও এবার", "hmm", open).isEmpty())
    }

    @Test fun stripCutsShortReminderSentencesOnly() {
        assertEquals("সারাদিন কী করলে আজ? অনেক কাজ ছিল বুঝি।", CareNudges.strip("সারাদিন কী করলে আজ? অনেক কাজ ছিল বুঝি। খেয়েছো কিছু? 🥰", setOf(CareNudges.Kind.FOOD)))
        assertEquals("হুম, বুঝলাম।", CareNudges.strip("হুম, বুঝলাম। এত রাতে জেগে আছো কেন, ঘুমাও এবার! 😴", setOf(CareNudges.Kind.SLEEP)))
        assertEquals("খেয়েছো কিছু?", CareNudges.strip("খেয়েছো কিছু?", setOf(CareNudges.Kind.FOOD)))   // never empties a reply
        assertEquals("ঠিক আছে", CareNudges.strip("ঠিক আছে", emptySet()))
    }

    @Test fun noteMatchesTheRules() {
        val night = CareNudges.note(CareNudges.allow(23, 0L, 0L, emptySet()))
        assertTrue(night.contains("a sleep reminder is due now"))
        assertTrue(night.contains("night (dinner)"))
        val day = CareNudges.note(CareNudges.allow(14, 0L, 0L, setOf("noon")))
        assertTrue(day.contains("do NOT tell him to sleep"))
        assertTrue(day.contains("do NOT ask about eating again"))
        assertTrue(CareNudges.note(CareNudges.allow(3, 0L, 0L, emptySet())).contains("small hours"))
        assertEquals("", CareNudges.note(CareNudges.allow(23, 0L, 0L, emptySet()), PersonaMode.BOSS))
    }
}
