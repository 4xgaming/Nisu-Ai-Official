package ai.nisu.assistant

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v3.86: DEVICE INFO. One read-only tool (voice: device_info, typed chat: [[DEVICE: section]]) that reads the phone's own live values from the
 * system every time it is called, so she can say the real battery percent, charging speed, RAM use, CPU, chipset, model, ROM, storage, Wi-Fi,
 * Bluetooth, mobile network, screen and sensors instead of guessing. It changes nothing on the phone and sends nothing anywhere.
 *
 * Honest limits (the result says so per field, she must repeat it and never invent a value): Android hides some numbers from normal apps
 * (CPU load and per-core speed on some makers, per-app RAM, the Wi-Fi name without Location). On a rooted phone with root given to NISU AI,
 * CPU load and the top RAM apps are read through root as well.
 */
object DeviceInfo {
    const val TOOL = "device_info"

    // ---------------------------------------------------------------- pure helpers (unit tested in DeviceInfoTest)

    internal fun r1(x: Double): Double = if (x.isNaN() || x.isInfinite()) 0.0 else Math.round(x * 10.0) / 10.0
    internal fun r2(x: Double): Double = if (x.isNaN() || x.isInfinite()) 0.0 else Math.round(x * 100.0) / 100.0
    internal fun gb(bytes: Long): Double = r2(bytes / 1073741824.0)
    internal fun mb(bytes: Long): Long = bytes / 1048576L

    /** The RAM size on the box: Android reports a bit less than the real chip (7.4 GB for an "8 GB" phone). */
    internal fun marketingRamGb(totalBytes: Long): Int {
        val g = totalBytes / 1073741824.0
        val sizes = intArrayOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 24, 32)
        for (s in sizes) if (s >= g - 0.05) return s
        return Math.ceil(g).toInt()
    }

    /** The storage size on the box (a 128 GB phone shows about 110 GB in the data partition). */
    internal fun marketingStorageGb(totalBytes: Long): Int {
        val g = totalBytes / 1073741824.0
        val sizes = intArrayOf(8, 16, 32, 64, 128, 256, 512, 1024, 2048)
        for (s in sizes) if (s >= g) return s
        return Math.ceil(g).toInt()
    }

    /** /proc/meminfo as key to kB. */
    internal fun parseMeminfo(text: String): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (line in text.lines()) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val v = line.substring(i + 1).trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: continue
            m[line.substring(0, i).trim()] = v
        }
        return m
    }

    /** Charging power in watts from the battery current (microamps, or milliamps on some makers) and the voltage in millivolts. */
    internal fun chargeWatts(current: Long, voltageMv: Int): Double {
        if (voltageMv <= 0) return 0.0
        val a = Math.abs(current).toDouble()
        val mA = if (a < 10_000.0) a else a / 1000.0   // under 10 000 it cannot be microamps of a charger: the maker reports milliamps
        return r1(mA * (voltageMv / 1000.0) / 1000.0)
    }

    internal fun currentMa(current: Long): Long {
        val a = Math.abs(current)
        return if (a < 10_000L) a else a / 1000L
    }

    internal fun chargeClass(watts: Double): String = when {
        watts <= 0.0 -> "not charging"
        watts < 5.0 -> "very slow (under 5 W)"
        watts < 10.0 -> "normal (5 to 10 W)"
        watts < 18.0 -> "fast (10 to 18 W)"
        watts < 33.0 -> "very fast (18 to 33 W)"
        watts < 66.0 -> "super fast (33 to 66 W)"
        else -> "ultra fast (66 W or more)"
    }

    internal fun wifiBand(mhz: Int): String = when {
        mhz in 2400..2500 -> "2.4 GHz"
        mhz in 4900..5899 -> "5 GHz"
        mhz in 5900..7200 -> "6 GHz"
        else -> "unknown"
    }

    internal fun wifiChannel(mhz: Int): Int = when {
        mhz == 2484 -> 14
        mhz in 2400..2483 -> (mhz - 2407) / 5
        mhz in 4900..5899 -> (mhz - 5000) / 5
        mhz in 5900..7200 -> (mhz - 5950) / 5
        else -> -1
    }

    internal fun signalQuality(rssi: Int): String = when {
        rssi >= -50 -> "excellent"
        rssi >= -60 -> "good"
        rssi >= -70 -> "fair"
        rssi >= -80 -> "weak"
        else -> "very weak"
    }

    internal fun wifiStandardName(s: Int): String = when (s) {
        1 -> "legacy (802.11a/b/g)"; 4 -> "Wi-Fi 4 (802.11n)"; 5 -> "Wi-Fi 5 (802.11ac)"; 6 -> "Wi-Fi 6 (802.11ax)"
        7 -> "802.11ad"; 8 -> "Wi-Fi 7 (802.11be)"; else -> "unknown"
    }

    /** First line of /proc/stat: idle (idle + iowait) and total jiffies, or null. */
    internal fun parseCpuStat(line: String): Pair<Long, Long>? {
        val t = line.trim().split(Regex("\\s+"))
        if (t.size < 5 || t[0] != "cpu") return null
        val n = t.drop(1).mapNotNull { it.toLongOrNull() }
        if (n.size < 4) return null
        val idle = n[3] + (n.getOrNull(4) ?: 0L)
        return idle to n.sum()
    }

    internal fun cpuPercent(a: Pair<Long, Long>, b: Pair<Long, Long>): Int {
        val total = b.second - a.second
        if (total <= 0L) return 0
        val idle = b.first - a.first
        return (((total - idle) * 100.0) / total).toInt().coerceIn(0, 100)
    }

    /** Qualcomm model codes to the name on the box. Only codes that are certain; everything else is reported as the raw code. */
    internal fun socName(model: String): String? = when (model.trim().uppercase(Locale.US)) {
        "SM8150" -> "Snapdragon 855"; "SM8250" -> "Snapdragon 865"; "SM8350" -> "Snapdragon 888"
        "SM8450" -> "Snapdragon 8 Gen 1"; "SM8475" -> "Snapdragon 8+ Gen 1"; "SM8550" -> "Snapdragon 8 Gen 2"
        "SM8650" -> "Snapdragon 8 Gen 3"; "SM8750" -> "Snapdragon 8 Elite"
        "SM7150" -> "Snapdragon 730"; "SM7250" -> "Snapdragon 765G"; "SM7325" -> "Snapdragon 778G"
        "SM6115" -> "Snapdragon 662"; "SM6225" -> "Snapdragon 680"; "SM6375" -> "Snapdragon 695"
        "MT6893" -> "Dimensity 1200"; "MT6877" -> "Dimensity 900"
        else -> null
    }

    internal fun armCore(part: String): String? = when (part.trim().lowercase(Locale.US)) {
        "0xd03" -> "Cortex-A53"; "0xd04" -> "Cortex-A35"; "0xd05" -> "Cortex-A55"; "0xd07" -> "Cortex-A57"
        "0xd08" -> "Cortex-A72"; "0xd09" -> "Cortex-A73"; "0xd0a" -> "Cortex-A75"; "0xd0b" -> "Cortex-A76"
        "0xd0d" -> "Cortex-A77"; "0xd41" -> "Cortex-A78"; "0xd44" -> "Cortex-X1"; "0xd46" -> "Cortex-A510"
        "0xd47" -> "Cortex-A710"; "0xd48" -> "Cortex-X2"; "0xd4d" -> "Cortex-A715"; "0xd4e" -> "Cortex-X3"
        "0xd80" -> "Cortex-A520"; "0xd81" -> "Cortex-A720"; "0xd82" -> "Cortex-X4"
        else -> null
    }

    /** `getprop` output ("[key]: [value]" per line) as a map. */
    internal fun parseProps(text: String): Map<String, String> {
        val m = HashMap<String, String>()
        val re = Regex("^\\[(.*?)]: \\[(.*)]$")
        for (line in text.lines()) re.find(line.trim())?.let { m[it.groupValues[1]] = it.groupValues[2] }
        return m
    }

    /** The maker's Android skin (the "ROM") from the system properties. Pair(name, version). */
    internal fun romName(p: Map<String, String>): Pair<String, String> {
        fun g(k: String) = p[k].orEmpty()
        return when {
            g("ro.mi.os.version.name").isNotBlank() -> "Xiaomi HyperOS" to g("ro.mi.os.version.name")
            g("ro.miui.ui.version.name").isNotBlank() -> "Xiaomi MIUI" to g("ro.miui.ui.version.name") + (g("ro.build.version.incremental").let { if (it.isBlank()) "" else " ($it)" })
            g("ro.build.version.oneui").isNotBlank() -> "Samsung One UI" to oneUi(g("ro.build.version.oneui"))
            g("ro.build.version.realmeui").isNotBlank() -> "realme UI" to g("ro.build.version.realmeui")
            g("ro.build.version.oplusrom").isNotBlank() -> "Oppo / OnePlus / realme ColorOS family" to g("ro.build.version.oplusrom")
            g("ro.build.version.opporom").isNotBlank() -> "Oppo ColorOS" to g("ro.build.version.opporom")
            g("ro.vivo.os.version").isNotBlank() -> ((g("ro.vivo.os.name").ifBlank { "vivo Funtouch / OriginOS" })) to g("ro.vivo.os.version")
            g("ro.build.version.emui").isNotBlank() -> "Huawei EMUI" to g("ro.build.version.emui")
            g("ro.build.version.harmony").isNotBlank() -> "HarmonyOS" to g("ro.build.version.harmony")
            g("ro.lineage.version").isNotBlank() -> "LineageOS" to g("ro.lineage.version")
            g("ro.pixelexperience.version").isNotBlank() -> "PixelExperience" to g("ro.pixelexperience.version")
            g("ro.crdroid.version").isNotBlank() -> "crDroid" to g("ro.crdroid.version")
            g("ro.build.version.itel").isNotBlank() || g("ro.tranos.version").isNotBlank() -> "Transsion (HiOS / XOS / itel OS)" to g("ro.tranos.version").ifBlank { g("ro.build.version.itel") }
            g("ro.com.google.clientidbase").contains("android-google") && g("ro.product.manufacturer").equals("Google", true) -> "Pixel (stock Android)" to ""
            else -> "Android (stock or unknown skin)" to ""
        }
    }

    /** 60100 -> 6.1.0 */
    internal fun oneUi(raw: String): String {
        val n = raw.trim().toIntOrNull() ?: return raw
        return "${n / 10000}.${(n % 10000) / 100}.${n % 100}"
    }

    /** `dumpsys meminfo` lines like "  123,456K: com.foo (pid 12 / activities)" as [{app, mb}], biggest first. */
    internal fun parseTopPss(text: String, limit: Int): JSONArray {
        val out = JSONArray()
        val re = Regex("^\\s*([\\d,]+)K:\\s+(\\S+)")
        var n = 0
        for (line in text.lines()) {
            val m = re.find(line) ?: continue
            val kb = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            out.put(JSONObject().put("app", m.groupValues[2]).put("ram_mb", kb / 1024L))
            if (++n >= limit) break
        }
        return out
    }

    internal fun uptimeText(ms: Long): String {
        val s = ms / 1000L
        val d = s / 86400L; val h = (s % 86400L) / 3600L; val m = (s % 3600L) / 60L
        return (if (d > 0) "$d d " else "") + (if (d > 0 || h > 0) "$h h " else "") + "$m min"
    }

    internal fun networkTypeName(t: Int): String = when (t) {
        1 -> "GPRS"; 2 -> "EDGE"; 3 -> "3G UMTS"; 4 -> "CDMA"; 8 -> "3G HSDPA"; 9 -> "3G HSUPA"; 10 -> "3G HSPA"
        13 -> "4G LTE"; 15 -> "3G HSPA+"; 19 -> "4G LTE advanced"; 20 -> "5G NR"; 0 -> "unknown"; else -> "type $t"
    }

    internal fun ipText(i: Int): String = "${i and 0xff}.${(i shr 8) and 0xff}.${(i shr 16) and 0xff}.${(i shr 24) and 0xff}"

    // ---------------------------------------------------------------- reading

    private fun readText(path: String, max: Int = 20000): String? = try {
        val f = File(path)
        if (!f.exists() || !f.canRead()) null else f.bufferedReader().use { it.readText() }.take(max)
    } catch (_: Exception) { null }

    private fun readLong(path: String): Long? = readText(path, 64)?.trim()?.toLongOrNull()

    @Volatile private var propCache: Map<String, String>? = null
    private fun props(): Map<String, String> {
        propCache?.let { return it }
        val m = try {
            val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
            val txt = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            parseProps(txt)
        } catch (_: Exception) { emptyMap() }
        if (m.isNotEmpty()) propCache = m
        return m
    }

    private fun safe(f: () -> JSONObject): JSONObject = try { f() }
        catch (e: SecurityException) { JSONObject().put("available", false).put("reason", "Android did not allow this (permission): ${e.message}") }
        catch (e: Exception) { JSONObject().put("available", false).put("reason", (e.javaClass.simpleName + ": " + (e.message ?: "")).take(160)) }

    private fun rootOk(): Boolean = try { Root.available(false) } catch (_: Exception) { false }

    // ---- device / chipset / ROM
    private fun device(c: Context, full: Boolean): JSONObject {
        val p = props()
        val o = JSONObject()
        o.put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND).put("model", Build.MODEL)
        o.put("device_codename", Build.DEVICE).put("product", Build.PRODUCT)
        try { Settings.Global.getString(c.contentResolver, "device_name")?.let { if (it.isNotBlank()) o.put("user_set_name", it) } } catch (_: Exception) {}
        // chipset: Android 12+ names it directly; older phones give the board / hardware code
        val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
        val socMaker = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        val platform = p["ro.board.platform"].orEmpty()
        val chipRaw = listOf(socModel, p["ro.soc.model"].orEmpty(), p["ro.hardware.chipname"].orEmpty(), platform, Build.HARDWARE, Build.BOARD)
            .firstOrNull { it.isNotBlank() && it != "unknown" }.orEmpty()
        o.put("chipset", chipRaw)
        if (socMaker.isNotBlank() && socMaker != "unknown") o.put("chipset_maker", socMaker)
        val cpuHw = cpuInfoValue("Hardware")
        if (cpuHw.isNotBlank()) o.put("cpu_hardware", cpuHw)
        socName(socModel.ifBlank { chipRaw })?.let { o.put("chipset_name", it) }
        o.put("board", Build.BOARD).put("hardware", Build.HARDWARE).put("board_platform", platform)
        p["ro.hardware.egl"]?.takeIf { it.isNotBlank() }?.let { o.put("gpu_driver_hint", it) }
        o.put("cpu_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        // Android and ROM
        o.put("android_version", Build.VERSION.RELEASE).put("android_sdk", Build.VERSION.SDK_INT)
        if (Build.VERSION.SDK_INT >= 23) o.put("security_patch", Build.VERSION.SECURITY_PATCH)
        val rom = romName(p)
        o.put("rom", rom.first); if (rom.second.isNotBlank()) o.put("rom_version", rom.second)
        o.put("build_id", Build.DISPLAY).put("build_incremental", Build.VERSION.INCREMENTAL).put("build_type", Build.TYPE)
        o.put("kernel", System.getProperty("os.version") ?: "")
        o.put("uptime_since_boot", uptimeText(SystemClock.elapsedRealtime()))
        if (full) {
            o.put("fingerprint", Build.FINGERPRINT).put("bootloader", Build.BOOTLOADER).put("build_tags", Build.TAGS)
            p["ro.build.date"]?.let { o.put("build_date", it) }
            p["gsm.version.baseband"]?.takeIf { it.isNotBlank() }?.let { o.put("baseband", it) }
            p["ro.product.first_api_level"]?.let { o.put("first_api_level", it) }
            o.put("language", Locale.getDefault().toLanguageTag()).put("time_zone", TimeZone.getDefault().id)
        }
        return o
    }

    private fun cpuInfoValue(key: String): String {
        val t = readText("/proc/cpuinfo") ?: return ""
        for (line in t.lines()) {
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals(key, true)) return line.substring(i + 1).trim()
        }
        return ""
    }

    // ---- battery and charging
    private fun battery(c: Context, full: Boolean): JSONObject {
        val o = JSONObject()
        val i: Intent? = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val prop = try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { -1 }
        val pct = if (level >= 0 && scale > 0) Math.round(level * 100.0 / scale).toInt() else prop
        o.put("percent", pct)
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || (status == BatteryManager.BATTERY_STATUS_FULL && plugged != 0)
        o.put("status", when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging (on battery)"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "plugged in but not charging"
            else -> "unknown"
        })
        o.put("is_charging", charging)
        o.put("plugged_via", when {
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC charger"
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
            plugged != 0 -> "dock / other"
            else -> "not plugged in"
        })
        val voltage = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        if (voltage > 0) o.put("voltage_mv", voltage)
        val tenths = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (tenths != Int.MIN_VALUE) o.put("temperature_c", r1(tenths / 10.0))
        o.put("health", when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"; BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"; BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            else -> "unknown"
        })
        i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() }?.let { o.put("technology", it) }
        // the real-time current: sign and unit differ between makers, so only its size is used
        var cur = try { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) } catch (_: Exception) { Long.MIN_VALUE }
        if (cur == Long.MIN_VALUE || cur == 0L) cur = readLong("/sys/class/power_supply/battery/current_now") ?: 0L
        if (cur != 0L && voltage > 0) {
            val w = chargeWatts(cur, voltage)
            if (charging) {
                o.put("charging_current_ma", currentMa(cur)).put("charging_power_watts", w).put("charging_speed", chargeClass(w))
                o.put("charging_note", "Live reading from the battery chip at this moment; it rises and falls with the charger, the heat and the battery level (it drops near 80 to 100 percent).")
            } else {
                o.put("drain_current_ma", currentMa(cur)).put("drain_power_watts", w)
            }
        } else if (charging) {
            o.put("charging_power_watts_available", false).put("charging_note", "This phone does not let apps read the live charging current, so the exact charging speed is unknown.")
        }
        if (Build.VERSION.SDK_INT >= 28 && charging) {
            val ms = try { bm.computeChargeTimeRemaining() } catch (_: Exception) { -1L }
            if (ms > 0) o.put("time_to_full_min", ms / 60000L)
        }
        if (full) {
            val cc = try { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) } catch (_: Exception) { Long.MIN_VALUE }
            if (cc != Long.MIN_VALUE && cc > 0) o.put("charge_now_mah", cc / 1000L)
            val cyc = i?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1   // Android 14+
            val cycle = if (cyc > 0) cyc.toLong() else readLong("/sys/class/power_supply/battery/cycle_count") ?: -1L
            if (cycle > 0) o.put("charge_cycles", cycle)
            val design = readLong("/sys/class/power_supply/battery/charge_full_design")
            val now = readLong("/sys/class/power_supply/battery/charge_full")
            if (design != null && design > 0) {
                o.put("design_capacity_mah", design / 1000L)
                if (now != null && now > 0) o.put("full_capacity_mah", now / 1000L).put("battery_health_percent", Math.round(now * 100.0 / design).toInt())
            }
            o.put("battery_saver_on", (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode)
        }
        return o
    }

    // ---- RAM
    private fun memory(c: Context, full: Boolean): JSONObject {
        val o = JSONObject()
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        val used = mi.totalMem - mi.availMem
        o.put("total_gb", gb(mi.totalMem)).put("ram_size_on_box_gb", marketingRamGb(mi.totalMem))
        o.put("used_gb", gb(used)).put("free_available_gb", gb(mi.availMem))
        o.put("used_percent", if (mi.totalMem > 0) Math.round(used * 100.0 / mi.totalMem).toInt() else 0)
        o.put("low_memory_now", mi.lowMemory)
        val mem = parseMeminfo(readText("/proc/meminfo") ?: "")
        val swapTotal = mem["SwapTotal"] ?: 0L
        if (swapTotal > 0) o.put("swap_zram_total_gb", gb(swapTotal * 1024L)).put("swap_zram_used_gb", gb(((swapTotal - (mem["SwapFree"] ?: 0L)) * 1024L)))
        if (full) {
            mem["Cached"]?.let { o.put("cached_mb", it / 1024L) }
            mem["Buffers"]?.let { o.put("buffers_mb", it / 1024L) }
            o.put("is_low_ram_device", am.isLowRamDevice).put("app_heap_limit_mb", am.memoryClass)
            if (rootOk()) {
                val (code, txt) = Root.exec("dumpsys meminfo", 8000)
                if (code == 0) o.put("top_ram_apps", parseTopPss(txt, 8))
            } else o.put("per_app_ram", "Android does not let normal apps read other apps' RAM; with root given to NISU AI the top apps can be listed.")
        }
        return o
    }

    // ---- CPU
    private fun cpu(c: Context, full: Boolean): JSONObject {
        val o = JSONObject()
        val cores = Runtime.getRuntime().availableProcessors()
        o.put("cores", cores)
        val info = readText("/proc/cpuinfo") ?: ""
        val parts = info.lines().filter { it.startsWith("CPU part") }.map { it.substringAfter(':').trim() }
        if (parts.isNotEmpty()) {
            val names = parts.groupingBy { armCore(it) ?: it }.eachCount()
            o.put("core_types", JSONObject().also { j -> names.forEach { (k, v) -> j.put(k, v) } })
        }
        // speed per core (many makers hide it from apps)
        val cur = ArrayList<Long>(); val max = ArrayList<Long>(); val min = ArrayList<Long>()
        val gov = ArrayList<String>()
        for (n in 0 until cores) {
            val base = "/sys/devices/system/cpu/cpu$n/cpufreq/"
            cur.add((readLong(base + "scaling_cur_freq") ?: readLong(base + "cpuinfo_cur_freq") ?: -1L))
            max.add(readLong(base + "cpuinfo_max_freq") ?: -1L)
            min.add(readLong(base + "cpuinfo_min_freq") ?: -1L)
            readText(base + "scaling_governor", 40)?.trim()?.let { gov.add(it) }
        }
        val curOk = cur.filter { it > 0 }; val maxOk = max.filter { it > 0 }
        if (maxOk.isNotEmpty()) o.put("max_speed_mhz", (maxOk.maxOrNull() ?: 0L) / 1000L).put("min_speed_mhz", (min.filter { it > 0 }.minOrNull() ?: 0L) / 1000L)
        if (curOk.isNotEmpty()) {
            o.put("current_speed_mhz_highest_core", (curOk.maxOrNull() ?: 0L) / 1000L)
            if (full) o.put("per_core_current_mhz", JSONArray(cur.map { if (it > 0) it / 1000L else -1L }))
        } else o.put("current_speed", "This phone does not let apps read the live CPU speed.")
        if (full && gov.isNotEmpty()) o.put("governor", gov.distinct().joinToString(", "))
        if (full && maxOk.isNotEmpty()) {
            val groups = max.withIndex().filter { it.value > 0 }.groupBy { it.value }.toSortedMap(compareByDescending<Long> { it })
            o.put("clusters", JSONArray(groups.map { (f, l) -> "${l.size} cores up to ${f / 1000L} MHz" }))
        }
        val use = cpuUsage()
        if (use >= 0) o.put("usage_percent", use) else o.put("usage", "Android hides the total CPU load from normal apps on this phone (it works when NISU AI has root).")
        val z = thermalCpu()
        if (z != null) o.put("cpu_temperature_c", z)
        return o
    }

    /** Whole-CPU load over a quarter of a second, or -1 when the system hides /proc/stat from apps (Android 8 and up). */
    private fun cpuUsage(): Int {
        fun sample(): Pair<Long, Long>? {
            readText("/proc/stat", 600)?.lines()?.firstOrNull()?.let { parseCpuStat(it) }?.let { return it }
            if (rootOk()) { val (code, txt) = Root.exec("head -n 1 /proc/stat", 2500); if (code == 0) return parseCpuStat(txt.lines().firstOrNull().orEmpty()) }
            return null
        }
        val a = sample() ?: return -1
        try { Thread.sleep(250) } catch (_: InterruptedException) {}
        val b = sample() ?: return -1
        return cpuPercent(a, b)
    }

    private fun thermalCpu(): Double? {
        for (n in 0 until 40) {
            val type = readText("/sys/class/thermal/thermal_zone$n/type", 40)?.trim()?.lowercase(Locale.US) ?: continue
            if (!(type.contains("cpu") || type.contains("soc") || type.contains("tsens") || type.contains("mtktscpu"))) continue
            val v = readLong("/sys/class/thermal/thermal_zone$n/temp") ?: continue
            val t = if (v > 1000) v / 1000.0 else v.toDouble()
            if (t in 1.0..150.0) return r1(t)
        }
        return null
    }

    // ---- storage
    private fun storage(): JSONObject {
        val o = JSONObject()
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.totalBytes; val free = st.availableBytes
        o.put("internal_total_gb", gb(total)).put("storage_size_on_box_gb", marketingStorageGb(total))
        o.put("internal_used_gb", gb(total - free)).put("internal_free_gb", gb(free))
        o.put("internal_used_percent", if (total > 0) Math.round((total - free) * 100.0 / total).toInt() else 0)
        return o
    }

    // ---- mobile network and internet
    @Suppress("DEPRECATION")
    private fun network(c: Context, full: Boolean): JSONObject {
        val o = JSONObject()
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        if (net == null || caps == null) {
            o.put("connected", false)
        } else {
            o.put("connected", true)
            val t = ArrayList<String>()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) t.add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) t.add("mobile data")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) t.add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) t.add("bluetooth tethering")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) t.add("VPN")
            o.put("connected_via", t.joinToString(" + ")).put("internet_working", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            o.put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            o.put("link_down_mbps_estimate", caps.linkDownstreamBandwidthKbps / 1000).put("link_up_mbps_estimate", caps.linkUpstreamBandwidthKbps / 1000)
            if (full) {
                cm.getLinkProperties(net)?.let { lp ->
                    o.put("interface", lp.interfaceName ?: "")
                    o.put("local_addresses", JSONArray(lp.linkAddresses.mapNotNull { it.address.hostAddress }.take(4)))
                    o.put("dns_servers", JSONArray(lp.dnsServers.mapNotNull { it.hostAddress }.take(3)))
                }
            }
        }
        val tm = c.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm != null) {
            val s = JSONObject()
            s.put("sim_state", when (tm.simState) {
                TelephonyManager.SIM_STATE_READY -> "ready"; TelephonyManager.SIM_STATE_ABSENT -> "no SIM"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN needed"; TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK needed"
                else -> "not ready" })
            tm.networkOperatorName?.takeIf { it.isNotBlank() }?.let { s.put("operator", it) }
            try { s.put("roaming", tm.isNetworkRoaming) } catch (_: Exception) {}
            if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try { s.put("mobile_network_type", networkTypeName(tm.dataNetworkType)) } catch (_: SecurityException) {}
            } else s.put("mobile_network_type", "needs the Phone permission")
            o.put("mobile", s)
        }
        return o
    }

    // ---- Wi-Fi
    @Suppress("DEPRECATION")
    private fun wifi(c: Context, full: Boolean): JSONObject {
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val o = JSONObject().put("enabled", wm.isWifiEnabled)
        if (!wm.isWifiEnabled) return o
        val info = wm.connectionInfo
        val connected = info != null && info.supplicantState == SupplicantState.COMPLETED && info.rssi > -127
        o.put("connected", connected)
        if (full) {
            o.put("supports_5ghz", wm.is5GHzBandSupported)
            if (Build.VERSION.SDK_INT >= 30) o.put("supports_6ghz", wm.is6GHzBandSupported)
        }
        if (!connected || info == null) return o
        val ssid = info.ssid?.trim('"').orEmpty()
        if (ssid.isNotBlank() && ssid != "<unknown ssid>") o.put("network_name", ssid)
        else o.put("network_name_hidden", "Android shows the Wi-Fi name to apps only when Location permission and Location are on.")
        o.put("signal_dbm", info.rssi).put("signal_quality", signalQuality(info.rssi))
        o.put("link_speed_mbps", info.linkSpeed)
        if (Build.VERSION.SDK_INT >= 29) {
            if (info.txLinkSpeedMbps > 0) o.put("upload_link_speed_mbps", info.txLinkSpeedMbps)
            if (info.rxLinkSpeedMbps > 0) o.put("download_link_speed_mbps", info.rxLinkSpeedMbps)
        }
        if (info.frequency > 0) o.put("frequency_mhz", info.frequency).put("band", wifiBand(info.frequency)).put("channel", wifiChannel(info.frequency))
        if (Build.VERSION.SDK_INT >= 30) o.put("wifi_standard", wifiStandardName(info.wifiStandard))
        if (info.ipAddress != 0) o.put("ip_address", ipText(info.ipAddress))
        if (full) {
            val d = wm.dhcpInfo
            if (d != null) { if (d.gateway != 0) o.put("router_address", ipText(d.gateway)); if (d.dns1 != 0) o.put("dns", ipText(d.dns1)) }
        }
        return o
    }

    // ---- Bluetooth
    private fun bluetooth(c: Context, full: Boolean): JSONObject {
        val o = JSONObject()
        val pm = c.packageManager
        o.put("hardware", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)).put("low_energy_supported", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
        val ad: BluetoothAdapter? = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (ad == null) return o.put("available", false)
        val canConnect = Build.VERSION.SDK_INT < 31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!canConnect) return o.put("note", "The Nearby devices (Bluetooth) permission is not allowed for NISU AI, so the on / off state and device names cannot be read.")
        o.put("enabled", ad.isEnabled)
        if (!ad.isEnabled) return o
        try { ad.name?.let { o.put("this_phone_name", it) } } catch (_: Exception) {}
        val profiles = JSONArray()
        try { if (ad.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED) profiles.put("audio (A2DP)") } catch (_: Exception) {}
        try { if (ad.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED) profiles.put("headset / calls") } catch (_: Exception) {}
        o.put("connected_profiles", profiles)
        val paired = JSONArray(); val connectedNames = JSONArray()
        for (d in ad.bondedDevices.orEmpty().take(25)) {
            val j = JSONObject().put("name", d.name ?: "unknown")
            j.put("kind", when (d.bluetoothClass?.majorDeviceClass) {
                BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio (headphones / speaker / car)"
                BluetoothClass.Device.Major.PHONE -> "phone"; BluetoothClass.Device.Major.COMPUTER -> "computer"
                BluetoothClass.Device.Major.WEARABLE -> "wearable"; BluetoothClass.Device.Major.PERIPHERAL -> "keyboard / mouse / controller"
                BluetoothClass.Device.Major.HEALTH -> "health"; else -> "other" })
            // hidden but widely available on Android: is it connected right now, and its battery
            val on = try { BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as? Boolean } catch (_: Throwable) { null }
            if (on != null) { j.put("connected_now", on); if (on) connectedNames.put(d.name ?: "unknown") }
            if (full || on == true) {
                val bat = try { BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as? Int } catch (_: Throwable) { null }
                if (bat != null && bat in 0..100) j.put("battery_percent", bat)
            }
            paired.put(j)
        }
        o.put("connected_devices", connectedNames).put("paired_devices", paired)
        if (full) {
            o.put("le_2m_phy", ad.isLe2MPhySupported).put("le_extended_advertising", ad.isLeExtendedAdvertisingSupported)
        }
        return o
    }

    // ---- screen
    private fun display(c: Context): JSONObject {
        val o = JSONObject()
        val dm = c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return o.put("available", false)
        val mode = d.mode
        o.put("resolution", "${mode.physicalWidth} x ${mode.physicalHeight}")
        o.put("refresh_rate_hz_now", r1(d.refreshRate.toDouble()))
        o.put("supported_refresh_rates_hz", JSONArray(d.supportedModes.map { Math.round(it.refreshRate) }.distinct().sorted()))
        o.put("density_dpi", c.resources.displayMetrics.densityDpi).put("hdr", d.isHdr).put("wide_color_gamut", d.isWideColorGamut)
        try {
            val b = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            o.put("brightness_percent", Math.round(b * 100.0 / 255.0).toInt())
            o.put("auto_brightness", Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        } catch (_: Exception) {}
        o.put("screen_on", (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)
        return o
    }

    // ---- sensors, cameras, features
    private fun sensors(c: Context): JSONObject {
        val o = JSONObject()
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        o.put("sensor_count", sm.getSensorList(Sensor.TYPE_ALL).size)
        val has = JSONArray()
        mapOf("accelerometer" to Sensor.TYPE_ACCELEROMETER, "gyroscope" to Sensor.TYPE_GYROSCOPE, "compass (magnetometer)" to Sensor.TYPE_MAGNETIC_FIELD,
            "light" to Sensor.TYPE_LIGHT, "proximity" to Sensor.TYPE_PROXIMITY, "barometer" to Sensor.TYPE_PRESSURE, "step counter" to Sensor.TYPE_STEP_COUNTER,
            "heart rate" to Sensor.TYPE_HEART_RATE).forEach { (n, t) -> if (sm.getDefaultSensor(t) != null) has.put(n) }
        o.put("main_sensors", has)
        val pm = c.packageManager
        val f = JSONArray()
        mapOf("NFC" to "android.hardware.nfc", "fingerprint" to "android.hardware.fingerprint", "GPS" to "android.hardware.location.gps",
            "camera flash" to "android.hardware.camera.flash", "Wi-Fi Direct" to "android.hardware.wifi.direct", "USB host (OTG)" to "android.hardware.usb.host",
            "5G / telephony" to "android.hardware.telephony", "dual SIM" to "android.hardware.telephony.subscription", "face / iris" to "android.hardware.biometrics.face",
            "ultra wideband" to "android.hardware.uwb").forEach { (n, k) -> if (pm.hasSystemFeature(k)) f.put(n) }
        o.put("hardware_features", f)
        try {
            val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cams = JSONArray()
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                val face = when (ch.get(CameraCharacteristics.LENS_FACING)) { CameraCharacteristics.LENS_FACING_FRONT -> "front"; CameraCharacteristics.LENS_FACING_BACK -> "back"; else -> "external" }
                val sz = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                cams.put(JSONObject().put("id", id).put("facing", face).also { j -> if (sz != null) j.put("megapixels", r1(sz.width * sz.height / 1_000_000.0)) })
            }
            o.put("cameras", cams)
        } catch (_: Exception) {}
        return o
    }

    // ---- power / heat
    private fun power(c: Context): JSONObject {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val o = JSONObject().put("battery_saver_on", pm.isPowerSaveMode).put("ignoring_battery_optimization_for_nisu", pm.isIgnoringBatteryOptimizations(c.packageName))
        if (Build.VERSION.SDK_INT >= 29) o.put("thermal_state", when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "normal"; PowerManager.THERMAL_STATUS_LIGHT -> "slightly warm"; PowerManager.THERMAL_STATUS_MODERATE -> "warm"
            PowerManager.THERMAL_STATUS_SEVERE -> "hot"; PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "very hot"
            else -> "unknown" })
        o.put("uptime_since_boot", uptimeText(SystemClock.elapsedRealtime()))
        return o
    }

    // ---------------------------------------------------------------- the tool

    /** section: summary (default), battery, memory, cpu, device, storage, network, wifi, bluetooth, display, sensors, power, all. Never throws. */
    fun run(ctx: Context, a: JSONObject): JSONObject {
        val sec = canonical(a.optString("section", "summary"))
        val c = ctx.applicationContext
        val out = JSONObject().put("ok", true).put("section", sec)
        out.put("read_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        val all = sec == "all"
        val sum = sec == "summary"
        if (all || sum || sec == "battery") out.put("battery", safe { battery(c, !sum) })
        if (all || sum || sec == "memory") out.put("memory", safe { memory(c, !sum) })
        if (all || sum || sec == "cpu") out.put("cpu", safe { cpu(c, !sum) })
        if (all || sum || sec == "device") out.put("device", safe { device(c, !sum) })
        if (all || sum || sec == "storage") out.put("storage", safe { storage() })
        if (all || sec == "network") out.put("network", safe { network(c, !sum) })
        if (all || sec == "wifi") out.put("wifi", safe { wifi(c, true) })
        if (all || sec == "bluetooth") out.put("bluetooth", safe { bluetooth(c, true) })
        if (sum) {
            out.put("network", safe { network(c, false) })
            out.put("wifi", safe { wifi(c, false) })
            out.put("bluetooth", safe { bluetooth(c, false) })
        }
        if (all || sec == "display") out.put("display", safe { display(c) })
        if (all || sec == "sensors") out.put("sensors", safe { sensors(c) })
        if (all || sec == "power") out.put("power", safe { power(c) })
        out.put("note", "These are live values read from the phone just now. Say only what he asked. If a value is missing or says the phone does not allow it, say so plainly and never invent it.")
        return out
    }

    internal fun canonical(raw: String): String = when (raw.trim().lowercase(Locale.US).replace(' ', '_')) {
        "", "summary", "overview", "status", "info", "general" -> "summary"
        "all", "everything", "full", "sob" -> "all"
        "battery", "charge", "charging", "charger", "percent", "batt" -> "battery"
        "memory", "ram" -> "memory"
        "cpu", "processor", "chip", "speed", "load" -> "cpu"
        "device", "phone", "model", "rom", "chipset", "soc", "android", "system", "build" -> "device"
        "storage", "disk", "space", "internal" -> "storage"
        "network", "internet", "sim", "mobile", "data", "mobile_data" -> "network"
        "wifi", "wi-fi", "wi_fi" -> "wifi"
        "bluetooth", "bt" -> "bluetooth"
        "display", "screen" -> "display"
        "sensors", "sensor", "hardware", "features", "camera", "cameras" -> "sensors"
        "power", "thermal", "heat", "temperature", "temp" -> "power"
        else -> "summary"
    }

    /** Typed chat marker [[DEVICE: section]]: the same readings as plain text lines for the model. */
    fun typed(ctx: Context, arg: String): String {
        val r = run(ctx, JSONObject().put("section", arg))
        return "DEVICE INFO (live values read from the phone just now; answer only what he asked, from these; never invent a missing value):\n" + r.toString()
    }

    const val NOTE = """

DEVICE INFO (real values, read-only): when he asks anything about the phone itself (battery percent, is it charging and how fast, RAM and RAM use, CPU, chipset, speed, model, ROM or Android version, storage, Wi-Fi, Bluetooth, mobile network, screen, heat, sensors, or "phone er sob information"), call device_info at once and answer from what it returns. Never answer from memory or guess: these numbers change every second, so call it again each time he asks, even when you answered a moment ago. section: summary (general question, default), battery (percent, charging, charging power in watts, temperature), memory (RAM), cpu (chipset, cores, speed, load), device (model, chipset, ROM, Android, security patch), storage, network (mobile data, SIM), wifi, bluetooth, display, sensors, power, or all (only when he asks for everything). Say only what he asked in one to three short sentences with the key numbers, and read all of it only when he asks for everything. If a field is missing or says the phone does not allow it, say that plainly; never invent a value. It only reads: it changes nothing. To turn Wi-Fi or Bluetooth on or off use set_switch.
"""

    const val TYPED_NOTE = """

DEVICE INFO (silent, real values): when he asks about the phone itself (battery percent, charging speed, RAM, CPU, chipset, model, ROM, storage, Wi-Fi, Bluetooth, mobile network, screen, heat, sensors, or all the information), never answer from memory: reply with ONLY [[DEVICE: section]] and nothing else, where section is summary, battery, memory, cpu, device, storage, network, wifi, bluetooth, display, sensors, power or all. The live values come back and you answer from them in a few words with the key numbers. Ask again for a fresh reading every time he asks. If a value is missing, say the phone does not allow apps to read it; never invent one.
"""
}
