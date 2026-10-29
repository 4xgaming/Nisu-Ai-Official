package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelVoiceParityTest {
    private val m31 = "gemini-3.1-flash-live-preview"
    private val m38 = "gemini-3.8-live"
    private val m38t = "gemini-3.8-live-extended-thinking"

    // v3.55 / v3.53: 3.8 reads the 3.1 text PLUS its own voice and real-feelings blocks; 3.1 itself is unchanged.
    @Test fun live38AndCustomModelsReadTheSameTextAnd31ReadsNoExtraBlocks() {
        assertEquals(Persona.modelBoost(m38), Persona.modelBoost("models/$m38"))
        assertEquals(Persona.modelBoost(m38), Persona.modelBoost("some-custom-live-model"))
        assertFalse(Persona.modelBoost(m31).contains("REAL GIRL VOICE"))
        assertFalse(Persona.modelBoost(m31).contains("REAL FEELINGS, NOT ACTED"))
    }

    @Test fun live38GetsTheRealFeelingsBlockInBothBrains() {
        for (m in listOf(m38, m38t)) {
            assertTrue(m, Persona.modelBoost(m).contains("REAL FEELINGS, NOT ACTED"))
            assertTrue(m, Persona.leanBoost(m).contains("REAL FEELINGS, NOT ACTED"))
            assertTrue(m, Persona.leanBoost(m).contains("A FEELING NEEDS A REASON"))
        }
        assertFalse(Persona.leanBoost(m31).contains("REAL FEELINGS, NOT ACTED"))
    }

    @Test fun live38GetsTheToneForEveryMoodAndTheMatchHisToneRuleInBothBrains() {
        for (m in listOf(m38, m38t)) {
            for (t in listOf(Persona.modelBoost(m), Persona.leanBoost(m))) {
                assertTrue(m, t.contains("MATCH HIS TONE AND TOPIC"))
                assertTrue(m, t.contains("TONE FOR EVERY MOOD"))
            }
        }
        assertFalse(Persona.modelBoost(m31).contains("TONE FOR EVERY MOOD"))
        assertFalse(Persona.leanBoost(m31).contains("MATCH HIS TONE AND TOPIC"))
    }

    @Test fun live38GetsThePhoneControlDisciplineInBothBrainsAnd31DoesNot() {
        for (m in listOf(m38, m38t)) {
            assertTrue(m, Persona.modelBoost(m).contains("PHONE CONTROL DISCIPLINE"))
            assertTrue(m, Persona.leanBoost(m).contains("ONLY ON A CLEAR COMMAND"))
        }
        assertFalse(Persona.modelBoost(m31).contains("PHONE CONTROL DISCIPLINE"))
        assertFalse(Persona.leanBoost(m31).contains("PHONE CONTROL DISCIPLINE"))
    }

    @Test fun everyEmotionTheAppCanShowHasAToneLine() {
        val all = "happy excited calm sad love surprised angry jealous sulky playful worried shy sleepy proud curious bored nervous disappointed grateful lonely confused scared annoyed flirty guilty relieved hopeful mischievous overwhelmed nostalgic heartbroken".split(" ")
        val t = Persona.leanBoost(m38)
        for (e in all) {
            val ok = if (e == "love") t.contains("romantic or love:") else t.contains("$e:")
            assertTrue(e, ok)
        }
    }

    @Test fun everyModelGetsTheEmotionInTheVoiceRules() {
        for (m in listOf(m31, m38, m38t)) {
            val t = Persona.modelBoost(m)
            assertTrue(m, t.contains("VOICE REALISM"))
            assertTrue(m, t.contains("EMOTION IS IN THE VOICE"))
            assertTrue(m, t.contains("FINAL CHECK before every sentence"))
            assertTrue(m, t.contains("LANGUAGE LOCK"))
        }
    }

    @Test fun thinkingModelKeepsItsExtraThinkingLine() {
        assertTrue(Persona.modelBoost(m38t).contains("extra time to think"))
        assertFalse(Persona.modelBoost(m38).contains("extra time to think"))
    }

    @Test fun compactBrainGets38TheFullVoiceRealismAnd31KeepsItsOld() {
        assertFalse(Persona.leanBoost(m31).contains("VOICE REALISM"))
        for (m in listOf(m38, m38t)) {
            val t = Persona.leanBoost(m)
            assertTrue(t.contains("VOICE REALISM"))
            assertTrue(t.contains("EMOTION IS IN THE VOICE"))
            assertTrue(t.contains("call set_emotion first"))
            assertTrue(t.contains("FINAL CHECK before every sentence"))
        }
        assertTrue(Persona.leanBoost(m38).startsWith(Persona.leanBoost(m31)))   // same text as 3.1, plus the voice part
    }
}
