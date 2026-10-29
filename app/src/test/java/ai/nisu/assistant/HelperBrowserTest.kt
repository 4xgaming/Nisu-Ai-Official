package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.132: the helper's search uses the app's own browser and is fast. */
class HelperBrowserTest {
    private val hits = listOf(
        Triple("Dhaka weather today", "32 C, humid, rain in the evening", "https://www.weather.example.com/dhaka?x=1"),
        Triple("Video", "a video", "https://www.youtube.com/watch?v=1"),
        Triple("Report", "a pdf", "https://site.example.org/report.pdf"),
        Triple("Daily Star", "Temperature 31 C", "https://thedailystar.net/weather")
    )

    @Test fun aPageTaskKeepsItsAddress() {
        val task = HelperRules.taskFor("visit_page", "", "https://example.com/a?b=1")!!
        assertEquals("https://example.com/a?b=1", HelperRules.pageUrlOf(task))
        assertEquals(null, HelperRules.pageUrlOf("bitcoin price today"))
        assertEquals(null, HelperRules.pageUrlOf(HelperRules.PAGE_PREFIX))
    }

    @Test fun onlyTextPagesAreRead() {
        assertTrue(HelperRules.readable("https://www.weather.example.com/dhaka?x=1"))
        assertTrue(HelperRules.readable("https://thedailystar.net/weather"))
        assertFalse(HelperRules.readable("https://www.youtube.com/watch?v=1"))
        assertFalse(HelperRules.readable("https://m.facebook.com/x"))
        assertFalse(HelperRules.readable("https://site.example.org/report.pdf"))
        assertFalse(HelperRules.readable("https://site.example.org/report.PDF?dl=1"))
        assertFalse(HelperRules.readable("not a url"))
        assertTrue(HelperRules.readable("https://notyoutube.com/a"))
    }

    @Test fun hostIsShownWithoutWww() {
        assertEquals("weather.example.com", HelperRules.hostOf("https://www.weather.example.com/dhaka?x=1"))
        assertEquals("thedailystar.net", HelperRules.hostOf("HTTPS://thedailystar.net:8080/a"))
        assertEquals("", HelperRules.hostOf("nothing"))
    }

    @Test fun theModelGetsTheQuestionTheResultsAndThePages() {
        val p = HelperRules.browserPrompt("  weather in Dhaka  ", hits, listOf(Triple("Dhaka weather", "x".repeat(5000), "https://www.weather.example.com/dhaka")))
        assertTrue(p.startsWith("QUESTION: weather in Dhaka"))
        assertTrue(p.contains("[1] Dhaka weather today - 32 C"))
        assertTrue(p.contains("(thedailystar.net)"))
        assertTrue(p.contains("[P1] Dhaka weather (weather.example.com)"))
        assertFalse("no full links go to the model prompt", p.contains("https://"))
        assertTrue("page text is cut", p.length < 3500)
    }

    @Test fun thePromptIsAlwaysSmall() {
        val big = (1..20).map { Triple("t".repeat(500), "s".repeat(900), "https://a$it.example.com/") }
        val pages = (1..5).map { Triple("p", "z".repeat(20000), "https://b$it.example.com/") }
        assertTrue(HelperRules.browserPrompt("q", big, pages).length <= 9000 + 10)
        assertTrue(HelperRules.rawDigest(big, pages).length <= 3000 + 10)
    }

    @Test fun plainDigestKeepsTheFactsWhenTheModelFails() {
        val d = HelperRules.rawDigest(hits, listOf(Triple("Dhaka weather", "Now 32 C, feels like 38 C", "https://www.weather.example.com/dhaka")))
        assertTrue(d.contains("Now 32 C, feels like 38 C"))
        assertTrue(d.contains("- Daily Star: Temperature 31 C (thedailystar.net)"))
        assertEquals("", HelperRules.rawDigest(emptyList(), emptyList()))
    }

    @Test fun theNewTimingsAreFast() {
        assertTrue(HelperRules.GRACE_MS <= 1000L)
        assertTrue(HelperRules.SEARCH_TIMEOUT_MS <= 12_000L)
        assertTrue(HelperRules.PROMPT_TIMEOUT_MS <= 8_000L)
        assertTrue(HelperRules.LIVE_TIMEOUT_MS <= 40_000L)
        assertTrue(HelperRules.PAGES_WAIT_MS <= 5_000L)
        assertTrue(HelperRules.GATE_WAIT_MS <= 10_000L)
        assertTrue("a session being opened (15 s) must not make a job say busy", HelperRules.LOCK_WAIT_MS > 15_000L)
        assertTrue(HelperRules.SESSION_JOBS in 3..10)
        assertEquals("gemini-3.8-live", HelperRules.DEFAULT_MODEL)
    }

    @Test fun theSearchPromptNeverAsksForLinksOrInventing() {
        val s = HelperRules.searchSystem()
        assertTrue(s.contains("ALREADY searched"))
        assertTrue(s.contains("never invent"))
        assertTrue(s.contains("no links"))
        assertTrue(s.contains("120 words"))
    }
}
