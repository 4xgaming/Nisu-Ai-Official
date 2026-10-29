package ai.nisu.assistant

import ai.nisu.assistant.ThemeCommandRules.LoopCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCommandsTest {
    @Test fun commandsWorkOnlyInNisuModes() {
        assertTrue(ThemeCommandRules.modeAllowed(PersonaMode.CARE))
        assertTrue(ThemeCommandRules.modeAllowed(PersonaMode.FRIEND))
        assertTrue(ThemeCommandRules.modeAllowed(PersonaMode.BOSS))
        assertFalse(ThemeCommandRules.modeAllowed(PersonaMode.JARVIS))
        assertFalse(ThemeCommandRules.modeAllowed(PersonaMode.VENOM))
    }

    @Test fun loopNeedsPhotos() {
        assertEquals(LoopCheck.NO_PHOTO, ThemeCommandRules.loopCheck(0))
        assertEquals(LoopCheck.NEED_TWO, ThemeCommandRules.loopCheck(1))
        assertEquals(LoopCheck.OK, ThemeCommandRules.loopCheck(2))
        assertEquals(LoopCheck.OK, ThemeCommandRules.loopCheck(10))
    }

    @Test fun galleryCyclesTheReadyThemesEvery5SecondsAndNeverShowsDefault() {
        assertEquals(5, ThemeCommandRules.GALLERY_SECS)
        assertEquals(3, ThemeCommandRules.READY.size)
        assertEquals(ThemeCommandRules.GARDEN, ThemeCommandRules.next(null))          // start (default look or custom photos): first ready theme
        assertEquals(ThemeCommandRules.SAREE, ThemeCommandRules.next(ThemeCommandRules.GARDEN))
        assertEquals(ThemeCommandRules.LACE, ThemeCommandRules.next(ThemeCommandRules.SAREE))
        assertEquals(ThemeCommandRules.GARDEN, ThemeCommandRules.next(ThemeCommandRules.LACE))   // back to the first, never to default
        var k: String? = null
        repeat(20) { k = ThemeCommandRules.next(k); assertTrue(k in ThemeCommandRules.READY) }
    }

    @Test fun actionNamesAreUnderstood() {
        assertEquals(ThemeCommandRules.LOOP_START, ThemeCommandRules.action("loop_start"))
        assertEquals(ThemeCommandRules.LOOP_START, ThemeCommandRules.action("Theme Loop start"))
        assertEquals(ThemeCommandRules.LOOP_STOP, ThemeCommandRules.action("loop stop"))
        assertEquals(ThemeCommandRules.GALLERY_START, ThemeCommandRules.action("gallery_start"))
        assertEquals(ThemeCommandRules.GALLERY_START, ThemeCommandRules.action("theme galary start"))
        assertEquals(ThemeCommandRules.GALLERY_STOP, ThemeCommandRules.action("gallery-off"))
        assertEquals(ThemeCommandRules.DEFAULT, ThemeCommandRules.action("default"))
        assertEquals(ThemeCommandRules.DEFAULT, ThemeCommandRules.action("default theme"))
        assertNull(ThemeCommandRules.action(""))
        assertNull(ThemeCommandRules.action("dance"))
    }

    @Test fun customThemeStartAndSetTimeAreUnderstood() {
        assertEquals(ThemeCommandRules.CUSTOM_START, ThemeCommandRules.action("custom_start"))
        assertEquals(ThemeCommandRules.CUSTOM_START, ThemeCommandRules.action("custom theme start"))
        assertEquals(ThemeCommandRules.DEFAULT, ThemeCommandRules.action("custom off"))
        assertEquals(ThemeCommandRules.SET_TIME, ThemeCommandRules.action("set_time"))
        assertEquals(ThemeCommandRules.LOOP_START, ThemeCommandRules.action("loop_start"))   // still not mistaken for a time
    }

    @Test fun timesInSecondsMinutesHoursAndDays() {
        assertEquals(10, ThemeCommandRules.toSeconds(10.0, "second"))
        assertEquals(30, ThemeCommandRules.toSeconds(30.0, "seconds"))
        assertEquals(600, ThemeCommandRules.toSeconds(10.0, "minute"))
        assertEquals(600, ThemeCommandRules.toSeconds(10.0, "minit"))
        assertEquals(7200, ThemeCommandRules.toSeconds(2.0, "hour"))
        assertEquals(7200, ThemeCommandRules.toSeconds(2.0, "ghonta"))
        assertEquals(5400, ThemeCommandRules.toSeconds(1.5, "hours"))
        assertEquals(86400, ThemeCommandRules.toSeconds(1.0, "day"))
        assertEquals(172800, ThemeCommandRules.toSeconds(2.0, "din"))
    }

    @Test fun badTimesAreRefusedAndHugeOnesAreCapped() {
        assertNull(ThemeCommandRules.toSeconds(0.0, "second"))
        assertNull(ThemeCommandRules.toSeconds(-5.0, "minute"))
        assertNull(ThemeCommandRules.toSeconds(Double.NaN, "minute"))
        assertNull(ThemeCommandRules.toSeconds(5.0, "fortnight"))
        assertNull(ThemeCommandRules.toSeconds(5.0, ""))
        assertEquals(ThemeCommandRules.MIN_SECS, ThemeCommandRules.toSeconds(1.0, "second"))
        assertEquals(ThemeCommandRules.LIMIT_SECS, ThemeCommandRules.toSeconds(400.0, "day"))
        assertEquals(30 * 24 * 3600, ThemeCommandRules.LIMIT_SECS)
    }

    @Test fun timesAreReadBackInPlainWords() {
        assertEquals("5 seconds", ThemeCommandRules.describe(5))
        assertEquals("1 minute 30 seconds", ThemeCommandRules.describe(90))
        assertEquals("1 hour", ThemeCommandRules.describe(3600))
        assertEquals("2 days", ThemeCommandRules.describe(172800))
        assertEquals("1 day 1 hour 1 minute 1 second", ThemeCommandRules.describe(90061))
    }
}
