package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisThemeTest {
    @Test fun onlyJarvisModeShowsTheJarvisName() {
        assertEquals("Jarvis", JarvisTheme.title(PersonaMode.JARVIS))
        for (m in listOf(PersonaMode.CARE, PersonaMode.BOSS, PersonaMode.FRIEND, PersonaMode.VENOM)) assertEquals(Brand.APP_NAME, JarvisTheme.title(m))
    }

    @Test fun jarvisAndVenomThemesNeverOverlap() {
        for (m in PersonaMode.ALL) assertFalse(JarvisTheme.on(m) && VenomTheme.on(m))
        assertTrue(JarvisTheme.on(PersonaMode.normalize("jervis")!!))
    }

    @Test fun theBrandNameStaysNisuAi() {
        assertEquals("NISU AI", Brand.APP_NAME)
        assertTrue(Brand.integrityOk())
    }

    @Test fun theReactorFollowsHisVoiceAndFadesBackOut() {
        assertEquals(0f, JarvisTheme.voiceTarget(0f), 0f)
        assertEquals(1f, JarvisTheme.voiceTarget(0.9f), 0f)           // loud speech is capped at 1
        var up = 0f
        repeat(10) { up = JarvisTheme.voiceStep(up, 1f, 0.016f) }
        assertTrue(up > 0.5f && up <= 1f)                              // rises quickly with his voice
        var down = 1f
        repeat(10) { down = JarvisTheme.voiceStep(down, 0f, 0.016f) }
        assertTrue(down < 1f && down > 0.4f)                           // and falls slowly after it
        assertTrue(JarvisTheme.voiceStep(0.5f, 1f, 0.016f) - 0.5f > 0.5f - JarvisTheme.voiceStep(0.5f, 0f, 0.016f))   // attack faster than release
    }
}
