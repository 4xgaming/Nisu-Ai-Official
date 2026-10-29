package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockerTest {
    @Test fun pinMustBeFourToSixteenDigits() {
        assertEquals("1234", Unlocker.validSecret("pin", " 1234 "))
        assertNull(Unlocker.validSecret("pin", "123"))
        assertNull(Unlocker.validSecret("pin", "12a4"))
        assertNull(Unlocker.validSecret("pin", "12345678901234567"))
    }

    @Test fun passwordKeepsEveryCharacterAndRejectsNewlines() {
        assertEquals("p@ss w0rd'\"", Unlocker.validSecret("password", "p@ss w0rd'\""))
        assertNull(Unlocker.validSecret("password", "abc"))
        assertNull(Unlocker.validSecret("password", "abcd\nefgh"))
    }

    @Test fun patternNeedsFourDifferentDotsFromOneToNine() {
        assertEquals("14789", Unlocker.validSecret("pattern", "14789"))
        assertNull(Unlocker.validSecret("pattern", "123"))
        assertNull(Unlocker.validSecret("pattern", "1231"))
        assertNull(Unlocker.validSecret("pattern", "1230"))
        assertNull(Unlocker.validSecret("fingerprint", "1234"))
    }

    @Test fun waitRuleOnlyBlocksWhileBlocked() {
        assertEquals(0L, Unlocker.waitMs(0, 0L, 0L, 1000L))
        assertEquals(4000L, Unlocker.waitMs(2, 0L, 5000L, 1000L))
        assertTrue(Unlocker.waitMs(2, 0L, 5000L, 6000L) == 0L)
    }
}
