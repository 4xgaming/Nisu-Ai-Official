package ai.nisu.assistant

import org.junit.Assert.*
import org.junit.Test

class HiddenBrowserTest {
    private val html = """
        <div class="result"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1&amp;rut=abc">Example <b>Title</b></a>
        <a class="result__snippet" href="x">Some &amp; snippet text</a></div>
        <div class="result"><a class="result__a" href="//duckduckgo.com/y.js?ad_provider=bing">Ad</a><a class="result__snippet">ad</a></div>
        <div class="result"><a class="result__a" href="https://second.org/">Second</a><a class="result__snippet">Two</a></div>
    """.trimIndent()

    @Test fun parsesTitlesSnippetsAndRealLinks() {
        val h = HiddenBrowser.parseDuck(html, 5)
        assertEquals(2, h.size)
        assertEquals("Example Title", h[0].title)
        assertEquals("https://example.com/a?x=1", h[0].url)
        assertEquals("Some & snippet text", h[0].snippet)
        assertEquals("https://second.org/", h[1].url)
    }

    @Test fun respectsMax() = assertEquals(1, HiddenBrowser.parseDuck(html, 1).size)

    @Test fun parsesWiki() {
        val h = HiddenBrowser.parseWiki("""{"query":{"search":[{"title":"Dhaka","snippet":"capital <span>of</span> Bangladesh"}]}}""", "en", 3)
        assertEquals("capital of Bangladesh", h[0].snippet)
        assertEquals("https://en.wikipedia.org/wiki/Dhaka", h[0].url)
    }

    @Test fun marker() {
        assertEquals("SEARCH" to "bitcoin price today", HiddenBrowser.markerAction("[[SEARCH: bitcoin price today]]"))
        assertEquals("VISIT" to "https://a.com/x", HiddenBrowser.markerAction("[[visit: https://a.com/x]]"))
        assertNull(HiddenBrowser.markerAction("hello"))
        assertEquals("ok", HiddenBrowser.stripMarker("ok [[SEARCH: x]]"))
        assertTrue(HiddenBrowser.looksLikeMarker("[[SEA"))
        assertFalse(HiddenBrowser.looksLikeMarker("Hello"))
    }

    @Test fun addressRules() {
        assertEquals("https://example.com", HiddenBrowser.normalizeUrl("example.com"))
        assertNull(HiddenBrowser.normalizeUrl("http://localhost:8080/x"))
        assertNull(HiddenBrowser.normalizeUrl("http://192.168.0.1/"))
        assertNull(HiddenBrowser.normalizeUrl("http://127.0.0.1/"))
        assertNull(HiddenBrowser.normalizeUrl("two words.com"))
        assertTrue(HiddenBrowser.privateHost("10.1.2.3"))
        assertTrue(HiddenBrowser.privateHost("172.20.0.1"))
        assertFalse(HiddenBrowser.privateHost("172.32.0.1"))
        assertFalse(HiddenBrowser.privateHost("8.8.8.8"))
    }

    @Test fun pageText() {
        val t = HiddenBrowser.htmlToText("<html><head><title>T</title></head><body><script>var x=1;</script><h1>Price list</h1><p>The phone costs 500 dollars today in the official store.</p><p>Second paragraph &amp; more text about the product here.</p></body></html>")
        assertTrue(t.contains("500 dollars"))
        assertTrue(t.contains("& more"))
        assertFalse(t.contains("var x"))
        assertEquals("T", HiddenBrowser.titleOf("<title>T</title>"))
    }

    @Test fun errorsAreHuman() {
        assertTrue(HiddenBrowser.humanError("no_internet").contains("internet"))
        assertTrue(HiddenBrowser.humanError("http_503").contains("503"))
        assertTrue(HiddenBrowser.isHidden("web_search") && HiddenBrowser.isHidden("visit_page") && !HiddenBrowser.isHidden("open_url"))
    }
}
