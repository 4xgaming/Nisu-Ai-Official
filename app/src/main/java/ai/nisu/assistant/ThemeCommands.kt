package ai.nisu.assistant

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * v3.134: the plain rules of the spoken / typed theme commands (no Android classes, so they can be unit tested).
 *
 *  "theme loop start koro"     -> loop_start: the custom photos loop, but only when photos were added (otherwise he is told no photo is added).
 *  "theme gallery start koro"  -> gallery_start: the READY themes (Sunset Garden, Dusk Saree, Jasmine Terrace) change by themselves every 5 seconds.
 *                                 The default look is NOT one of them: it only comes back when he says "default theme koro".
 *  "default theme koro"        -> default: the normal look of the mode (custom photos and all ready themes off, gallery stopped).
 *  "custom theme start koro"   -> custom_start: the custom photo(s) show (works with just ONE photo; no photo = he is told none is added).
 *  "loop time 10 minute koro"  -> set_time: how long each photo (loop) or each ready theme (gallery) stays: seconds, minutes, hours or days.
 *
 * These commands never work in Jarvis and Venom mode (only Care, Friend and Boss).
 */
object ThemeCommandRules {
    const val LOOP_START = "loop_start"
    const val LOOP_STOP = "loop_stop"
    const val GALLERY_START = "gallery_start"
    const val GALLERY_STOP = "gallery_stop"
    const val DEFAULT = "default"
    const val CUSTOM_START = "custom_start"
    const val SET_TIME = "set_time"

    const val GALLERY_SECS = 5

    const val GARDEN = "garden"
    const val SAREE = "saree"
    const val LACE = "lace"
    /** The newly added ready themes, in the order the gallery shows them. The default look is deliberately not in this list. */
    val READY: List<String> = listOf(GARDEN, SAREE, LACE)

    /** Only the NISU AI modes (Care, Friend, Boss). Jarvis and Venom never run these commands. */
    fun modeAllowed(mode: String): Boolean = GardenRules.nisuMode(mode)

    /** The theme after [cur] in the gallery; after the last one it starts again from the first. An unknown / empty [cur] gives the first. */
    fun next(cur: String?): String = READY[(READY.indexOf(cur) + 1) % READY.size]

    private fun stopWord(t: String) = t.contains("stop") || t.contains("off") || t.contains("bondho") || t.contains("\u09AC\u09A8\u09CD\u09A7") || t.contains("\u09A5\u09BE\u09AE")

    /** Shortest and longest time one photo / theme may stay (the same limits CustomThemeRules.clampSecs uses for the loop). */
    const val MIN_SECS = CustomThemeRules.MIN_SECS
    const val LIMIT_SECS = CustomThemeRules.LIMIT_SECS

    /** Seconds in one [unit] (English, or Bengali typed in English letters, or Bengali script); null when the unit is not understood. */
    fun unitSeconds(unit: String): Int? {
        val t = unit.lowercase().trim().replace(Regex("[\\s_\\-]+"), "")
        if (t.isEmpty()) return null
        fun has(vararg k: String) = k.any { t.contains(it) }
        return when {
            has("sec", "sek", "\u09B8\u09C7\u0995\u09C7\u09A8", "\u09B8\u09C7\u0995\u09C7\u09A3") -> 1
            has("min", "\u09AE\u09BF\u09A8\u09BF") -> 60
            has("hour", "hr", "ghon", "ghan", "\u0998\u09A3", "\u0998\u09A8") -> 3600
            has("day", "din", "\u09A6\u09BF\u09A8") -> 86400
            else -> null
        }
    }

    /** [amount] of [unit] in whole seconds, kept between 3 seconds and 30 days; null when the amount is not a positive number or the unit is unknown. */
    fun toSeconds(amount: Double, unit: String): Int? {
        if (amount.isNaN() || amount.isInfinite() || amount <= 0.0) return null
        val mult = unitSeconds(unit) ?: return null
        val raw = (amount * mult).coerceAtMost(LIMIT_SECS.toDouble())
        return raw.roundToInt().coerceIn(MIN_SECS, LIMIT_SECS)
    }

    /** 5 -> "5 seconds", 90 -> "1 minute 30 seconds", 3600 -> "1 hour", 172800 -> "2 days". */
    fun describe(secs: Int): String {
        var r = secs.coerceAtLeast(0)
        val d = r / 86400; r %= 86400
        val h = r / 3600; r %= 3600
        val m = r / 60
        val s = r % 60
        val parts = ArrayList<String>()
        fun add(n: Int, w: String) { if (n > 0) parts.add(n.toString() + " " + w + (if (n == 1) "" else "s")) }
        add(d, "day"); add(h, "hour"); add(m, "minute"); add(s, "second")
        return if (parts.isEmpty()) "0 seconds" else parts.joinToString(" ")
    }

    /** Maps what the model passes (or what people say, English or Bengali, typed in English letters too) to one of the action names; null when unknown. */
    fun action(raw: String): String? {
        val t = raw.lowercase().trim().replace(Regex("[\\s_\\-]+"), "")
        if (t.isEmpty()) return null
        return when {
            t.contains("time") || t.contains("interval") || t.contains("\u099F\u09BE\u0987\u09AE") -> SET_TIME
            t.contains("default") || t.contains("\u09A1\u09BF\u09AB\u09B2\u09CD\u099F") -> DEFAULT
            t.contains("gallery") || t.contains("galary") || t.contains("gallary") || t.contains("\u0997\u09CD\u09AF\u09BE\u09B2\u09BE\u09B0\u09BF") -> if (stopWord(t)) GALLERY_STOP else GALLERY_START
            t.contains("loop") || t.contains("\u09B2\u09C1\u09AA") -> if (stopWord(t)) LOOP_STOP else LOOP_START
            t.contains("custom") || t.contains("\u0995\u09BE\u09B8\u09CD\u099F\u09AE") -> if (stopWord(t)) DEFAULT else CUSTOM_START   // custom off = the normal look
            else -> null
        }
    }

    enum class LoopCheck { OK, NO_PHOTO, NEED_TWO }

    /** The loop needs photos: none = tell him no photo is added; one photo cannot loop (the Customization page also asks for at least 2). */
    fun loopCheck(photoCount: Int): LoopCheck = when {
        photoCount <= 0 -> LoopCheck.NO_PHOTO
        photoCount == 1 -> LoopCheck.NEED_TWO
        else -> LoopCheck.OK
    }
}

/**
 * v3.134: the theme gallery. While it runs, the ready themes change by themselves every [secs] seconds (5 unless he sets another time)
 * (Sunset Garden -> Dusk Saree -> Jasmine Terrace -> Sunset Garden ...). It pauses while Jarvis or Venom mode is on and goes on in the NISU AI modes.
 * Any manual choice (a theme tapped, DEFAULT, CUSTOM, a photo) or "default theme koro" stops it. It is not saved: after the app restarts it is off.
 */
object ThemeGallery {
    val running = MutableStateFlow(false)
    /** How long each ready theme stays (5 seconds until he sets another time; not saved, so 5 again after the app restarts). */
    val secs = MutableStateFlow(ThemeCommandRules.GALLERY_SECS)

    /** Which ready theme is selected right now (null = none: default look or the custom photos). */
    fun currentKey(): String? = when {
        GardenTheme.selected.value -> ThemeCommandRules.GARDEN
        SareeTheme.selected.value -> ThemeCommandRules.SAREE
        LaceTheme.selected.value -> ThemeCommandRules.LACE
        else -> null
    }

    fun nameOf(key: String): String = when (key) {
        ThemeCommandRules.GARDEN -> GardenTheme.NAME
        ThemeCommandRules.SAREE -> SareeTheme.NAME
        else -> LaceTheme.NAME
    }

    /** Picks one ready theme (each select() also switches the custom photos and the other ready themes off). */
    private fun show(ctx: Context, key: String) {
        when (key) {
            ThemeCommandRules.GARDEN -> GardenTheme.select(ctx)
            ThemeCommandRules.SAREE -> SareeTheme.select(ctx)
            else -> LaceTheme.select(ctx)
        }
    }

    /** Starts the gallery: the first ready theme shows at once, the next one follows after the gallery time ([newSecs] when given, else the time set so far). Returns the name of the first. */
    fun start(ctx: Context, newSecs: Int? = null): String {
        if (newSecs != null) secs.value = newSecs.coerceIn(ThemeCommandRules.MIN_SECS, ThemeCommandRules.LIMIT_SECS)
        val first = ThemeCommandRules.READY[0]
        show(ctx, first)
        running.value = true
        return nameOf(first)
    }

    fun stop() { running.value = false }

    /** One step of the clock. */
    fun showNext(ctx: Context) = show(ctx, ThemeCommandRules.next(currentKey()))

    /** The gallery clock. Put it ONCE near the top of the app (next to CustomTheme.Driver). It does nothing unless the gallery is running in a NISU AI mode. */
    @Composable
    fun Driver() {
        val app = LocalContext.current.applicationContext
        val on by running.collectAsState()
        val mode by PersonaMode.live.collectAsState()
        val wait by secs.collectAsState()
        val go = on && ThemeCommandRules.modeAllowed(mode)
        LaunchedEffect(go, wait) {
            if (!go) return@LaunchedEffect
            while (true) {
                delay(wait * 1000L)
                showNext(app)
            }
        }
    }
}

/** v3.134: the set_theme tool (called from a background thread by Tools.run). */
object ThemeCommands {
    const val TOOL = "set_theme"

    private fun err(msg: String, fix: String? = null): JSONObject =
        JSONObject().put("ok", false).put("error", msg).also { if (fix != null) it.put("fix", fix) }

    fun run(ctx: Context, a: JSONObject): JSONObject {
        val app = ctx.applicationContext
        val act = ThemeCommandRules.action(a.optString("action"))
            ?: return err("Unknown action. Use loop_start, loop_stop, gallery_start, gallery_stop or default.")

        val mode = PersonaMode.saved(app)
        if (!ThemeCommandRules.modeAllowed(mode))
            return err("Theme commands do not work in " + PersonaMode.label(mode) + ". They work only in Care, Friend and Boss mode.", "Tell him this in one short line. Do not change anything.")
                .put("blocked", true)

        // the app may not be on screen: make sure the saved choices are read before they are used
        CustomTheme.sync(app); GardenTheme.sync(app); SareeTheme.sync(app); LaceTheme.sync(app)
        val photos = CustomTheme.cfg.value.files.size

        // an optional time: amount + unit (second / minute / hour / day)
        var secs: Int? = null
        if (a.has("amount") && !a.isNull("amount")) {
            secs = ThemeCommandRules.toSeconds(a.optDouble("amount", Double.NaN), a.optString("unit"))
                ?: return err("The time was not understood: it needs a positive amount and a unit (second, minute, hour or day).", "Ask him once for the amount and the unit, then call again.")
        }
        if (act == ThemeCommandRules.SET_TIME && secs == null)
            return err("No time given: it needs amount and unit (second, minute, hour or day).", "Ask him how long, then call again.")

        fun noPhoto() = err("No photo is added yet, so there is nothing to show or loop.", "Tell him no photo is added. He can add photos in Customization (menu > Customization > ADD PHOTO), then ask again.")
            .put("photos", 0).put("no_photo", true)
        fun timeInfo(o: JSONObject, s: Int): JSONObject = o.put("every_seconds", s).put("every", ThemeCommandRules.describe(s))

        return when (act) {
            ThemeCommandRules.CUSTOM_START -> {
                if (photos <= 0) noPhoto() else {   // one photo is enough: it simply stays on screen
                    ThemeGallery.stop()
                    SareeTheme.setSelected(app, false); LaceTheme.setSelected(app, false)
                    CustomTheme.setOn(app, true)    // also switches the Sunset Garden off
                    JSONObject().put("ok", true).put("action", act).put("photos", photos).put("looping", CustomThemeRules.looping(CustomTheme.cfg.value))
                }
            }
            ThemeCommandRules.LOOP_START -> when (ThemeCommandRules.loopCheck(photos)) {
                ThemeCommandRules.LoopCheck.NO_PHOTO -> noPhoto()
                ThemeCommandRules.LoopCheck.NEED_TWO ->
                    err("Only 1 photo is added. A loop needs at least 2 photos.", "Tell him to add one more photo in Customization, then ask again. If he only wants the one photo to show, he can say custom theme start.")
                        .put("photos", 1)
                ThemeCommandRules.LoopCheck.OK -> {
                    ThemeGallery.stop()
                    SareeTheme.setSelected(app, false); LaceTheme.setSelected(app, false)
                    if (secs != null) CustomTheme.setSecs(app, secs)
                    CustomTheme.setOn(app, true)    // also switches the Sunset Garden off
                    CustomTheme.setLoop(app, true)
                    timeInfo(JSONObject().put("ok", true).put("action", act).put("photos", photos), CustomTheme.cfg.value.secs)
                }
            }
            ThemeCommandRules.LOOP_STOP -> {
                CustomTheme.setLoop(app, false)
                JSONObject().put("ok", true).put("action", act)
            }
            ThemeCommandRules.GALLERY_START -> {
                val first = ThemeGallery.start(app, secs)
                timeInfo(JSONObject().put("ok", true).put("action", act), ThemeGallery.secs.value)
                    .put("themes", org.json.JSONArray(ThemeCommandRules.READY.map { ThemeGallery.nameOf(it) }))
                    .put("showing_first", first)
                    .put("note", "The default look is not part of the gallery. He must say \"default theme koro\" to get it back.")
            }
            ThemeCommandRules.GALLERY_STOP -> {
                val was = ThemeGallery.running.value
                ThemeGallery.stop()
                JSONObject().put("ok", true).put("action", act).put("was_running", was)
            }
            ThemeCommandRules.SET_TIME -> {
                val want = secs!!
                val t = a.optString("target").lowercase()
                val gallery = t.contains("gallery") || t.contains("galary") || (t.isBlank() && ThemeGallery.running.value)   // no target: whatever is running (the gallery), else the loop
                val applied: Int
                if (gallery) { ThemeGallery.secs.value = want; applied = ThemeGallery.secs.value }
                else { CustomTheme.setSecs(app, want); applied = CustomTheme.cfg.value.secs }
                timeInfo(JSONObject().put("ok", true).put("action", act).put("target", if (gallery) "gallery" else "loop"), applied)
                    .put("running", if (gallery) ThemeGallery.running.value else CustomThemeRules.looping(CustomTheme.cfg.value))
            }
            else -> {   // DEFAULT: the normal look of the mode; the photos stay saved
                ThemeGallery.stop()
                CustomTheme.setOn(app, false); GardenTheme.setSelected(app, false); SareeTheme.setSelected(app, false); LaceTheme.setSelected(app, false)
                JSONObject().put("ok", true).put("action", act).put("default_theme", true)
            }
        }
    }
}
