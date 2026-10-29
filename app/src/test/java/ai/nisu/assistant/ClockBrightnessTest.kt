package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ClockBrightnessTest {
    // 2026-10-22 23:39 UTC
    private val t = 1_792_712_340_000L

    @Test fun timeIsInThePhonesZone() {
        val d = Clock.facts(t, ZoneId.of("Asia/Dhaka"))
        assertEquals("05:39", d.time24); assertEquals("5:39 AM", d.time12); assertEquals("UTC+06:00", d.offset)
        assertEquals("Friday, 23 October 2026", d.date)
        val ny = Clock.facts(t, ZoneId.of("America/New_York"))
        assertEquals("19:39", ny.time24); assertEquals("Thursday, 22 October 2026", ny.date)
    }

    @Test fun utcOffsetIsWritten() = assertEquals("UTC+00:00", Clock.facts(t, ZoneId.of("UTC")).offset)

    @Test fun reduceGoesDownAndIncreaseGoesUp() {
        val down = PhoneControl.brightnessTarget(128, "down", 15.0)!!
        val up = PhoneControl.brightnessTarget(128, "up", 15.0)!!
        assertTrue(down < 128); assertTrue(up > 128)
        assertEquals(128 - 38, down); assertEquals(128 + 38, up)
    }

    @Test fun limitsAndSynonyms() {
        assertEquals(1, PhoneControl.brightnessTarget(10, "reduce", 30.0))
        assertEquals(255, PhoneControl.brightnessTarget(250, "increase", 30.0))
        assertEquals(255, PhoneControl.brightnessTarget(10, "max", 15.0))
        assertEquals(1, PhoneControl.brightnessTarget(200, "min", 15.0))
        assertNull(PhoneControl.brightnessTarget(100, "banana", 15.0))
    }

    @Test fun unknownCurrentLevelStartsInTheMiddle() = assertEquals(128 - 38, PhoneControl.brightnessTarget(-1, "down", 15.0))
}
