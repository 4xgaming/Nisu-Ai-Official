package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRulesTest {
    @Test fun folderWordsInEnglishAndBengali() {
        assertEquals("Screenshot", SmartRules.albumHint("my screenshots"))
        assertEquals("Screenshot", SmartRules.albumHint("\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8\u09B6\u099F"))
        assertEquals("Camera", SmartRules.albumHint("Camera"))
        assertEquals("Download", SmartRules.albumHint("download folder"))
        assertEquals("WhatsApp", SmartRules.albumHint("whats app"))
        assertNull(SmartRules.albumHint("sunset beach"))
        assertNull(SmartRules.albumHint(""))
    }

    @Test fun latestWords() {
        assertTrue(SmartRules.wantsLatest("the latest photo"))
        assertTrue(SmartRules.wantsLatest("\u09B8\u09B0\u09CD\u09AC\u09B6\u09C7\u09B7 \u099B\u09AC\u09BF"))
        assertFalse(SmartRules.wantsLatest("IMG_2031"))
    }

    @Test fun paidLabelsAreRecognised() {
        assertTrue(SmartRules.looksPaid("Buy"))
        assertTrue(SmartRules.looksPaid("$4.99"))
        assertTrue(SmartRules.looksPaid("\u09F3 120"))
        assertTrue(SmartRules.looksPaid("1.99 USD"))
        assertFalse(SmartRules.looksPaid("Install"))
        assertFalse(SmartRules.looksPaid("Open"))
        assertFalse(SmartRules.looksPaid(""))
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("app.apk", SmartRules.safeFileName("https://x.com/files/app.apk?token=1#a"))
        assertEquals("a_b.pdf", SmartRules.safeFileName("https://x.com/a/b.pdf".replace("/b.pdf", "/a<b.pdf")))
        assertEquals("download", SmartRules.safeFileName("https://x.com/"))
        assertEquals("fallback", SmartRules.safeFileName("", "fallback"))
        assertFalse(SmartRules.safeFileName("https://x.com/../../etc/passwd").contains("/"))
    }

    @Test fun onlyHttpLinksAreDownloaded() {
        assertTrue(SmartRules.isHttpUrl("https://example.com/a.zip"))
        assertTrue(SmartRules.isHttpUrl("HTTP://example.com/a.zip"))
        assertFalse(SmartRules.isHttpUrl("file:///sdcard/a.zip"))
        assertFalse(SmartRules.isHttpUrl("javascript:alert(1)"))
        assertFalse(SmartRules.isHttpUrl("https://exa mple.com"))
    }
}
