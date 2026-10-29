package ai.nisu.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeTruthTest {
    @Test fun eachModeGetsItsOwnBlock() {
        val care = Persona.modeTruth(PersonaMode.CARE)
        val friend = Persona.modeTruth(PersonaMode.FRIEND)
        val boss = Persona.modeTruth(PersonaMode.BOSS)
        assertTrue(care.contains("REAL GIRLFRIEND"))
        assertTrue(friend.contains("REAL BEST FRIEND"))
        assertTrue(boss.contains("TOP-CLASS ASSISTANT TO A BOSS"))
        assertNotEquals(care, friend)
        assertNotEquals(friend, boss)
    }

    @Test fun friendAndBossStayWithoutRomance() {
        assertTrue(Persona.modeTruth(PersonaMode.FRIEND).contains("No flirting"))
        assertTrue(Persona.modeTruth(PersonaMode.BOSS).contains("no romance"))
        assertFalse(Persona.modeTruth(PersonaMode.BOSS).contains("pet name more than rarely"))
    }

    @Test fun careNeverControlsHim() {
        val care = Persona.modeTruth(PersonaMode.CARE)
        assertTrue(care.contains("never turns into control"))
    }

    @Test fun unknownModeFallsBackToCare() {
        assertTrue(Persona.modeTruth("something").contains("REAL GIRLFRIEND"))
    }
}
