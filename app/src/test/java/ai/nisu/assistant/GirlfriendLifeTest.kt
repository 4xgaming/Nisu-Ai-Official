package ai.nisu.assistant

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GirlfriendLifeTest {
    @Test fun bossModeGetsNoMomentNote() {
        assertEquals("", GirlfriendLife.momentText(PersonaMode.BOSS, 23, Calendar.MONDAY, 5000))
    }

    @Test fun lateNightCareModeWorriesAboutSleep() {
        val t = GirlfriendLife.momentText(PersonaMode.CARE, 2, Calendar.MONDAY, 5)
        assertTrue(t.contains("after midnight"))
        assertTrue(t.contains("no greeting"))
    }

    @Test fun longSilenceGivesAbhimanOnlyInCareMode() {
        val care = GirlfriendLife.momentText(PersonaMode.CARE, 18, Calendar.FRIDAY, 3L * 24 * 60)
        val friend = GirlfriendLife.momentText(PersonaMode.FRIEND, 18, Calendar.FRIDAY, 3L * 24 * 60)
        assertTrue(care.contains("3 days"))
        assertTrue(care.contains("অভিমান"))
        assertFalse(friend.contains("অভিমান"))
        assertTrue(care.contains("Friday"))
    }

    @Test fun firstTalkNeverClaimsSharedPast() {
        assertTrue(GirlfriendLife.momentText(PersonaMode.CARE, 9, Calendar.TUESDAY, -1).contains("no shared memories"))
    }
}
