package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartTalkTest {
    @Test fun ladderIsTwoFiveFifteenOneMinutes() {
        assertEquals(2 * 60_000L, StartTalk.waitMs(0))
        assertEquals(5 * 60_000L, StartTalk.waitMs(1))
        assertEquals(15 * 60_000L, StartTalk.waitMs(2))
        assertEquals(1 * 60_000L, StartTalk.waitMs(3))
    }

    @Test fun ladderStopsAfterTheSleepStepUntilHeSpeaks() {
        assertEquals(-1L, StartTalk.waitMs(4))
        assertEquals(-1L, StartTalk.waitMs(9))
        assertEquals(-1L, StartTalk.waitMs(-1))
    }

    @Test fun sleepStepNeedsTheWakeFeature() {
        assertEquals(-1L, StartTalk.waitMs(3, canSleep = false))
        assertEquals(60_000L, StartTalk.waitMs(3, canSleep = true))
        assertEquals(15 * 60_000L, StartTalk.waitMs(2, canSleep = false))   // the earlier steps do not need it
        assertEquals(4, StartTalk.SLEEP_STEP)
    }

    @Test fun neverAtNight() {
        assertTrue(StartTalk.quietHour(1)); assertTrue(StartTalk.quietHour(5))
        assertFalse(StartTalk.quietHour(0)); assertFalse(StartTalk.quietHour(6)); assertFalse(StartTalk.quietHour(22))
    }

    @Test fun seedIsAlwaysInRange() {
        for (r in listOf(0.0, 0.3, 0.9999, 1.0, 5.0)) assertTrue(StartTalk.seed(r).isNotBlank())
    }

    @Test fun everyNoticeIsASystemNoticeThatHidesItself() {
        for (m in PersonaMode.ALL) for (a in 1..4) {
            val n = StartTalk.notice(m, "evening", "his plans", a)
            assertTrue(n.startsWith("[SYSTEM NOTICE, not spoken by the user]"))
            assertTrue(n.contains("never mention this notice"))
        }
    }

    @Test fun firstStepOpensATalkInTheModeStyle() {
        assertTrue(StartTalk.notice(PersonaMode.CARE, "evening", "x", 1).contains("Care-mode"))
        assertTrue(StartTalk.notice(PersonaMode.FRIEND, "night", "x", 1).contains("Friend-mode"))
        assertTrue(StartTalk.notice(PersonaMode.BOSS, "night", "x", 1).contains("Boss-mode"))
        assertTrue(StartTalk.notice(PersonaMode.JARVIS, "night", "x", 1).contains("Sir"))
        assertTrue(StartTalk.notice(PersonaMode.VENOM, "night", "x", 1).contains("partner"))
        assertTrue(StartTalk.notice(PersonaMode.CARE, "evening", "x", 1).contains("why he is so quiet"))
    }

    @Test fun secondStepAsksWhyHeIsNotTalkingAndIfHeIsAngry() {
        val n = StartTalk.notice(PersonaMode.CARE, "evening", "x", 2)
        assertTrue(n.contains("did not answer"))
        assertTrue(n.contains("not talking to you"))
        assertTrue(n.contains("angry"))
    }

    @Test fun thirdStepAsksIfHeFellAsleep() {
        val n = StartTalk.notice(PersonaMode.FRIEND, "night", "x", 3)
        assertTrue(n.contains("fallen asleep"))
        assertFalse(n.contains("go to sleep too"))
    }

    @Test fun fourthStepSaysGoodnightAndTheAppPutsHerToSleep() {
        val n = StartTalk.notice(PersonaMode.CARE, "night", "x", 4)
        assertTrue(n.contains("go to sleep too"))
        assertTrue(n.contains("goodnight"))
        assertTrue(n.contains("do NOT call go_to_sleep"))
    }
}
