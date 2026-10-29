package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneTogglesTest {
    @Test fun switchNamesAreUnderstood() {
        assertEquals("flashlight", PhoneToggles.canonical("flash light"))
        assertEquals("flashlight", PhoneToggles.canonical("torch"))
        assertEquals("flashlight", PhoneToggles.canonical("টর্চ"))
        assertEquals("location", PhoneToggles.canonical("Location"))
        assertEquals("location", PhoneToggles.canonical("gps"))
        assertEquals("dnd", PhoneToggles.canonical("do not disturb"))
        assertEquals("dnd", PhoneToggles.canonical("DND"))
        assertEquals("ringer", PhoneToggles.canonical("silent mode"))
        assertEquals("ringer", PhoneToggles.canonical("general mode"))
        assertEquals("ambient_display", PhoneToggles.canonical("ambient display"))
        assertEquals("ambient_display", PhoneToggles.canonical("always on display"))
        assertEquals("wifi", PhoneToggles.canonical("wi-fi"))
        assertEquals("bluetooth", PhoneToggles.canonical("bluetooth"))
        assertEquals("mobile_data", PhoneToggles.canonical("mobile data"))
        assertEquals("airplane", PhoneToggles.canonical("airplane mode"))
        assertEquals("hotspot", PhoneToggles.canonical("hotspot"))
        assertEquals("auto_rotate", PhoneToggles.canonical("auto rotate"))
        assertEquals("battery_saver", PhoneToggles.canonical("battery saver"))
        assertEquals("dark_mode", PhoneToggles.canonical("dark mode"))
        assertNull(PhoneToggles.canonical("banana"))
    }

    @Test fun statesAreUnderstood() {
        assertEquals("on", PhoneToggles.wantedState("ON"))
        assertEquals("on", PhoneToggles.wantedState("chalu"))
        assertEquals("off", PhoneToggles.wantedState("bondho"))
        assertEquals("off", PhoneToggles.wantedState("false"))
        assertEquals("normal", PhoneToggles.wantedState("general"))
        assertEquals("silent", PhoneToggles.wantedState("Silent"))
        assertEquals("vibrate", PhoneToggles.wantedState("vibrate"))
        assertNull(PhoneToggles.wantedState("maybe"))
    }
}
