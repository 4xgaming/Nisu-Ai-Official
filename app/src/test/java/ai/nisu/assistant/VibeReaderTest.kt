package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeReaderTest {
    @Test fun romanticInEveryLanguage() {
        for (t in listOf("I love you so much", "tumi amar jaan, valobashi", "আমি তোমাকে ভালোবাসি", "मुझे तुमसे प्यार है", "miss you ❤"))
            assertTrue(t, (VibeReader.classify(t)["romantic"] ?: 0f) >= 0.5f)
    }

    @Test fun otherMoods() {
        assertTrue((VibeReader.classify("open whatsapp and send a message")["task"] ?: 0f) >= 0.5f)
        assertTrue((VibeReader.classify("মন খারাপ, একা লাগছে")["sad"] ?: 0f) >= 0.5f)
        assertTrue((VibeReader.classify("haha you are so funny 😂")["playful"] ?: 0f) >= 0.5f)
        assertTrue((VibeReader.classify("kivabe internet kaj kore?")["curious"] ?: 0f) >= 0.5f)
    }

    @Test fun moodBuildsGraduallyAndFadesWhenHeMovesOn() {
        var s: Map<String, Float> = emptyMap()
        val love = VibeReader.classify("I love you, miss you")
        repeat(3) { s = VibeReader.step(s, love) }
        val built = s["romantic"] ?: 0f
        assertTrue("v2.55: one clear message already counts, so she can mirror him at once", (VibeReader.step(emptyMap(), love)["romantic"] ?: 0f) >= 0.25f)
        assertTrue(built > 0.25f)
        val work = VibeReader.classify("open the file and send it")
        repeat(4) { s = VibeReader.step(s, work) }
        assertTrue((s["romantic"] ?: 0f) < built / 2f)
        assertTrue((s["task"] ?: 0f) > (s["romantic"] ?: 0f))
    }

    @Test fun plainTalkShowsNothing() {
        assertEquals(emptyMap<String, Float>(), VibeReader.classify("okay"))
    }
}
