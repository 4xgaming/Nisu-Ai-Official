package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInfoTest {
    @Test fun ramSizeOnTheBoxIsRoundedUpFromWhatAndroidReports() {
        val gb = 1073741824L
        assertEquals(8, DeviceInfo.marketingRamGb((7.4 * gb).toLong()))
        assertEquals(6, DeviceInfo.marketingRamGb((5.5 * gb).toLong()))
        assertEquals(4, DeviceInfo.marketingRamGb((3.7 * gb).toLong()))
        assertEquals(12, DeviceInfo.marketingRamGb((11.2 * gb).toLong()))
        assertEquals(8, DeviceInfo.marketingRamGb(8 * gb))
    }

    @Test fun storageSizeOnTheBox() {
        val gb = 1073741824L
        assertEquals(128, DeviceInfo.marketingStorageGb((108.5 * gb).toLong()))
        assertEquals(256, DeviceInfo.marketingStorageGb((230.0 * gb).toLong()))
    }

    @Test fun meminfoIsParsedInKb() {
        val m = DeviceInfo.parseMeminfo("MemTotal:        7654321 kB\nMemAvailable:    3000000 kB\nSwapTotal:       2097148 kB\nbad line\n")
        assertEquals(7654321L, m["MemTotal"])
        assertEquals(3000000L, m["MemAvailable"])
        assertEquals(2097148L, m["SwapTotal"])
        assertNull(m["bad line"])
    }

    @Test fun chargingWattsFromMicroampsAndFromMilliamps() {
        assertEquals(12.6, DeviceInfo.chargeWatts(-3_000_000L, 4200), 0.05)   // 3 A at 4.2 V, sign does not matter
        assertEquals(12.6, DeviceInfo.chargeWatts(3000L, 4200), 0.05)         // the same, a maker that reports milliamps
        assertEquals(0.0, DeviceInfo.chargeWatts(1_000_000L, 0), 0.0)
        assertEquals(3000L, DeviceInfo.currentMa(-3_000_000L))
        assertEquals(850L, DeviceInfo.currentMa(850L))
    }

    @Test fun chargingSpeedNames() {
        assertEquals("not charging", DeviceInfo.chargeClass(0.0))
        assertTrue(DeviceInfo.chargeClass(4.0).startsWith("very slow"))
        assertTrue(DeviceInfo.chargeClass(7.5).startsWith("normal"))
        assertTrue(DeviceInfo.chargeClass(15.0).startsWith("fast"))
        assertTrue(DeviceInfo.chargeClass(25.0).startsWith("very fast"))
        assertTrue(DeviceInfo.chargeClass(45.0).startsWith("super fast"))
        assertTrue(DeviceInfo.chargeClass(100.0).startsWith("ultra fast"))
    }

    @Test fun wifiBandChannelAndSignal() {
        assertEquals("2.4 GHz", DeviceInfo.wifiBand(2437)); assertEquals(6, DeviceInfo.wifiChannel(2437))
        assertEquals(14, DeviceInfo.wifiChannel(2484))
        assertEquals("5 GHz", DeviceInfo.wifiBand(5180)); assertEquals(36, DeviceInfo.wifiChannel(5180))
        assertEquals("6 GHz", DeviceInfo.wifiBand(5975)); assertEquals(5, DeviceInfo.wifiChannel(5975))
        assertEquals("excellent", DeviceInfo.signalQuality(-45)); assertEquals("fair", DeviceInfo.signalQuality(-65)); assertEquals("very weak", DeviceInfo.signalQuality(-90))
        assertEquals("Wi-Fi 6 (802.11ax)", DeviceInfo.wifiStandardName(6))
        assertEquals("192.168.1.5", DeviceInfo.ipText(0x0501A8C0))
    }

    @Test fun cpuLoadFromTwoProcStatSamples() {
        val a = DeviceInfo.parseCpuStat("cpu  100 0 100 700 100 0 0 0 0 0")!!
        val b = DeviceInfo.parseCpuStat("cpu  200 0 200 800 100 0 0 0 0 0")!!
        assertEquals(66, DeviceInfo.cpuPercent(a, b))    // 300 more ticks, 100 of them idle: 200 busy
        assertNull(DeviceInfo.parseCpuStat("garbage"))
        assertEquals(0, DeviceInfo.cpuPercent(a, a))
    }

    @Test fun chipsetAndCoreNames() {
        assertEquals("Snapdragon 8 Gen 2", DeviceInfo.socName("sm8550"))
        assertNull(DeviceInfo.socName("unknown-code"))
        assertEquals("Cortex-A55", DeviceInfo.armCore("0xd05"))
        assertNull(DeviceInfo.armCore("0xffff"))
    }

    @Test fun romIsReadFromTheMakersProperties() {
        assertEquals("Samsung One UI" to "6.1.0", DeviceInfo.romName(mapOf("ro.build.version.oneui" to "60100")))
        assertEquals("Xiaomi HyperOS" to "OS1.0", DeviceInfo.romName(mapOf("ro.mi.os.version.name" to "OS1.0")))
        assertEquals("Xiaomi MIUI", DeviceInfo.romName(mapOf("ro.miui.ui.version.name" to "V14")).first)
        assertEquals("LineageOS", DeviceInfo.romName(mapOf("ro.lineage.version" to "21.0")).first)
        assertTrue(DeviceInfo.romName(emptyMap()).first.startsWith("Android"))
    }

    @Test fun getpropOutputIsParsed() {
        val p = DeviceInfo.parseProps("[ro.board.platform]: [kalama]\n[ro.build.display.id]: [UP1A.231005.007]\n[empty.one]: []\n")
        assertEquals("kalama", p["ro.board.platform"]); assertEquals("UP1A.231005.007", p["ro.build.display.id"]); assertEquals("", p["empty.one"])
    }

    @Test fun topRamAppsAreReadFromDumpsysMeminfo() {
        val arr = DeviceInfo.parseTopPss("Total PSS by process:\n    512,000K: com.android.systemui (pid 1 / activities)\n    204,800K: com.whatsapp (pid 2)\n", 8)
        assertEquals(2, arr.length())
        assertEquals("com.android.systemui", arr.getJSONObject(0).getString("app")); assertEquals(500L, arr.getJSONObject(0).getLong("ram_mb"))
    }

    @Test fun sectionNamesAndSpeechAliases() {
        assertEquals("battery", DeviceInfo.canonical("Charging")); assertEquals("memory", DeviceInfo.canonical("RAM"))
        assertEquals("device", DeviceInfo.canonical("rom")); assertEquals("all", DeviceInfo.canonical("everything"))
        assertEquals("summary", DeviceInfo.canonical("")); assertEquals("summary", DeviceInfo.canonical("something odd"))
        assertEquals("wifi", DeviceInfo.canonical("wi-fi")); assertEquals("bluetooth", DeviceInfo.canonical("bt"))
    }

    @Test fun uptimeText() {
        assertEquals("5 min", DeviceInfo.uptimeText(5 * 60_000L))
        assertEquals("1 d 2 h 3 min", DeviceInfo.uptimeText(((26 * 60 + 3) * 60_000L)))
    }

    @Test fun theToolIsTheNameTheModelCalls() {
        assertEquals("device_info", DeviceInfo.TOOL)
        assertTrue(DeviceInfo.NOTE.contains("device_info")); assertTrue(DeviceInfo.TYPED_NOTE.contains("[[DEVICE:"))
    }
}
