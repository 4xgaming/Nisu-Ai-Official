package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceSegmenterTest {
    private fun frame(amp: Int): ByteArray {
        val b = ByteArray(UtteranceSegmenter.FRAME_BYTES)
        var i = 0; var k = 0
        while (i < b.size) { val v = if (k % 2 == 0) amp else -amp; b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte(); i += 2; k++ }
        return b
    }
    private fun run(seg: UtteranceSegmenter, loudFrames: Int, quietFrames: Int = 40): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        repeat(25) { seg.feed(frame(20))?.let { out.add(it) } }
        repeat(loudFrames) { seg.feed(frame(4000))?.let { out.add(it) } }
        repeat(quietFrames) { seg.feed(frame(20))?.let { out.add(it) } }
        return out
    }

    @Test fun phraseLengthSoundIsReturned() {
        val r = run(UtteranceSegmenter(), 50)
        assertEquals(1, r.size)
        assertTrue(r[0].size >= 50 * UtteranceSegmenter.FRAME_BYTES)
    }
    @Test fun shortNoiseIsDropped() { assertTrue(run(UtteranceSegmenter(), 6).isEmpty()) }
    @Test fun longSpeechIsDropped() { assertTrue(run(UtteranceSegmenter(), 400).isEmpty()) }
    @Test fun silenceGivesNothing() { assertTrue(run(UtteranceSegmenter(), 0).isEmpty()) }
    @Test fun worksTwiceInARow() {
        val s = UtteranceSegmenter()
        assertEquals(1, run(s, 50).size)
        assertEquals(1, run(s, 60).size)
    }
    @Test fun afterLongSpeechTheNextPhraseStillWorks() {
        val s = UtteranceSegmenter()
        assertTrue(run(s, 400).isEmpty())
        assertNotNull(run(s, 50).firstOrNull())
    }
}
