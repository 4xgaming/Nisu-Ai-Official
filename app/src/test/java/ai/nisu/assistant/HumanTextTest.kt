package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanTextTest {
    @Test fun markdownIsRemovedButTheWordsStay() {
        val out = HumanText.polish("**Nice** idea! Try *this* first.")
        assertFalse(out.contains("*"))
        assertTrue(out.contains("Nice idea"))
    }

    @Test fun aClosingOfferOfHelpIsDropped() {
        val out = HumanText.polish("That sounds like a great plan, go for it. Let me know if you need anything else!")
        assertEquals("That sounds like a great plan, go for it.", out)
    }

    @Test fun aSingleSentenceIsNeverCut() {
        val s = "Feel free to ask me anything."
        assertEquals(s, HumanText.polish(s))
    }

    @Test fun roboticOpenerIsDroppedButNormalOpenersStay() {
        assertEquals("I can explain that to you now.", HumanText.polish("Certainly! I can explain that to you now."))
        assertEquals("Sure, let's do it.", HumanText.polish("Sure, let's do it."))
    }

    @Test fun bengaliAndHindiTextIsLeftAsItIs() {
        val bn = "আরে বাহ! কী খবর, কেমন আছো?"
        assertEquals(bn, HumanText.polish(bn))
    }

    @Test fun spelledOutLaughIsRemoved() {
        assertFalse(HumanText.polish("That is funny hehe really").contains("hehe"))
    }
}
