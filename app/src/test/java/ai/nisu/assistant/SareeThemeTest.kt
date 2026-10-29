package ai.nisu.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** v3.101: the Dusk Saree theme shows only in the NISU AI modes. */
class SareeThemeTest {
    @Test fun showsOnlyInNisuAiModes() {
        assertTrue(SareeRules.showing(true, PersonaMode.CARE, false))
        assertTrue(SareeRules.showing(true, PersonaMode.FRIEND, false))
        assertTrue(SareeRules.showing(true, PersonaMode.BOSS, false))
        assertFalse(SareeRules.showing(true, PersonaMode.JARVIS, false))
        assertFalse(SareeRules.showing(true, PersonaMode.VENOM, false))
    }

    @Test fun neverShowsWhenNotSelectedOrWithCustomPhotos() {
        assertFalse(SareeRules.showing(false, PersonaMode.CARE, false))
        assertFalse(SareeRules.showing(true, PersonaMode.CARE, true))
    }
}

/** v3.102: the talking mesh of the Dusk Saree theme. */
class SareeTalkTest {
    private fun mesh(s: Float, p: Float): FloatArray = FloatArray(SareeRules.meshSize()).also { SareeRules.warpMesh(it, s, p) }

    @Test fun bottomOfThePictureNeverMoves() {
        val m = mesh(1f, 0.7f)
        val last = SareeRules.ROWS * (SareeRules.COLS + 1)
        for (i in 0..SareeRules.COLS) {
            org.junit.Assert.assertEquals(i * SareeRules.IMG_W / SareeRules.COLS, m[2 * (last + i)], 0.01f)
            org.junit.Assert.assertEquals(SareeRules.IMG_H, m[2 * (last + i) + 1], 0.01f)
        }
    }

    @Test fun movementStaysSmallSoNoEdgeShows() {
        var worst = 0f
        for (step in 0 until 200) for (s in listOf(0f, 0.5f, 1f)) {
            val m = mesh(s, step * 0.0314f)
            var n = 0
            for (j in 0..SareeRules.ROWS) for (i in 0..SareeRules.COLS) {
                val dx = m[n] - i * SareeRules.IMG_W / SareeRules.COLS
                val dy = m[n + 1] - j * SareeRules.IMG_H / SareeRules.ROWS
                worst = maxOf(worst, kotlin.math.abs(dx), kotlin.math.abs(dy)); n += 2
            }
        }
        assertTrue("worst move " + worst, worst < 28f)   // the picture is shown 6 % larger: 28 px of margin on each side
    }

    @Test fun talkingMovesMoreThanSilence() {
        fun spread(s: Float): Float { var w = 0f; for (step in 0 until 100) { val m = mesh(s, step * 0.0628f); w = maxOf(w, kotlin.math.abs(m[2 * (5 * (SareeRules.COLS + 1) + 10) + 1] - 5 * SareeRules.IMG_H / SareeRules.ROWS)) }; return w }
        assertTrue(spread(1f) > spread(0f))
    }
}

/** v3.103: the face of the Dusk Saree theme: blink and mood. */
class SareeFaceTest {
    @Test fun blinkCurveClosesAndOpens() {
        org.junit.Assert.assertEquals(0f, SareeRules.blinkCurve(0f), 0.001f)
        org.junit.Assert.assertEquals(1f, SareeRules.blinkCurve(0.4f), 0.001f)
        org.junit.Assert.assertEquals(0f, SareeRules.blinkCurve(1f), 0.001f)
        for (i in 0..100) assertTrue(SareeRules.blinkCurve(i / 100f) in 0f..1f)
    }

    @Test fun blinksLikeAPerson() {
        val c = SareeRules.BlinkClock(7L)
        var blinks = 0; var wasClosed = false; var longest = 0f; var run = 0f
        val dt = 0.016f
        for (i in 0 until (60f / dt).toInt()) {
            val v = c.step(dt, 4.2f, 1f)
            assertTrue(v in 0f..1f)
            val closed = v > 0.6f
            if (closed && !wasClosed) blinks++
            run = if (v > 0.05f) run + dt else 0f; longest = maxOf(longest, run)
            wasClosed = closed
        }
        assertTrue("blinks in 60 s: " + blinks, blinks in 8..30)
        assertTrue("longest blink " + longest, longest < 0.5f)
    }

    @Test fun everyEmotionHasASafeMood() {
        val names = listOf("happy", "excited", "calm", "sad", "love", "surprised", "angry", "jealous", "sulky", "playful", "worried", "shy", "sleepy", "proud", "curious", "bored", "nervous", "disappointed", "grateful", "lonely", "confused", "scared", "annoyed", "flirty", "guilty", "relieved", "hopeful", "mischievous", "overwhelmed", "nostalgic", "heartbroken", "nonsense", "")
        for (n in names) {
            val m = SareeRules.mood(n)
            assertTrue(m.blush in 0f..1f && m.droop in 0f..0.5f && m.tintA in 0f..0.15f)
            assertTrue(m.tilt in -1.5f..1.5f && m.dip in -6f..9f)
            assertTrue(m.every in 1f..12f && m.speed in 0.3f..2f)
            org.junit.Assert.assertEquals(SareeRules.MOOD_N, m.toArray().size)
        }
        assertTrue(SareeRules.mood("shy").blush > SareeRules.mood("calm").blush)
        assertTrue(SareeRules.mood("sleepy").droop > SareeRules.mood("happy").droop)
        assertTrue(SareeRules.mood("sad").dip > SareeRules.mood("happy").dip)
    }

    @Test fun moodFadesInsteadOfJumping() {
        val cur = SareeRules.mood("calm").toArray(); val target = SareeRules.mood("shy").toArray()
        SareeRules.ease(cur, target, 0.016f)
        assertTrue(cur[SareeRules.M_BLUSH] > 0f && cur[SareeRules.M_BLUSH] < target[SareeRules.M_BLUSH])
        for (i in 0 until 300) SareeRules.ease(cur, target, 0.016f)
        org.junit.Assert.assertEquals(target[SareeRules.M_BLUSH], cur[SareeRules.M_BLUSH], 0.01f)
    }

    @Test fun strongestMoodPlusLoudVoiceStillShowsNoEdge() {
        var worst = 0f
        val m = FloatArray(SareeRules.meshSize())
        for (step in 0 until 200) for (e in listOf("shy", "sad", "love", "excited")) {
            val md = SareeRules.mood(e)
            SareeRules.warpMesh(m, 1f, step * 0.0314f, md.dip, md.tilt)
            var n = 0
            for (j in 0..SareeRules.ROWS) for (i in 0..SareeRules.COLS) {
                worst = maxOf(worst, kotlin.math.abs(m[n] - i * SareeRules.IMG_W / SareeRules.COLS), kotlin.math.abs(m[n + 1] - j * SareeRules.IMG_H / SareeRules.ROWS)); n += 2
            }
        }
        assertTrue("worst move " + worst, worst < 28f)   // the picture is shown 6 % larger: 28 px of margin on each side
    }

    @Test fun eyelidsFollowTheHead() {
        val o = FloatArray(2)
        SareeRules.headPoint(o, SareeRules.EYE_L[0], SareeRules.EYE_L[1], 0f, 0f)
        org.junit.Assert.assertEquals(SareeRules.EYE_L[0], o[0], 1f)
        org.junit.Assert.assertEquals(SareeRules.EYE_L[1], o[1], 1f)
        SareeRules.headPoint(o, SareeRules.EYE_L[0], SareeRules.EYE_L[1], 0f, 0f, 8f, 0f)
        org.junit.Assert.assertEquals(SareeRules.EYE_L[1] + 8f, o[1], 1f)   // a lowered head carries the eye with it
    }

    @Test fun mouthOpensWithTheVoiceAndClosesInSilence() {
        assertEquals(0f, SareeRules.mouthTarget(0f, 1f), 0f)
        assertTrue(SareeRules.mouthTarget(0.2f, 0f) > 0.3f)
        assertTrue(SareeRules.mouthTarget(1f, 1f) <= 1f)
        var v = 0f
        for (i in 0 until 10) v = SareeRules.mouthStep(v, 0.8f, 0.016f)
        assertTrue(v > 0.5f)                                   // opens within a few frames: it follows each syllable
        for (i in 0 until 60) v = SareeRules.mouthStep(v, 0f, 0.016f)
        assertEquals(0f, v, 0f)                                // and closes fully
    }

    @Test fun paintedMouthOnlyShowsWhenItDiffersFromThePicture() {
        assertEquals(0f, SareeRules.mouthShow(0f, SareeRules.NATURAL_SMILE, 1f), 0f)       // calm and silent: the picture's own mouth
        assertTrue(SareeRules.mouthShow(0.3f, SareeRules.NATURAL_SMILE, 1f) > 0.9f)       // talking
        assertTrue(SareeRules.mouthShow(0f, -0.6f, 0.9f) > 0.9f)                          // sad
        assertTrue(SareeRules.mouthShow(0f, 0.9f, 1f) > 0.5f)                             // a broad smile
    }

    @Test fun moodsHaveFacesThatDiffer() {
        assertTrue(SareeRules.mood("happy").smile > SareeRules.NATURAL_SMILE)
        assertTrue(SareeRules.mood("sad").smile < 0f && SareeRules.mood("sad").slant < 0f)
        assertTrue(SareeRules.mood("angry").smile < 0f && SareeRules.mood("angry").slant > 0.5f)
        assertTrue(SareeRules.mood("surprised").open > 0.4f && SareeRules.mood("surprised").wide < 0.7f)
        assertTrue(SareeRules.mood("happy").squint > 0f)
        assertEquals(SareeRules.NATURAL_SMILE, SareeRules.mood("calm").smile, 0f)
        for (n in listOf("happy", "excited", "love", "shy", "flirty", "playful", "sad", "angry", "annoyed", "surprised", "scared", "sleepy", "bored", "curious", "unknown")) {
            val m = SareeRules.mood(n)
            assertTrue(m.smile in -1f..1f && m.open in 0f..1f && m.wide in 0.5f..1.1f && m.squint in 0f..1f && m.slant in -1f..1f)
        }
    }
}
