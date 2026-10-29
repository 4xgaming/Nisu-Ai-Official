package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppKnowledgeTest {
    @Test fun namesInAnyLanguageFindTheApp() {
        assertEquals("whatsapp", AppKnowledge.resolve("WhatsApp")?.key)
        assertEquals("whatsapp", AppKnowledge.resolve("whats app")?.key)
        assertEquals("whatsapp", AppKnowledge.resolve("wa")?.key)
        assertEquals("youtube", AppKnowledge.resolve("YT")?.key)
        assertEquals("youtube", AppKnowledge.resolve("\u0987\u0989\u099F\u09BF\u0989\u09AC")?.key)
        assertEquals("chatgpt", AppKnowledge.resolve("chat gpt")?.key)
        assertEquals("claude", AppKnowledge.resolve("Claude AI")?.key)
        assertEquals("gemini", AppKnowledge.resolve("Gemini")?.key)
        assertEquals("playstore", AppKnowledge.resolve("play store")?.key)
        assertEquals("spotify", AppKnowledge.resolve("com.spotify.music")?.key)
        assertNull(AppKnowledge.resolve("   "))
        assertNull(AppKnowledge.resolve("some unknown app 123"))
    }

    @Test fun exactMatchNeverGuesses() {
        assertNull(AppKnowledge.resolveExact("Phone Cleaner"))
        assertEquals("phone", AppKnowledge.resolveExact("Phone")?.key)
        assertEquals("ytmusic", AppKnowledge.resolveExact("youtube music")?.key)
    }

    @Test fun deepLinksAreEncodedAndComplete() {
        val yt = AppKnowledge.resolve("youtube music")!!
        assertEquals("https://music.youtube.com/search?q=arijit%20singh%20tum%20hi%20ho", AppKnowledge.link(yt, "play", "arijit singh tum hi ho"))
        val sp = AppKnowledge.resolve("spotify")!!
        assertEquals("spotify:search:believer%20imagine%20dragons", AppKnowledge.link(sp, "play", " believer imagine dragons "))
        val maps = AppKnowledge.resolve("maps")!!
        assertEquals("google.navigation:q=Dhaka%20Airport", AppKnowledge.link(maps, "navigate", "Dhaka Airport"))
        val wa = AppKnowledge.resolve("whatsapp")!!
        assertEquals("https://wa.me/8801700000000", AppKnowledge.link(wa, "chat", "8801700000000"))
        assertNull(AppKnowledge.link(wa, "navigate", "x"))
        assertTrue(AppKnowledge.needsText(wa, "chat"))
        assertFalse(AppKnowledge.needsText(wa, "guide"))
    }

    @Test fun everyEntryIsUsable() {
        val keys = HashSet<String>()
        for (a in AppKnowledge.APPS) {
            assertTrue("duplicate key " + a.key, keys.add(a.key))
            assertTrue(a.label.isNotBlank())
            assertTrue("no text for " + a.key, a.how.isNotBlank())
            assertTrue("app " + a.key + " needs a package or a web link", a.packages.isNotEmpty() || a.links.isNotEmpty())
            for (p in a.packages) assertTrue("bad package " + p, Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(p))
            for ((act, l) in a.links) assertTrue("bad link " + a.key + " " + act, l.contains("://") || l.contains(":"))
            assertNotNull(AppKnowledge.resolveExact(a.label))
        }
        assertTrue(AppKnowledge.APPS.size >= 100)
    }

    @Test fun guideCarriesTheGeneralRules() {
        val g = AppKnowledge.guide(AppKnowledge.resolve("chatgpt")!!)
        assertTrue(g.contains("type_text"))
        assertTrue(g.contains("Not now"))
    }
}
