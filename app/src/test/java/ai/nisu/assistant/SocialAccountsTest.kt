package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialAccountsTest {
    private fun acc(id: Long, url: String, label: String = "") = SocialAccounts.Account(id, SocialAccounts.platformOf(url) ?: "", label, url)

    @Test fun platformsAreRecognisedByHost() {
        assertEquals("youtube", SocialAccounts.platformOf("https://www.youtube.com/@raju"))
        assertEquals("youtube", SocialAccounts.platformOf("https://youtu.be/abc"))
        assertEquals("facebook", SocialAccounts.platformOf("https://m.facebook.com/raju"))
        assertEquals("instagram", SocialAccounts.platformOf("https://instagram.com/raju"))
        assertEquals("x", SocialAccounts.platformOf("https://x.com/raju"))
        assertEquals("x", SocialAccounts.platformOf("https://twitter.com/raju"))
        assertEquals("tiktok", SocialAccounts.platformOf("https://www.tiktok.com/@raju"))
    }

    @Test fun otherHostsAndLookAlikesAreRefused() {
        assertNull(SocialAccounts.platformOf("https://example.com/youtube.com"))
        assertNull(SocialAccounts.platformOf("https://notyoutube.com/@raju"))
        assertNull(SocialAccounts.platformOf("https://youtube.com.evil.io/@raju"))
        assertNull(SocialAccounts.normalize("https://192.168.0.1/admin"))
    }

    @Test fun normalizeAddsHttpsAndUpgradesHttp() {
        assertEquals("https://www.youtube.com/@raju", SocialAccounts.normalize("www.youtube.com/@raju"))
        assertEquals("https://x.com/raju", SocialAccounts.normalize("http://x.com/raju"))
    }

    @Test fun pickByPlatformSynonymNameOrAll() {
        val l = listOf(acc(1, "https://youtube.com/@raju", "My channel"), acc(2, "https://facebook.com/rajupage", "Page"), acc(3, "https://instagram.com/raju", "Insta"))
        assertEquals(3, SocialAccounts.pick(l, "").size)
        assertEquals(3, SocialAccounts.pick(l, "all").size)
        assertEquals(listOf(1L), SocialAccounts.pick(l, "yt").map { it.id })
        assertEquals(listOf(2L), SocialAccounts.pick(l, "fb").map { it.id })
        assertEquals(listOf(3L), SocialAccounts.pick(l, "insta").map { it.id })
        assertEquals(listOf(1L), SocialAccounts.pick(l, "my channel").map { it.id })
        assertTrue(SocialAccounts.pick(l, "tiktok").isEmpty())
    }

    @Test fun youtubeLivePageIsLive() {
        val html = """<link rel="canonical" href="https://www.youtube.com/watch?v=abc123"><meta property="og:title" content="Big stream &amp; chat"><script>{"isLive":true,"isLiveContent":true}</script> 1,234 watching now"""
        val y = SocialAccounts.parseYoutube(html)
        assertTrue(y.live)
        assertEquals("Big stream & chat", y.title)
        assertEquals("1,234", y.watching)
        assertTrue(y.canonical.contains("watch?v=abc123"))
    }

    @Test fun youtubeChannelWithoutLiveIsNotLive() {
        val html = """<link rel="canonical" href="https://www.youtube.com/@raju"><meta property="og:title" content="Raju"><script>{"subscriberCountText":{"accessibility":{},"simpleText":"12K subscribers"}}</script>"""
        val y = SocialAccounts.parseYoutube(html)
        assertFalse(y.live)
        assertEquals("12K subscribers", y.subs)
    }

    @Test fun aLiveMarkerOnAChannelPageAloneIsNotEnough() {
        val html = """<link rel="canonical" href="https://www.youtube.com/@raju"><script>{"isLive":true}</script>"""
        assertFalse(SocialAccounts.parseYoutube(html).live)
    }

    @Test fun upcomingStreamIsNotLive() {
        val y = SocialAccounts.parseYoutube("""<script>{"isUpcoming":true}</script>""")
        assertFalse(y.live); assertTrue(y.upcoming)
    }

    @Test fun metaReadsBothAttributeOrders() {
        assertEquals("A", SocialAccounts.meta("""<meta property="og:title" content="A">""", "og:title"))
        assertEquals("B", SocialAccounts.meta("""<meta content="B" name="description">""", "description"))
        assertEquals("", SocialAccounts.meta("<html></html>", "og:title"))
    }

    @Test fun liveMarkerNeedsAPositiveStatement() {
        assertTrue(SocialAccounts.liveMarker("""{"is_live":true}"""))
        assertTrue(SocialAccounts.liveMarker("""{"live_status":"LIVE"}"""))
        assertFalse(SocialAccounts.liveMarker("""{"is_live":false}"""))
        assertFalse(SocialAccounts.liveMarker("<html>nothing</html>"))
        assertNotNull(SocialAccounts.TOOL)
    }

    @Test fun countsAndAgesAreRead() {
        assertEquals(1200.0, SocialAccounts.parseCount("1.2K subscribers")!!, 0.01)
        assertEquals(3456.0, SocialAccounts.parseCount("3,456 views")!!, 0.01)
        assertEquals(2_500_000.0, SocialAccounts.parseCount("2.5M")!!, 0.01)
        assertNull(SocialAccounts.parseCount("no number"))
        assertEquals(2, SocialAccounts.agoDays("2 days ago"))
        assertEquals(14, SocialAccounts.agoDays("Streamed 2 weeks ago"))
        assertEquals(0, SocialAccounts.agoDays("5 hours ago"))
        assertNull(SocialAccounts.agoDays("yesterday"))
    }

    @Test fun followerCountsComeFromAMetaDescription() {
        val o = SocialAccounts.descCounts("1.5M Followers, 120 Following, 800 Posts - see photos")
        assertEquals("1.5M", o.optString("followers")); assertEquals("120", o.optString("following")); assertEquals("800", o.optString("posts"))
        assertEquals(0, SocialAccounts.descCounts("nothing here").length())
    }

    @Test fun videoStatsGiveAverageBestAndRhythm() {
        val vs = listOf(SocialAccounts.Vid("A", "1,000 views", "1 day ago", false), SocialAccounts.Vid("B", "3,000 views", "8 days ago", false),
            SocialAccounts.Vid("C", "2,000 views", "15 days ago", false), SocialAccounts.Vid("Live", "50 watching", "Streamed live", true))
        val o = SocialAccounts.videoStats(vs, 20000.0)
        assertEquals(2000L, o.getLong("average_views_recent"))
        assertTrue(o.getString("best_recent").startsWith("B"))
        assertTrue(o.getString("weakest_recent").startsWith("A"))
        assertEquals(7.0, o.getDouble("avg_days_between_uploads"), 0.01)
        assertEquals(10.0, o.getDouble("avg_views_percent_of_subscribers"), 0.01)
    }

    @Test fun oldVideoListLayoutIsParsed() {
        val html = """{"videoRenderer":{"videoId":"x","title":{"runs":[{"text":"My first \\u0026 best"}]},"publishedTimeText":{"simpleText":"3 days ago"},"viewCountText":{"simpleText":"1,234 views"}}}"""
        val v = SocialAccounts.parseVideos(html)
        assertEquals(1, v.size); assertEquals("1,234 views", v[0].views); assertEquals("3 days ago", v[0].published)
    }

    @Test fun channelHeaderReadsSubscribersAndVideos() {
        val h = SocialAccounts.channelHeader("""{"content":"1.2K subscribers"},{"content":"45 videos"}""")
        assertEquals("1.2K subscribers", h.optString("subscribers")); assertEquals("45 videos", h.optString("videos"))
    }
}
