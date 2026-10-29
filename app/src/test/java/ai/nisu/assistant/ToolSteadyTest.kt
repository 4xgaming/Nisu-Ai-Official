package ai.nisu.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSteadyTest {
    private val k = "click_text|{\"text\":\"Next\"}"

    @Test fun sameOneShotActionRightAfterOkIsARepeat() {
        assertTrue(ToolSteady.isRepeat(k, 10_000, true, "click_text", k, 10_900))
        assertTrue(ToolSteady.isRepeat(k, 10_000, true, "click_text", k, 11_500))
    }

    @Test fun laterOrDifferentOrFailedIsNotARepeat() {
        assertFalse(ToolSteady.isRepeat(k, 10_000, true, "click_text", k, 11_501))            // after the window: the model had time to read
        assertFalse(ToolSteady.isRepeat(k, 10_000, true, "click_text", "click_text|{\"text\":\"Back\"}", 10_500))   // other arguments
        assertFalse(ToolSteady.isRepeat(k, 10_000, false, "click_text", k, 10_500))            // the first one failed: trying again is fine
        assertFalse(ToolSteady.isRepeat(null, 0, true, "click_text", k, 10_500))               // nothing ran before
    }

    @Test fun repeatableActionsAreNeverBlocked() {
        for (n in listOf("scroll", "swipe", "press_key", "wait", "get_screen")) {
            val key = "$n|{}"
            assertFalse(n, ToolSteady.isRepeat(key, 10_000, true, n, key, 10_100))
        }
    }

    @Test fun typingAndOpeningAreOneShot() {
        for (n in listOf("type_text", "open_app", "tap", "send_message")) assertTrue(n, n in ToolSteady.ONE_SHOT)
    }

    @Test fun staleAppRule() {
        assertTrue(ToolSteady.staleApp("com.whatsapp", "com.android.chrome"))
        assertFalse(ToolSteady.staleApp("com.whatsapp", "com.whatsapp"))
        assertFalse(ToolSteady.staleApp("", "com.whatsapp"))      // never read yet: unknown, not stale
        assertFalse(ToolSteady.staleApp("com.whatsapp", ""))
    }

    @Test fun statefulWrapperBlocksOnlyTheImmediateRepeat() {
        ToolSteady.reset()
        val now = System.currentTimeMillis()
        assertFalse(ToolSteady.repeated("open_app", "{\"name\":\"WhatsApp\"}", now))
        ToolSteady.record("open_app", "{\"name\":\"WhatsApp\"}", true, now)
        assertTrue(ToolSteady.repeated("open_app", "{\"name\":\"WhatsApp\"}", now + 300))
        assertFalse(ToolSteady.repeated("open_app", "{\"name\":\"WhatsApp\"}", now + 5_000))
        ToolSteady.reset()
    }

    @Test fun sameOneShotActionThreeTimesInAWindowIsALoop() {
        assertTrue(ToolSteady.isLoop("tap", listOf(1_000L, 9_000L, 20_000L), 30_000))
        assertFalse(ToolSteady.isLoop("tap", listOf(1_000L, 9_000L), 30_000))                 // only two before
        assertFalse(ToolSteady.isLoop("tap", listOf(1_000L, 9_000L, 20_000L), 60_000))        // too long ago
        assertFalse(ToolSteady.isLoop("scroll", listOf(1_000L, 9_000L, 20_000L), 30_000))     // scrolling is meant to repeat
    }
}
