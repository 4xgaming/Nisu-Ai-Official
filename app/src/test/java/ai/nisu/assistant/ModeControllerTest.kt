package ai.nisu.assistant

import ai.nisu.assistant.ModeController.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeControllerTest {

    @Test fun startsInRequestedMode() {
        assertTrue(ModeController().isActive)
        assertTrue(ModeController(AssistantMode.SLEEP).isSleeping)
    }

    // ---- SLEEP
    @Test fun sleepingWakesOnlyOnExactWakePhrase() {
        val m = ModeController(AssistantMode.SLEEP)
        assertEquals(Decision.WAKE, m.onTranscript("হে নিশু"))
        assertTrue(m.isActive)
        val m2 = ModeController(AssistantMode.SLEEP)
        assertEquals(Decision.WAKE, m2.onTranscript("Hey Nishu"))
        assertTrue(m2.isActive)
    }
    @Test fun sleepingDiscardsEverythingElse() {
        val m = ModeController(AssistantMode.SLEEP)
        for (t in listOf("নিশু", "হে", "হে নিশা", "ওকে নিশা", "hey", "okay", "nishu", "what is the weather",
            "call mom", "hey nishu call mom", "music music music", "")) {
            assertEquals("'$t'", Decision.DISCARD, m.onTranscript(t))
            assertTrue("still asleep after '$t'", m.isSleeping)
        }
    }
    @Test fun sleepCommandWhileSleepingIsDiscardedNotForwarded() {   // command received in the wrong state
        val m = ModeController(AssistantMode.SLEEP)
        assertEquals(Decision.DISCARD, m.onTranscript("Nishu Sleep"))
        assertEquals(Decision.DISCARD, m.onTranscript("নিশু চুপ করো"))
        assertTrue(m.isSleeping)
    }
    @Test fun sleepingNeverForwards() {
        val m = ModeController(AssistantMode.SLEEP)
        assertFalse(listOf("hello", "নিশু", "hey", "sleep").any { m.onTranscript(it) == Decision.FORWARD })
    }

    // ---- ACTIVE
    @Test fun activeSleepsOnBothSleepCommands() {
        for (t in listOf("নিশু স্লিপ", "Nishu Sleep", "নিশু চুপ করো", "Nishu Chup Koro", "Nishu, sleep!")) {
            val m = ModeController(AssistantMode.ACTIVE)
            assertEquals(t, Decision.SLEEP, m.onTranscript(t))
            assertTrue(m.isSleeping)
        }
    }
    @Test fun activeForwardsOrdinarySpeech() {
        val m = ModeController(AssistantMode.ACTIVE)
        for (t in listOf("what is the weather", "নিশু", "nishu sleep now", "please nishu sleep", "sleep", "নিশা স্লিপ"))
            assertEquals("'$t'", Decision.FORWARD, m.onTranscript(t))
        assertTrue(m.isActive)
    }
    @Test fun wakePhraseWhileActiveIsOrdinarySpeech() {   // wrong state: no transition, not discarded
        val m = ModeController(AssistantMode.ACTIVE)
        val e = m.epoch
        assertEquals(Decision.FORWARD, m.onTranscript("Hey Nishu"))
        assertTrue(m.isActive)
        assertEquals(e, m.epoch)
    }

    // ---- cycle
    @Test fun fullCycle() {
        val m = ModeController(AssistantMode.SLEEP)
        assertEquals(Decision.WAKE, m.onTranscript("hey nishu"))
        assertEquals(Decision.FORWARD, m.onTranscript("tell me a joke"))
        assertEquals(Decision.SLEEP, m.onTranscript("nishu sleep"))
        assertEquals(Decision.DISCARD, m.onTranscript("tell me a joke"))
        assertEquals(Decision.WAKE, m.onTranscript("হে নিশু"))
        assertTrue(m.isActive)
    }

    // ---- wake alternatives path used by the recogniser
    @Test fun wakeAlternativesOnlyWorkWhileSleeping() {
        val sleeping = ModeController(AssistantMode.SLEEP)
        assertEquals(Decision.WAKE, sleeping.onWakeAlternatives(listOf("hey nisha", "hey nishu")))
        assertTrue(sleeping.isActive)
        assertEquals(Decision.DISCARD, sleeping.onWakeAlternatives(listOf("hey nishu")))   // already active
        val s2 = ModeController(AssistantMode.SLEEP)
        assertEquals(Decision.DISCARD, s2.onWakeAlternatives(listOf("hey nisha", "nishu", "hey")))
        assertTrue(s2.isSleeping)
        assertEquals(Decision.DISCARD, s2.onWakeAlternatives(listOf("hey nishu"), floatArrayOf(0.05f)))
        assertTrue(s2.isSleeping)
    }

    // ---- stale work must not survive a sleep
    @Test fun epochInvalidatesPendingWork() {
        val m = ModeController(AssistantMode.ACTIVE)
        val e = m.epoch
        assertTrue(m.isCurrent(e))
        m.onTranscript("nishu sleep")
        assertFalse("work started before sleep is stale", m.isCurrent(e))
        m.onTranscript("hey nishu")
        assertTrue(m.isActive)
        assertFalse("work started before sleep stays stale after waking", m.isCurrent(e))
        assertTrue(m.isCurrent(m.epoch))
    }
    @Test fun nothingIsCurrentWhileSleeping() {
        val m = ModeController(AssistantMode.SLEEP)
        assertFalse(m.isCurrent(m.epoch))
    }

    // ---- force() and listener
    @Test fun forceReportsRealChangesOnly() {
        val seen = ArrayList<AssistantMode>()
        val m = ModeController(AssistantMode.ACTIVE) { mode, _ -> seen.add(mode) }
        assertFalse(m.force(AssistantMode.ACTIVE))
        assertTrue(m.force(AssistantMode.SLEEP))
        assertFalse(m.force(AssistantMode.SLEEP))
        assertTrue(m.force(AssistantMode.ACTIVE))
        assertEquals(listOf(AssistantMode.SLEEP, AssistantMode.ACTIVE), seen)
    }
    @Test fun listenerFiresOnSpeechTransitions() {
        val seen = ArrayList<AssistantMode>()
        val m = ModeController(AssistantMode.ACTIVE) { mode, _ -> seen.add(mode) }
        m.onTranscript("nishu sleep"); m.onTranscript("random"); m.onTranscript("hey nishu")
        assertEquals(listOf(AssistantMode.SLEEP, AssistantMode.ACTIVE), seen)
    }
}
