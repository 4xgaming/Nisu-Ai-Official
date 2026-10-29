package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSetTest {
    @Test fun splitsAPromptAtCapitalHeaders() {
        val rules = RuleSet.splitPrompt("You are NISU AI.\nHis name is Raju.\nLANGUAGE: speak Bengali.\nSAFETY: ask before paying.")
        assertEquals(listOf("Identity", "Language", "Safety"), rules.map { it.title })
        assertTrue(rules[0].text.contains("His name is Raju."))
        assertTrue(rules[2].text.startsWith("SAFETY:"))
    }

    @Test fun blankClipboardIsNotARuleSet() { assertNull(RuleSet.parse("  ")) }
}
