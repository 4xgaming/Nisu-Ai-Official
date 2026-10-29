package ai.nisu.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRulesTest {
    @Test fun safePathAcceptsNormalFiles() {
        assertEquals("index.html", WebRules.safePath("index.html"))
        assertEquals("css/style.css", WebRules.safePath("./css/style.css"))
        assertEquals("js/app.js", WebRules.safePath("/js/app.js"))
    }

    @Test fun safePathRejectsEscapesAndBadTypes() {
        assertNull(WebRules.safePath("../secret.html"))
        assertNull(WebRules.safePath("a/../../b.js"))
        assertNull(WebRules.safePath(".env"))
        assertNull(WebRules.safePath("run.sh"))
        assertNull(WebRules.safePath("app.apk"))
        assertNull(WebRules.safePath(""))
    }

    @Test fun injectPutsHelperAfterHeadOnce() {
        val out = WebRules.inject("<html><head><title>x</title></head><body></body></html>", "demo")
        assertTrue(out.indexOf("data-nisu") > out.indexOf("<head>"))
        assertTrue(out.indexOf("data-nisu") < out.indexOf("<title>"))
        assertEquals(out, WebRules.inject(out, "demo"))
    }

    @Test fun filesOfKeepsOnlySafeFiles() {
        val o = JSONObject().put("files", JSONArray()
            .put(JSONObject().put("path", "index.html").put("content", "<h1>Hi</h1>"))
            .put(JSONObject().put("path", "../x.js").put("content", "bad")))
        val files = WebRules.filesOf(o)
        assertEquals(1, files.size)
        assertEquals("index.html", files[0].first)
    }

    @Test fun slugIsClean() {
        assertEquals("my-shop", WebRules.slugOf("My Shop!"))
        assertEquals("", WebRules.cleanSlug("../.."))
    }

    @Test fun exactEditChangesOnlyThePartAndEmptyReplaceRemoves() {
        val html = "<body>\n<h1>Hi</h1>\n<section id=\"a\">\n  <p>one</p>\n</section>\n<p>end</p>\n</body>"
        val r = WebRules.applyEdit(html, "<section id=\"a\">\n  <p>one</p>\n</section>\n", "", false)
        assertEquals("<body>\n<h1>Hi</h1>\n<p>end</p>\n</body>", r.text)
        assertTrue(WebRules.applyEdit(html, "<h1>Hi</h1>", "<h1>Hello</h1>", false).text!!.contains("<h1>Hello</h1>\n<section"))
    }

    @Test fun editThatIsMissingOrAmbiguousChangesNothing() {
        val t = "<p>x</p><p>x</p>"
        assertNull(WebRules.applyEdit(t, "<p>x</p>", "", false).text)
        assertTrue(WebRules.applyEdit(t, "<p>x</p>", "", false).error.contains("2 places"))
        assertEquals("", WebRules.applyEdit(t, "<p>x</p>", "", true).text)
        assertNull(WebRules.applyEdit(t, "<div>", "", false).text)
        assertNull(WebRules.applyEdit(t, "", "y", false).text)
    }

    @Test fun editMatchesWhenOnlyLineEndingsOrEdgeSpacesDiffer() {
        val file = "<ul>\r\n    <li>a</li>\r\n    <li>b</li>\r\n</ul>"
        val r = WebRules.applyEdit(file, "    <li>b</li>\n", "", false)
        assertEquals("<ul>\r\n    <li>a</li>\r\n    \r\n</ul>", r.text)
    }

    @Test fun editsAreReadSafely() {
        val o = org.json.JSONObject("{\"edits\":[{\"path\":\"index.html\",\"find\":\"a\",\"replace\":\"\"},{\"path\":\"../x.html\",\"find\":\"a\"},{\"path\":\"s.css\",\"find\":\"\"}]}")
        val e = WebRules.editsOf(o)
        assertEquals(1, e.size)
        assertEquals("index.html", e[0].path)
        assertEquals("", e[0].replace)
    }

    @Test fun codeWriterStripsAFence() {
        assertEquals("<p>x</p>", CodeWriter.cleanCode("```html\n<p>x</p>\n```"))
        assertEquals("<p>x</p>", CodeWriter.cleanCode("  <p>x</p> "))
    }
}
