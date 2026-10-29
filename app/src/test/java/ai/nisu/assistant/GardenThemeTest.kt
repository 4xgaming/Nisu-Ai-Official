package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GardenThemeTest {
    @Test fun showsOnlyInNisuAiModes() {
        assertTrue(GardenRules.showing(true, PersonaMode.CARE, false))
        assertTrue(GardenRules.showing(true, PersonaMode.FRIEND, false))
        assertTrue(GardenRules.showing(true, PersonaMode.BOSS, false))
        assertFalse(GardenRules.showing(true, PersonaMode.JARVIS, false))
        assertFalse(GardenRules.showing(true, PersonaMode.VENOM, false))
    }

    @Test fun neverShowsWhenNotSelectedOrWithCustomPhotos() {
        assertFalse(GardenRules.showing(false, PersonaMode.CARE, false))
        assertFalse(GardenRules.showing(true, PersonaMode.CARE, true))
    }

    @Test fun everyModeIsEitherNisuAiOrJarvisOrVenom() {
        for (m in PersonaMode.ALL) assertEquals(m != PersonaMode.JARVIS && m != PersonaMode.VENOM, GardenRules.nisuMode(m))
    }

    private fun mesh(s: Float, p: Float): FloatArray = FloatArray(GardenRules.meshSize()).also { GardenRules.warpMesh(it, s, p) }

    @Test fun bottomOfThePictureNeverMoves() {
        val m = mesh(1f, 0.7f)
        val last = GardenRules.ROWS * (GardenRules.COLS + 1)
        for (i in 0..GardenRules.COLS) {
            assertEquals(i * GardenRules.IMG_W / GardenRules.COLS, m[2 * (last + i)], 0.01f)
            assertEquals(GardenRules.IMG_H, m[2 * (last + i) + 1], 0.01f)
        }
    }

    @Test fun movementStaysSmallSoNoEdgeShows() {
        var worst = 0f
        for (step in 0 until 200) for (s in listOf(0f, 0.5f, 1f)) {
            val m = mesh(s, step * 0.0314f)
            var n = 0
            for (j in 0..GardenRules.ROWS) for (i in 0..GardenRules.COLS) {
                val dx = m[n] - i * GardenRules.IMG_W / GardenRules.COLS
                val dy = m[n + 1] - j * GardenRules.IMG_H / GardenRules.ROWS
                worst = maxOf(worst, kotlin.math.abs(dx), kotlin.math.abs(dy)); n += 2
            }
        }
        assertTrue("worst move " + worst, worst < 28f)   // the picture is shown 4.5 % larger, so at least 21 px of margin on each side
    }

    @Test fun herHeadMovesMoreWhenShesTalking() {
        fun headMove(s: Float): Float {
            var best = 0f
            for (step in 0 until 200) {
                val m = mesh(s, step * 0.0314f)
                val idx = 2 * (4 * (GardenRules.COLS + 1) + GardenRules.COLS / 2)   // a point on the face
                val y0 = 4 * GardenRules.IMG_H / GardenRules.ROWS
                best = maxOf(best, kotlin.math.abs(m[idx + 1] - y0))
            }
            return best
        }
        assertTrue(headMove(1f) > headMove(0f) + 4f)
    }

    @Test fun cropMappingMatchesContentScaleCrop() {
        // a screen with the same shape as the picture: no cropping, the corners map to the corners
        val w = GardenRules.IMG_W; val h = GardenRules.IMG_H
        assertEquals(0f, GardenRules.mapX(0f, w, h), 0.01f)
        assertEquals(w, GardenRules.mapX(GardenRules.IMG_W, w, h), 0.01f)
        assertEquals(h, GardenRules.mapY(GardenRules.IMG_H, w, h), 0.01f)
        // a taller / narrower phone (1080 x 2400): the picture fills the height and is cut left and right; the picture's centre stays the screen's centre
        assertEquals(1080f / 2f, GardenRules.mapX(GardenRules.IMG_W / 2f, 1080f, 2400f), 0.01f)
        assertEquals(2400f / 2f, GardenRules.mapY(GardenRules.IMG_H / 2f, 1080f, 2400f), 0.01f)
        assertTrue(GardenRules.scale(1080f, 2400f) >= 1080f / GardenRules.IMG_W)
        assertTrue(GardenRules.mapX(0f, 1080f, 2400f) <= 0f)
    }

    @Test fun silentMeansNoBodyMotion() {
        for (y in listOf(0f, 300f, 520f, 700f, 1000f, 1672f)) {
            assertEquals(0f, GardenRules.shiftY(y, 0f, 1.3f), 0f)
            assertEquals(0f, GardenRules.shiftX(y, 0f, 1.3f), 0f)
        }
    }

    @Test fun loudVoiceMovesHeadMoreThanFeetAndStaysSmall() {
        assertTrue(GardenRules.shiftY(300f, 1f, 0f) > GardenRules.shiftY(1400f, 1f, 0f))
        assertEquals(0f, GardenRules.shiftY(1500f, 1f, 2f), 0.001f)      // legs and floor do not bob
        assertEquals(0f, GardenRules.shiftX(1600f, 1f, 2f), 0.001f)      // feet do not slide
        for (y in 0..1672 step 40) for (ph in listOf(0f, 1f, 2f, 3f)) {
            assertTrue(Math.abs(GardenRules.shiftY(y.toFloat(), 1f, ph)) < 12f)
            assertTrue(Math.abs(GardenRules.shiftX(y.toFloat(), 1f, ph)) < 16f)
        }
    }

    @Test fun shiftCurveIsSmoothSoStripsNeverTear() {
        var prev = GardenRules.shiftY(0f, 1f, 0.7f)
        for (y in 1..1672) { val v = GardenRules.shiftY(y.toFloat(), 1f, 0.7f); assertTrue(Math.abs(v - prev) < 0.2f); prev = v }
    }

    @Test fun chestAndShouldersStayStillWhileSheTalks() {
        for (y in 600..800 step 20) for (ph in listOf(0f, 1f, 2f, 3f, 4f)) {
            assertEquals(0f, GardenRules.shiftY(y.toFloat(), 1f, ph), 0f)        // no lift of the chest
            assertEquals(0f, GardenRules.shiftX(y.toFloat(), 1f, ph), 0f)        // no sway of the chest
        }
        assertTrue(GardenRules.shiftY(300f, 1f, 0f) > 5f)                         // the head still nods
        val a = FloatArray(GardenRules.meshSize()); val b = FloatArray(GardenRules.meshSize())
        GardenRules.warpMesh(a, 0f, 0.7f); GardenRules.warpMesh(b, 1f, 0.7f)
        val row = 20 * (GardenRules.COLS + 1)                                     // a row of the grid in the chest (y about 1000)
        for (i in 0..GardenRules.COLS) assertEquals(a[2 * (row + i) + 1], b[2 * (row + i) + 1], 0.001f)   // the voice does not move the chest in the mesh either
    }

    @Test fun faceSitsInsideThePictureAndFollowsTheHead() {
        val pts = listOf(GardenRules.EYE_L, GardenRules.EYE_R, GardenRules.MOUTH, GardenRules.CHEEK_L, GardenRules.CHEEK_R, GardenRules.FACE)
        for (q in pts) assertTrue(q[0] in 0f..GardenRules.IMG_W && q[1] in 0f..GardenRules.IMG_H)
        assertTrue(GardenRules.EYE_L[0] < GardenRules.FACE[0] && GardenRules.EYE_R[0] > GardenRules.FACE[0])
        assertTrue(GardenRules.MOUTH[1] > GardenRules.EYE_L[1] && GardenRules.EYE_L[1] > GardenRules.EYE_R[1])   // mouth below the eyes; her head is tilted: the left eye is lower
        val out = FloatArray(2)
        GardenRules.headPoint(out, 485f, 338f, 0f, 1.3f)
        assertEquals(485f, out[0], 0f); assertEquals(338f, out[1], 0f)                                    // silent: exactly where the picture is
        GardenRules.headPoint(out, 485f, 338f, 1f, 0.5f)
        assertEquals(485f + GardenRules.shiftX(338f, 1f, 0.5f), out[0], 0.0001f)                           // talking: the same shift as the picture strips
        assertEquals(338f + GardenRules.shiftY(338f, 1f, 0.5f), out[1], 0.0001f)
    }
}
