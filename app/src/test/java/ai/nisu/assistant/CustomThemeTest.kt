package ai.nisu.assistant

import ai.nisu.assistant.CustomThemeRules.Cfg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomThemeTest {
    private val five = listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg")

    @Test fun atMostTenPhotosAndRoomCountsDown() {
        assertEquals(10, CustomThemeRules.MAX)
        assertEquals(10, CustomThemeRules.roomLeft(0))
        assertEquals(3, CustomThemeRules.roomLeft(7))
        assertEquals(0, CustomThemeRules.roomLeft(10))
        assertEquals(0, CustomThemeRules.roomLeft(12))
        val twelve = (1..12).map { "p$it.jpg" }
        assertEquals(10, CustomThemeRules.decode(CustomThemeRules.encode(twelve)).size)
    }

    @Test fun savedNamesSurviveButUnsafeNamesAreDropped() {
        assertEquals(five, CustomThemeRules.decode(CustomThemeRules.encode(five)))
        assertEquals(listOf("ok1.jpg"), CustomThemeRules.decode("../secret.jpg,/etc/passwd,ok1.jpg,ok1.jpg,x.png, ,a b.jpg"))
        assertTrue(CustomThemeRules.decode("").isEmpty())
    }

    @Test fun defaultShowsNoCustomPhotoEvenWithLoopOn() {
        val c = Cfg(on = false, loop = true, files = five)
        assertFalse(CustomThemeRules.usable(c))
        assertFalse(CustomThemeRules.looping(c))
        assertNull(CustomThemeRules.shown(c, 2))
        assertNull(CustomThemeRules.shown(Cfg(on = true, files = emptyList()), 0))
    }

    @Test fun loopOffShowsTheSelectedPhotoAndLoopOnFollowsTheClock() {
        val off = Cfg(on = true, loop = false, files = five, main = 3)
        assertEquals("d.jpg", CustomThemeRules.shown(off, 0))
        assertEquals("d.jpg", CustomThemeRules.shown(off, 4))
        val on = off.copy(loop = true)
        assertTrue(CustomThemeRules.looping(on))
        assertEquals("a.jpg", CustomThemeRules.shown(on, 0))
        assertEquals("c.jpg", CustomThemeRules.shown(on, 2))
        assertEquals("e.jpg", CustomThemeRules.shown(on, 99))   // a stale index never crashes
    }

    @Test fun aLoopNeedsTwoPhotos() {
        val one = Cfg(on = true, loop = true, files = listOf("a.jpg"), main = 0)
        assertFalse(CustomThemeRules.looping(one))
        assertEquals("a.jpg", CustomThemeRules.shown(one, 0))
    }

    @Test fun theLoopStartsAgainFromTheFirstPhotoAfterTheLast() {
        var i = 0
        val seen = ArrayList<Int>()
        repeat(7) { seen.add(i); i = CustomThemeRules.nextIndex(i, 3) }
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), seen)
        assertEquals(0, CustomThemeRules.nextIndex(0, 0))
    }

    @Test fun secondsStayBetweenThreeAndOneTwenty() {
        assertEquals(3, CustomThemeRules.clampSecs(0))
        assertEquals(8, CustomThemeRules.clampSecs(8))
        assertEquals(9999, CustomThemeRules.clampSecs(9999))   // v3.134: spoken times may be longer than the 120 s slider
        assertEquals(CustomThemeRules.LIMIT_SECS, CustomThemeRules.clampSecs(Int.MAX_VALUE))
        assertTrue(CustomThemeRules.DEFAULT_SECS in CustomThemeRules.MIN_SECS..CustomThemeRules.MAX_SECS)
    }

    @Test fun removingAPhotoKeepsTheSelectionSensible() {
        assertEquals(2, CustomThemeRules.mainAfterRemove(main = 3, removed = 0, newCount = 4))   // an earlier photo went: same photo, new number
        assertEquals(3, CustomThemeRules.mainAfterRemove(main = 3, removed = 4, newCount = 4))   // a later photo went: unchanged
        assertEquals(3, CustomThemeRules.mainAfterRemove(main = 4, removed = 4, newCount = 4))   // the selected last one went: clamp to the new last
        assertEquals(0, CustomThemeRules.mainAfterRemove(main = 0, removed = 0, newCount = 0))
    }

    @Test fun theBrandNameIsUntouched() {
        assertEquals("NISU AI", Brand.APP_NAME)
        assertTrue(Brand.integrityOk())
    }
}
