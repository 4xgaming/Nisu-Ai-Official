package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestSlotTest {
    @Test fun emptySlotGivesNothing() {
        val s = LatestSlot<String>()
        assertFalse(s.hasItem())
        assertNull(s.take())
    }

    @Test fun oneSoundWaitsAndIsTakenOnce() {
        val s = LatestSlot<String>()
        assertNull(s.offer("a"))
        assertTrue(s.hasItem())
        assertEquals("a", s.take())
        assertNull(s.take())
    }

    @Test fun aNewerSoundReplacesAnOlderWaitingOneAndTheOldOneIsHandedBack() {
        val s = LatestSlot<String>()
        s.offer("old")
        assertEquals("old", s.offer("new"))      // the caller wipes the old sound
        assertEquals("new", s.take())
        assertFalse(s.hasItem())
    }

    @Test fun nothingIsDroppedJustBecauseACheckIsRunning() {
        // while a check runs, the next sound simply waits here and is taken as soon as the check ends
        val s = LatestSlot<Int>()
        s.offer(1)
        assertEquals(1, s.take())      // check 1 running
        s.offer(2)                     // "Hey Nishu" said again meanwhile
        assertTrue(s.hasItem())
        assertEquals(2, s.take())      // check 2 starts right after
    }
}
