package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** v3.131: the Jasmine Terrace theme shows only in the NISU AI modes. */
class LaceThemeTest {
    @Test fun showsOnlyInNisuAiModes() {
        assertTrue(LaceRules.showing(true, PersonaMode.CARE, false))
        assertTrue(LaceRules.showing(true, PersonaMode.FRIEND, false))
        assertTrue(LaceRules.showing(true, PersonaMode.BOSS, false))
        assertFalse(LaceRules.showing(true, PersonaMode.JARVIS, false))
        assertFalse(LaceRules.showing(true, PersonaMode.VENOM, false))
    }

    @Test fun neverShowsWhenNotSelectedOrWithCustomPhotos() {
        assertFalse(LaceRules.showing(false, PersonaMode.CARE, false))
        assertFalse(LaceRules.showing(true, PersonaMode.CARE, true))
    }
}

/** v3.131: the bendable picture of the Jasmine Terrace theme. */
class LaceMeshTest {
    private val twoPi = (2.0 * Math.PI).toFloat()
    private fun mesh(p: Float): FloatArray = FloatArray(LaceRules.meshSize()).also { LaceRules.warpMesh(it, p) }
    private fun at(m: FloatArray, i: Int, j: Int): Pair<Float, Float> { val n = 2 * (j * (LaceRules.COLS + 1) + i); return m[n] to m[n + 1] }

    @Test fun movementStaysSmallSoNoEdgeShows() {
        var worst = 0f
        for (step in 0 until 240) {
            val m = mesh(step * twoPi / 240f)
            for (j in 0..LaceRules.ROWS) for (i in 0..LaceRules.COLS) {
                val (x, y) = at(m, i, j)
                worst = maxOf(worst, abs(x - i * LaceRules.IMG_W / LaceRules.COLS), abs(y - j * LaceRules.IMG_H / LaceRules.ROWS))
            }
        }
        assertTrue("worst move " + worst, worst < 28f)   // the picture is shown at least 7.5 % larger: more than 28 px of margin on each side
    }

    @Test fun theLoopIsSeamless() {
        val a = mesh(0f); val b = mesh(twoPi)
        for (n in a.indices) assertEquals(a[n], b[n], 0.01f)
    }

    @Test fun everyPointIsFinite() {
        for (step in 0 until 50) for (v in mesh(step * 0.13f)) assertTrue(v.isFinite())
    }

    @Test fun theCurtainMovesMoreThanTheChest() {
        fun spread(i: Int, j: Int): Float { var lo = 1e9f; var hi = -1e9f; for (s in 0 until 240) { val x = at(mesh(s * twoPi / 240f), i, j).first; lo = minOf(lo, x); hi = maxOf(hi, x) }; return hi - lo }
        val curtain = spread(22, 8)   // x = 939, y = 341: the lace curtain, high up
        val chest = spread(12, 14)    // x = 512, y = 597: the chest
        assertTrue("curtain $curtain chest $chest", curtain > chest + 5f)
    }

    @Test fun theBottomEdgeBarelyMoves() {
        for (s in 0 until 60) {
            val m = mesh(s * 0.1f)
            for (i in 0..LaceRules.COLS) assertEquals(LaceRules.IMG_H, at(m, i, LaceRules.ROWS).second, 3f)
        }
    }

    @Test fun eyelidsFollowTheHead() {
        // a vertex in the middle of the face is moved by exactly the head movement (nothing else touches the face)
        val pt = FloatArray(2)
        val i = 12; val j = 5
        val x0 = i * LaceRules.IMG_W / LaceRules.COLS; val y0 = j * LaceRules.IMG_H / LaceRules.ROWS
        for (s in 0 until 40) {
            val p = s * 0.157f
            val (x, y) = at(mesh(p), i, j)
            LaceRules.headPoint(pt, x0, y0, p)
            assertEquals(pt[0], x, 0.01f); assertEquals(pt[1], y, 0.01f)
        }
    }

    @Test fun eyesAreWhereTheFaceIs() {
        // both eyes lie in the head (above the neck), the skin patches are close to them
        for (e in listOf(LaceRules.EYE_L, LaceRules.EYE_R)) { assertTrue(e[1] < 300f); assertTrue(e[2] > e[3]) }
        assertTrue(abs(LaceRules.SKIN_L[0] - LaceRules.EYE_L[0]) < 30f && abs(LaceRules.SKIN_L[1] - LaceRules.EYE_L[1]) < 40f)
        assertTrue(abs(LaceRules.SKIN_R[0] - LaceRules.EYE_R[0]) < 30f && abs(LaceRules.SKIN_R[1] - LaceRules.EYE_R[1]) < 40f)
    }
}

/** v3.131: light, sparkles, petals of the Jasmine Terrace theme. */
class LaceEffectsTest {
    @Test fun flickerStaysInRangeAndLoops() {
        for (s in 0..1000) { val f = LaceRules.flicker(s / 1000f); assertTrue(f in 0.4f..1f) }
        assertEquals(LaceRules.flicker(0f), LaceRules.flicker(1f), 0.001f)
    }

    @Test fun sunBeamsLoop() {
        for (b in LaceRules.SHAFT_X.indices) {
            assertEquals(LaceRules.shaftAlpha(b, 0f), LaceRules.shaftAlpha(b, 1f), 0.001f)
            assertEquals(LaceRules.shaftDrift(b, 0f), LaceRules.shaftDrift(b, 1f), 0.01f)
            for (s in 0..100) assertTrue(LaceRules.shaftAlpha(b, s / 100f) in 0.049f..0.161f)
        }
    }

    @Test fun sparklesFlashAndLoop() {
        for (s in LaceRules.SPARKS.indices) {
            var peak = 0f
            for (k in 0..400) { val v = LaceRules.spark(s, k / 400f); assertTrue(v in 0f..1f); peak = maxOf(peak, v) }
            assertTrue(peak > 0.9f)
            assertEquals(LaceRules.spark(s, 0f), LaceRules.spark(s, 1f), 0.001f)
        }
    }

    @Test fun petalsLoopAndStayVisibleOnlyOnTheirWay() {
        val a = FloatArray(6); val b = FloatArray(6)
        for (i in 0 until LaceRules.PETALS) {
            LaceRules.petal(i, 0f, a); LaceRules.petal(i, 1f, b)
            for (n in 0 until 6) assertEquals("petal $i value $n", a[n], b[n], 0.01f)
            var seen = false
            for (s in 0..200) {
                LaceRules.petal(i, s / 200f, a)
                assertTrue(a[5] in 0f..1f); assertTrue(a[4] in 0.25f..1f); assertTrue(a[3] > 0.005f)
                if (a[5] > 0.2f) seen = true
            }
            assertTrue(seen)
        }
    }

    @Test fun lightsRise() {
        val a = FloatArray(4); val b = FloatArray(4)
        for (i in 0 until LaceRules.MOTES) {
            LaceRules.mote(i, 0.30f, a); LaceRules.mote(i, 0.31f, b)
            assertTrue(b[1] < a[1])
            assertTrue(a[3] in 0f..1f)
        }
    }
}
