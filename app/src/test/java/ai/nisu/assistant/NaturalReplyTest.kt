package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalReplyTest {
    @Test fun romanceLevelInEveryLanguage() {
        assertEquals(3, NaturalReply.romanceLevel("আমি তোমাকে ভালোবাসি"))
        assertEquals(3, NaturalReply.romanceLevel("tumake valobashi"))
        assertEquals(3, NaturalReply.romanceLevel("I love you"))
        assertEquals(2, NaturalReply.romanceLevel("miss you"))
        assertEquals(2, NaturalReply.romanceLevel("tumi amar jaan"))
        assertEquals(1, NaturalReply.romanceLevel("tomar kotha mone porchilo"))
        assertEquals(0, NaturalReply.romanceLevel("open whatsapp and send a message"))
        assertEquals(0, NaturalReply.romanceLevel("aj ki khabo?"))
    }

    @Test fun wordsKeepBengaliVowelSigns() {
        assertEquals(listOf("ভালোবাসি", "তুমি"), NaturalReply.words("ভালোবাসি, তুমি!"))
    }

    @Test fun aCopyIsFoundAndAFreshReplyIsNot() {
        val recent = listOf("উফ তুমি না সত্যিই পাগল, আমি তো তোমার কথাই ভাবছিলাম বাবু 🥰", "উফ তুমি না সত্যিই পাগল, খেয়েছো কিছু আজকে? 😊")
        assertTrue(NaturalReply.tooSimilar("উফ তুমি না সত্যিই পাগল, আমি তো তোমার কথাই ভাবছিলাম বাবু 🥰", recent))
        assertTrue("same first five words", NaturalReply.tooSimilar("উফ তুমি না সত্যিই পাগল, আজ কী করলে সারাদিন?", recent))
        assertFalse(NaturalReply.tooSimilar("সারাদিন পর তোমার মেসেজ দেখে ভালো লাগলো, কেমন গেল দিনটা?", recent))
    }

    @Test fun veryShortRepliesAreNeverACopy() {
        assertFalse(NaturalReply.tooSimilar("হুম", listOf("হুম")))
        assertFalse(NaturalReply.tooSimilar("আচ্ছা ঠিক আছে", listOf("আচ্ছা ঠিক আছে")))
    }

    @Test fun noteNamesWhatSheKeepsRepeating() {
        val recent = listOf("বাবু, খেয়েছো কিছু? 🥰", "বাবু, ঘুমাবে না আজ? ভালোবাসি 🥰", "বাবু, আজ কী করলে? ভালোবাসি 😘")
        val n = NaturalReply.note("hmm", recent, PersonaMode.CARE)
        assertTrue(n.contains("DO NOT REPEAT YOURSELF"))
        assertTrue(n.contains("pet name"))
        assertTrue(n.contains("love line"))
    }

    @Test fun noteIsEmptyInBossModeAndWhenThereIsNothingToSay() {
        assertEquals("", NaturalReply.note("ভালোবাসি", listOf("বাবু, খেয়েছো?"), PersonaMode.BOSS))
        assertEquals("", NaturalReply.note("ok", emptyList(), PersonaMode.CARE))
    }

    @Test fun romanceNoteOnlyInCareModeAndScalesWithHisLevel() {
        assertEquals("", NaturalReply.romanceNote("I love you", emptyList(), PersonaMode.FRIEND))
        assertEquals("", NaturalReply.romanceNote("kemon acho", emptyList(), PersonaMode.CARE))
        assertTrue(NaturalReply.romanceNote("I love you", emptyList(), PersonaMode.CARE).contains("level 3"))
        assertTrue(NaturalReply.romanceNote("miss you", emptyList(), PersonaMode.CARE).contains("level 2"))
        assertTrue(NaturalReply.romanceNote("miss you", emptyList(), PersonaMode.CARE).contains("a line or two"))
    }

    @Test fun romanceComesAfterComfortWhenHeIsAlsoHurting() {
        val n = NaturalReply.romanceNote("মন খারাপ, মিস করছি তোমাকে", emptyList(), PersonaMode.CARE)
        assertTrue(n.contains("comfort first"))
    }

    @Test fun tidyOnlyCalmsWhatLooksStaged() {
        assertEquals("সত্যি! 😍😍", NaturalReply.tidy("সত্যি!!!!! 😍😍😍😍"))
        assertEquals("আচ্ছা...", NaturalReply.tidy("আচ্ছা........"))
        assertEquals("ঠিক আছে!! 🙂", NaturalReply.tidy("ঠিক আছে!! 🙂"))
    }

    @Test fun moreKindsOfRomanticTalkAreSeenButTasksAreNot() {
        assertEquals(3, NaturalReply.romanceLevel("marry me"))
        assertEquals(2, NaturalReply.romanceLevel("tumi amar"))
        assertEquals(1, NaturalReply.romanceLevel("I dream about you"))
        assertEquals(0, NaturalReply.romanceLevel("I need you to open youtube"))
        assertEquals(0, NaturalReply.romanceLevel("execute the script"))
    }

    @Test fun herAnswerIsOneStepMoreRomanticThanHis() {
        for (m in listOf("I love you", "miss you", "tomar kotha mone porchilo"))
            assertTrue(NaturalReply.romanceNote(m, emptyList(), PersonaMode.CARE).contains("more", ignoreCase = true))
        assertTrue(NaturalReply.romanceNote("miss you", emptyList(), PersonaMode.CARE).contains("MORE romantic"))
        assertFalse(NaturalReply.romanceNote("miss you", emptyList(), PersonaMode.CARE).contains("same size"))
        assertEquals("", NaturalReply.romanceNote("miss you", emptyList(), PersonaMode.BOSS))
        assertEquals("", NaturalReply.romanceNote("miss you", emptyList(), PersonaMode.JARVIS))
        assertEquals("", NaturalReply.romanceNote("miss you", emptyList(), PersonaMode.VENOM))
    }
}
