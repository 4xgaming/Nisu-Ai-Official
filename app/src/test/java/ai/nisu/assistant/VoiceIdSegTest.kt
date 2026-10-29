package ai.nisu.assistant

import org.junit.Assert.*
import org.junit.Test

/** v3.49: how speech is cut into pieces for the voice check (short commands must not be lost or leave "someone is speaking" open). */
class VoiceIdSegTest {
    private val c = DoubleArray(19)
    private class Rec { var started = 0; var drops = 0; val utts = ArrayList<Pair<Int, Boolean>>() }

    private fun seg(r: Rec) = VoiceId.Seg({ r.started++ }, { f, short -> r.utts.add(f.size to short) }, { r.drops++ })
    private fun VoiceId.Seg.quiet(n: Int) { repeat(n) { frame(c, 0.0, false) } }
    private fun VoiceId.Seg.talk(n: Int) { repeat(n) { frame(c, 10.0, false) } }

    @Test fun normalUtteranceIsJudged() {
        val r = Rec(); val s = seg(r)
        s.quiet(80); s.talk(200); s.quiet(60)
        assertEquals(listOf(200 to false), r.utts)
    }

    @Test fun shortCommandIsJudgedAloneAfterAPause() {
        val r = Rec(); val s = seg(r)
        s.quiet(80); s.talk(70); s.quiet(140)
        assertEquals(listOf(70 to true), r.utts)
        assertTrue(r.started > 0)
    }

    @Test fun twoShortPiecesAreJoined() {
        val r = Rec(); val s = seg(r)
        s.quiet(80); s.talk(70); s.quiet(60); s.talk(70); s.quiet(60)
        assertEquals(listOf(140 to false), r.utts)
    }

    @Test fun clickIsDroppedAndNeverStartsAJudgement() {
        val r = Rec(); val s = seg(r)
        s.quiet(80); s.talk(20); s.quiet(80)
        assertTrue(r.utts.isEmpty())
        assertEquals(0, r.started)
        assertEquals(1, r.drops)
    }

    @Test fun herAnswerFlushesTheShortSpeechThatCameBefore() {
        val r = Rec(); val s = seg(r)
        s.quiet(80); s.talk(70); s.quiet(55)
        assertTrue(r.utts.isEmpty())
        s.frame(c, 10.0, true)
        assertEquals(listOf(70 to true), r.utts)
    }

    @Test fun speechCutByHerAnswerDoesNotLeaveSpeakingOpen() {
        val r = Rec(); val s = seg(r)
        s.quiet(80); s.talk(40)
        s.frame(c, 10.0, true)
        assertTrue(r.utts.isEmpty())
        assertEquals(1, r.drops)
    }
}
