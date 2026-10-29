package ai.nisu.assistant

import org.junit.Assert.*
import org.junit.Test

class AiClipboardTest {
    @Test fun otpAndBlankAreNotKept() {
        assertTrue(AiClipboard.skip("123456"))
        assertTrue(AiClipboard.skip("   "))
        assertFalse(AiClipboard.skip("https://play.google.com/store/apps/details?id=com.whatsapp"))
        assertFalse(AiClipboard.skip("12345678901"))
    }

    @Test fun linkDetection() {
        assertTrue(AiClipboard.isLink("https://a.com/x"))
        assertFalse(AiClipboard.isLink("see https://a.com"))
    }

    @Test fun markersForClipboard() {
        assertEquals("SAVE" to "WhatsApp | https://wa.me", HiddenBrowser.markerAction("[[SAVE: WhatsApp | https://wa.me]]"))
        assertEquals("COPY" to "3", HiddenBrowser.markerAction("[[COPY: 3]]"))
        assertEquals("CLIPS" to "youtube", HiddenBrowser.markerAction("[[clips: youtube]]"))
    }

    @Test fun toolNamesStaySilent() {
        assertTrue(HiddenBrowser.isHidden("ai_clipboard"))
        assertFalse(HiddenBrowser.guestOk("ai_clipboard"))
        assertTrue(HiddenBrowser.guestOk("web_search"))
    }
}

class LinkRuleTest {
    @Test fun linkOnlyWhenAsked() {
        val r = "WhatsApp is free on the Play Store.\nLink: https://play.google.com/store/apps/details?id=com.whatsapp\nInstall it there (https://example.com)."
        val s = HiddenBrowser.stripLinks(r, "whatsapp ache?")
        assertFalse(s.contains("http"))
        assertTrue(s.contains("WhatsApp is free"))
        assertFalse(s.contains("Link:"))
        assertEquals(r, HiddenBrowser.stripLinks(r, "link dao"))
        assertEquals(r, HiddenBrowser.stripLinks(r, "লিংক দেখাও"))
        assertTrue(HiddenBrowser.wantsLink("send me the link"))
        assertFalse(HiddenBrowser.wantsLink("whatsapp ki"))
    }
}
