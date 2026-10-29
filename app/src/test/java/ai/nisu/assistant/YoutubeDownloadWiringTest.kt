package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.136: the YouTube download system exists, so every voice setup must be able to reach it (the AI used to say it could not download). */
class YoutubeDownloadWiringTest {
    @Test fun theDefaultFastSetupKeepsTheYoutubeTool() {
        assertTrue("youtube" in Tools.EXTRA_FAST_TOOLS)
    }

    @Test fun theShortPromptsSayDownloadingIsBuiltInAndHowItWorks() {
        val n = Persona.YT_DOWNLOAD_NOTE
        assertTrue(n.contains("never say you cannot download"))
        assertTrue(n.contains("action download"))
        assertTrue(n.contains("mp3 or video"))
        assertTrue(n.contains("y2mate"))
        assertTrue(n.contains("never use download_file"))
        assertFalse(n.contains('$'))   // it is spliced into prompt templates: a dollar sign would start a template
    }

    @Test fun downloaderPagesAreReadyAndHisOwnComesFirst() {
        val ready = YouTube.downloaderSites("", "mp3")
        assertEquals(6, ready.size)
        assertTrue(ready.first().contains("y2mate"))
        assertEquals("https://my.site/", YouTube.downloaderSites("https://my.site/", "video").first())
    }
}
