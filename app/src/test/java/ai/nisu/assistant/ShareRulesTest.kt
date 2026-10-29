package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareRulesTest {
    @Test fun appNamesAreFoundInEnglishAndBengali() {
        assertEquals("whatsapp", ShareRules.findApp("WhatsApp")?.key)
        assertEquals("whatsapp", ShareRules.findApp("wp")?.key)
        assertEquals("whatsapp", ShareRules.findApp("wa")?.key)
        assertEquals("whatsapp", ShareRules.findApp("send on whatsapp")?.key)
        assertEquals("whatsapp", ShareRules.findApp("\u09B9\u09CB\u09AF\u09BC\u09BE\u099F\u09B8\u0985\u09CD\u09AF\u09BE\u09AA")?.key)
        assertEquals("telegram", ShareRules.findApp("Telegram")?.key)
        assertEquals("gmail", ShareRules.findApp("email")?.key)
    }

    @Test fun shortAliasesNeedTheWholeWordAndUnknownIsNull() {
        assertNull(ShareRules.findApp("swap"))
        assertNull(ShareRules.findApp("someapp"))
        assertNull(ShareRules.findApp(""))
    }

    @Test fun chatAppsAreMarkedAsChat() {
        assertTrue(ShareRules.findApp("whatsapp")!!.chat)
        assertFalse(ShareRules.findApp("instagram")!!.chat)
        assertNotNull(ShareRules.findApp("messenger"))
    }

    @Test fun statusTarget() {
        assertTrue(ShareRules.isStatusTarget("My Status"))
        assertTrue(ShareRules.isStatusTarget("status"))
        assertFalse(ShareRules.isStatusTarget("Rahim"))
    }

    @Test fun pickTakesExactThenOneThenAsks() {
        val a = ShareRules.pick("rahim", listOf("Rahim", "Rahim Uddin"))
        assertTrue(a is ShareRules.Pick.Exact); assertEquals("Rahim", (a as ShareRules.Pick.Exact).label)

        val b = ShareRules.pick("rahim", listOf("Rahim Uddin", "Rahim Uddin", "Karim"))
        assertTrue(b is ShareRules.Pick.One); assertEquals("Rahim Uddin", (b as ShareRules.Pick.One).label)

        val c = ShareRules.pick("rahim", listOf("Rahim Uddin", "Abdur Rahim"))
        assertTrue(c is ShareRules.Pick.Many); assertEquals(2, (c as ShareRules.Pick.Many).labels.size)
    }

    @Test fun pickIgnoresCaseAndSpacesAndFindsNothingForNothing() {
        assertTrue(ShareRules.pick("  FAMILY   group ", listOf("Family Group")) is ShareRules.Pick.Exact)
        assertTrue(ShareRules.pick("rahim", emptyList()) is ShareRules.Pick.None)
        assertTrue(ShareRules.pick("", listOf("Rahim")) is ShareRules.Pick.None)
        assertTrue(ShareRules.pick("rahim", listOf("Karim", "Search")) is ShareRules.Pick.None)
    }

    @Test fun ageText() {
        assertEquals("30 seconds ago", ShareRules.ageText(1000, 1030))
        assertEquals("10 minutes ago", ShareRules.ageText(1000, 1600))
        assertEquals("2 hours ago", ShareRules.ageText(1000, 8200))
        assertEquals("2 days ago", ShareRules.ageText(1000, 201000))
    }

    @Test fun sharingIsNeverRepeatedByTheModel() {
        assertTrue("share_media" in ToolSteady.ONE_SHOT)
        assertTrue("share_pick" in ToolSteady.ONE_SHOT)
    }
}
