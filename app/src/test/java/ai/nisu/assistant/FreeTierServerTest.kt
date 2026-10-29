package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class FreeTierServerTest {
    @Test fun windowConstants() {
        assertEquals(15 * 60 * 1000L, FreeTier.FREE_MS)
        assertEquals(6 * 60 * 60 * 1000L, FreeTier.RESET_MS)
    }
}
