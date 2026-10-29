package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VenomThemeTest {
    @Test fun onlyVenomModeChangesTheShownHeaderName() {
        assertEquals("Venom", VenomTheme.title(PersonaMode.VENOM))
        for (m in listOf(PersonaMode.CARE, PersonaMode.BOSS, PersonaMode.FRIEND, PersonaMode.JARVIS)) assertEquals(Brand.APP_NAME, VenomTheme.title(m))
    }

    @Test fun theBrandNameAndDeveloperAreUntouched() {
        assertEquals("NISU AI", Brand.APP_NAME)
        assertEquals("Raju", Brand.DEVELOPER)
        assertTrue(Brand.integrityOk())
    }

    @Test fun venomModeIsRecognisedByNameAndBySpeech() {
        assertTrue(VenomTheme.on(PersonaMode.normalize("venom")!!))
        assertTrue(VenomTheme.on(PersonaMode.normalize("ভেনম")!!))
        assertFalse(VenomTheme.on(PersonaMode.CARE))
    }
}
