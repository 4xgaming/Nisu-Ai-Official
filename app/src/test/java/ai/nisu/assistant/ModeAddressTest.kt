package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.134: tui / tumi / apni per mode. */
class ModeAddressTest {
    @Test fun defaultsPerMode() {
        assertEquals(ModeAddress.Form.TUI, ModeAddress.defaultFor(PersonaMode.FRIEND))
        assertEquals(ModeAddress.Form.TUMI, ModeAddress.defaultFor(PersonaMode.CARE))
        assertEquals(ModeAddress.Form.APNI, ModeAddress.defaultFor(PersonaMode.BOSS))
        assertEquals(ModeAddress.Form.APNI, ModeAddress.defaultFor(PersonaMode.JARVIS))
        assertEquals(ModeAddress.Form.TUI, ModeAddress.defaultFor(PersonaMode.VENOM))
    }

    @Test fun onlyBossJarvisVenomAreLocked() {
        assertTrue(ModeAddress.locked(PersonaMode.BOSS))
        assertTrue(ModeAddress.locked(PersonaMode.JARVIS))
        assertTrue(ModeAddress.locked(PersonaMode.VENOM))
        assertFalse(ModeAddress.locked(PersonaMode.FRIEND))
        assertFalse(ModeAddress.locked(PersonaMode.CARE))
    }

    @Test fun switchableModesAllowTheChange() {
        for (m in listOf(PersonaMode.FRIEND, PersonaMode.CARE)) {
            val n = ModeAddress.note(m)
            assertTrue(n.contains("tumi kore bolo"))
            assertTrue(n.contains("keep that form"))
            assertFalse(n.contains("designed to say apni only"))
        }
        assertTrue(ModeAddress.note(PersonaMode.FRIEND).contains("তুই to him by default"))
        assertTrue(ModeAddress.note(PersonaMode.CARE).contains("তুমি to him by default"))
    }

    @Test fun bossAndJarvisSaySorryAndKeepApni() {
        val boss = ModeAddress.note(PersonaMode.BOSS)
        assertTrue(boss.contains("ONLY আপনি"))
        assertTrue(boss.contains("Sorry boss, ami apnake apni-i bolbo, amake emon-i design kora hoyeche."))
        assertTrue(boss.contains("দুঃখিত বস"))
        val jarvis = ModeAddress.note(PersonaMode.JARVIS)
        assertTrue(jarvis.contains("ONLY আপনি"))
        assertTrue(jarvis.contains("Sorry Sir, ami apnake apni-i bolbo"))
    }

    @Test fun venomOnlyTui() {
        val v = ModeAddress.note(PersonaMode.VENOM)
        assertTrue(v.contains("ONLY তুই"))
        assertTrue(v.contains("never তুমি and never আপনি"))
    }

    @Test fun noMixingRuleEverywhereAndNoDollarSign() {
        for (m in PersonaMode.ALL) {
            val n = ModeAddress.note(m)
            assertTrue(n.contains("ENDINGS MUST MATCH THE PRONOUN"))
            assertFalse(n.contains("$"))
        }
    }

    @Test fun reachesThePrompt() {
        for (m in PersonaMode.ALL) assertTrue(Persona.modeTruth(m).contains("BENGALI ADDRESS"))
    }
}
