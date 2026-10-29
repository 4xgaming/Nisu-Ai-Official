package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.79: Jarvis and Venom modes. */
class PersonaModesTest {
    @Test fun namesAreUnderstood() {
        assertEquals(PersonaMode.JARVIS, PersonaMode.normalize("Jarvis"))
        assertEquals(PersonaMode.JARVIS, PersonaMode.normalize(" jervis "))
        assertEquals(PersonaMode.VENOM, PersonaMode.normalize("VENOM"))
        assertEquals(PersonaMode.CARE, PersonaMode.normalize("girlfriend"))
        assertNull(PersonaMode.normalize("robot"))
        assertTrue(PersonaMode.ALL.containsAll(listOf(PersonaMode.JARVIS, PersonaMode.VENOM)))
    }

    @Test fun bossLikeModesHaveNoGirlfriendFeelings() {
        assertTrue(PersonaMode.bossLike(PersonaMode.BOSS))
        assertTrue(PersonaMode.bossLike(PersonaMode.JARVIS))
        assertTrue(PersonaMode.bossLike(PersonaMode.VENOM))
        assertFalse(PersonaMode.bossLike(PersonaMode.CARE))
        assertFalse(PersonaMode.bossLike(PersonaMode.FRIEND))
    }

    @Test fun onlyJarvisAndVenomLockTheVoice() {
        assertNotNull(PersonaMode.voiceFor(PersonaMode.JARVIS))
        assertNotNull(PersonaMode.voiceFor(PersonaMode.VENOM))
        assertFalse(PersonaMode.voiceFor(PersonaMode.JARVIS) == PersonaMode.voiceFor(PersonaMode.VENOM))
        assertNull(PersonaMode.voiceFor(PersonaMode.CARE))
        assertNull(PersonaMode.voiceFor(PersonaMode.BOSS))
        assertNull(PersonaMode.voiceFor(PersonaMode.FRIEND))
        assertNotNull(PersonaMode.pitchFor(PersonaMode.VENOM))
        assertNull(PersonaMode.pitchFor(PersonaMode.JARVIS))
    }

    @Test fun textsAreMaleAndKeepTheBrand() {
        for (m in listOf(PersonaMode.JARVIS, PersonaMode.VENOM)) {
            val id = ModePersonas.identity(m, "Raju")
            assertTrue(id.contains("MALE"))
            assertTrue(id.contains("NISU AI"))
            assertTrue(id.contains("Raju developed NISU AI"))
            val note = ModePersonas.overrideNote(m)
            assertTrue(note.contains("MASCULINE"))
            assertTrue(note.contains("Raju developed NISU AI"))
            assertTrue(Persona.modeTruth(m).contains("no romance"))
        }
        assertTrue(ModePersonas.overrideNote(PersonaMode.JARVIS).contains("Sir"))
        assertTrue(ModePersonas.overrideNote(PersonaMode.JARVIS).contains("name is Jarvis") || ModePersonas.overrideNote(PersonaMode.JARVIS).contains("NAME IN THIS MODE IS JARVIS"))
        assertTrue(ModePersonas.identity(PersonaMode.JARVIS, "Raju").contains("I am Jarvis"))
        assertTrue(ModePersonas.identity(PersonaMode.VENOM, "Raju").contains("I am Venom"))
        assertTrue(ModePersonas.overrideNote(PersonaMode.VENOM).contains("NAME IN THIS MODE IS VENOM"))
        assertTrue(ModePersonas.whoLine(PersonaMode.VENOM, "Raju").contains("VENOM"))
        assertTrue(ModePersonas.whoLine(PersonaMode.JARVIS, "Raju").contains("JARVIS"))
        assertTrue(ModePersonas.overrideNote(PersonaMode.VENOM).contains("partner"))
        assertEquals("", ModePersonas.overrideNote(PersonaMode.CARE))
    }
}
