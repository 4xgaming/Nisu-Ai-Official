package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeRulesTest {
    private val song = "Tum Hi Ho - 4 minutes, 22 seconds - Go to channel - T-Series - 1.2B views - 8 years ago - play video"

    @Test fun skipLabelsAreWholeLabelsOnly() {
        assertTrue(YtRules.isSkipLabel("Skip"))
        assertTrue(YtRules.isSkipLabel(" skip ads "))
        assertFalse(YtRules.isSkipLabel("Skip navigation"))
        assertFalse(YtRules.isSkipLabel("Skipper"))
    }

    @Test fun videoResultsAreRecognisedAndShortsAreNot() {
        assertTrue(YtRules.isVideoResult(song))
        assertFalse(YtRules.isVideoResult("Funny cat - 30 seconds - play Short"))
        assertTrue(YtRules.isShortResult("Funny cat - 30 seconds - play Short"))
        assertFalse(YtRules.isVideoResult("Home"))
    }

    @Test fun durationIsReadFromTheLengthPartOnly() {
        assertEquals(262, YtRules.durationSec(song))
        assertEquals(3725, YtRules.durationSec("Mix - 1 hour, 2 minutes, 5 seconds - Channel - play video"))
        assertEquals(45, YtRules.durationSec("Clip - 45 seconds - Channel - play video"))
        assertNull(YtRules.durationSec("Live stream - Channel - 5 hours ago - play video"))
        assertEquals("Tum Hi Ho", YtRules.titleOf(song))
    }

    @Test fun songLengthAcceptsNormalSongsAndUnknown() {
        assertTrue(YtRules.isSongLength(262)); assertTrue(YtRules.isSongLength(null))
        assertFalse(YtRules.isSongLength(30)); assertFalse(YtRules.isSongLength(3725))
    }

    @Test fun trendingQueryAndSearchUrl() {
        assertEquals("Hindi pop trending songs October 2026", YtRules.trendingQuery(" Hindi ", "pop", "October", 2026))
        assertEquals("trending songs October 2026", YtRules.trendingQuery("", "", "October", 2026))
        assertEquals("https://www.youtube.com/results?search_query=tum+hi+ho", YtRules.searchUrl(" tum hi ho "))
    }

    @Test fun pickIndexStaysInsideTheList() {
        assertEquals(0, YtRules.pickIndex("first", 5, 3)); assertEquals(0, YtRules.pickIndex("", 5, 3))
        assertEquals(2, YtRules.pickIndex("3", 5, 0)); assertEquals(4, YtRules.pickIndex("99", 5, 0)); assertEquals(0, YtRules.pickIndex("0", 5, 0))
        assertEquals(3, YtRules.pickIndex("random", 5, 8)); assertEquals(-1, YtRules.pickIndex("first", 0, 1))
    }

    @Test fun adTextOnThePlayerIsNotConfusedWithSponsoredCards() {
        assertTrue(YtRules.isPlayerAd("ad 1 of 2 | "))
        assertTrue(YtRules.isPlayerAd("visit advertiser"))
        assertTrue(YtRules.isSkipCountdown("You can skip in 5"))
        assertFalse(YtRules.isPlayerAd("sponsored | some product card"))
        assertTrue(YtRules.isAdText("sponsored | some product card"))
        assertFalse(YtRules.isPlayerAd("head of the class"))
    }

    @Test fun speedLabelsMatchTheMenu() {
        assertEquals("Normal", YtRules.speedLabel(1.0)); assertEquals("1.5x", YtRules.speedLabel(1.5)); assertEquals("2x", YtRules.speedLabel(2.0))
    }
}
