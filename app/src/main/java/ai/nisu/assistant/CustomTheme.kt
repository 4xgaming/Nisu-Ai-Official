package ai.nisu.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v3.90: the plain rules of the custom theme (no Android classes, so they can be unit tested).
 * Up to [MAX] photos; one setting says whether the custom theme is ON (otherwise every mode keeps its own default look), one says whether the photos LOOP.
 */
object CustomThemeRules {
    const val MAX = 10
    const val MIN_SECS = 3
    /** The Customization slider goes from [MIN_SECS] to [MAX_SECS]. */
    const val MAX_SECS = 120
    /** v3.134: spoken / typed times (minutes, hours, days) may be much longer than the slider: up to 30 days. */
    const val LIMIT_SECS = 30 * 24 * 3600
    const val DEFAULT_SECS = 8

    /** on = the custom theme is selected (false = DEFAULT: Nisu / Jarvis / Venom look as before). loop = change photos by themselves. main = the photo shown when the loop is off. */
    data class Cfg(val on: Boolean = false, val loop: Boolean = false, val secs: Int = DEFAULT_SECS, val files: List<String> = emptyList(), val main: Int = 0)

    private val NAME_OK = Regex("^[A-Za-z0-9-]{1,40}\\.jpg$")

    fun clampSecs(s: Int): Int = s.coerceIn(MIN_SECS, LIMIT_SECS)
    fun roomLeft(count: Int): Int = (MAX - count).coerceAtLeast(0)
    fun encode(files: List<String>): String = files.joinToString(",")

    /** Only names this app made itself are accepted (no paths, no odd characters), at most [MAX], no repeats. */
    fun decode(s: String): List<String> = s.split(',').map { it.trim() }.filter { NAME_OK.matches(it) }.distinct().take(MAX)

    /** The loop goes 0, 1, 2 ... last, then back to the first photo. */
    fun nextIndex(cur: Int, count: Int): Int = if (count <= 0) 0 else (cur + 1) % count

    /** The custom theme is really showing: it is selected AND there is at least one photo. */
    fun usable(c: Cfg): Boolean = c.on && c.files.isNotEmpty()

    /** The loop really runs only when the custom theme is selected, the loop is on and there are at least 2 photos. */
    fun looping(c: Cfg): Boolean = usable(c) && c.loop && c.files.size > 1

    /** The photo to show right now, or null when the default theme is selected (or there is no photo). */
    fun shown(c: Cfg, index: Int): String? {
        if (!usable(c)) return null
        val n = c.files.size
        return if (looping(c)) c.files[index.coerceIn(0, n - 1)] else c.files[c.main.coerceIn(0, n - 1)]
    }

    /** Keeps [main] pointing at the same photo (or the next one) after the photo at [removed] is deleted. */
    fun mainAfterRemove(main: Int, removed: Int, newCount: Int): Int {
        if (newCount <= 0) return 0
        val m = if (removed < main) main - 1 else main
        return m.coerceIn(0, newCount - 1)
    }
}

/**
 * v3.90: CUSTOM THEME. The person picks up to 10 photos from the phone (Customization page). While the custom theme is selected, those photos replace the
 * home picture and the blurred page backdrops and the round chat picture in EVERY mode (Nisu, Jarvis, Venom). The names / taglines at the top still
 * follow the mode. DEFAULT brings each mode's own look back (the photos stay saved); a photo can be removed one by one or all at once.
 * LOOP: when it is on (and the custom theme is selected, and there are 2+ photos) the photos change by themselves every N seconds with a soft fade and a slow
 * zoom, and after the last photo it starts again from the first.
 * The photos are copied into the app's own folder, shrunk to at most 2000 px on the long side, so they never depend on the gallery.
 */
object CustomTheme {
    const val MAX = CustomThemeRules.MAX
    const val MIN_SECS = CustomThemeRules.MIN_SECS
    const val MAX_SECS = CustomThemeRules.MAX_SECS
    const val DEFAULT_SECS = CustomThemeRules.DEFAULT_SECS
    fun roomLeft(count: Int): Int = CustomThemeRules.roomLeft(count)

    private const val K_ON = "ct_on"
    private const val K_LOOP = "ct_loop"
    private const val K_SECS = "ct_secs"
    private const val K_FILES = "ct_files"
    private const val K_MAIN = "ct_main"
    private const val DIR = "custom_theme"
    private const val MAX_SIDE = 2000
    private val TWO_PI = (2.0 * PI).toFloat()
    private val seq = AtomicInteger(0)

    val cfg = MutableStateFlow(CustomThemeRules.Cfg())
    /** Which photo the loop is showing (only meaningful while the loop runs). */
    val index = MutableStateFlow(0)

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR)

    /** Reads the saved settings (call once before the first frame). Photos that are missing on disk are dropped. */
    fun sync(ctx: Context) {
        val d = dir(ctx)
        val files = CustomThemeRules.decode(Prefs.get(ctx, K_FILES, "")).filter { File(d, it).isFile }
        cfg.value = CustomThemeRules.Cfg(
            on = Prefs.getBool(ctx, K_ON, false) && files.isNotEmpty(),
            loop = Prefs.getBool(ctx, K_LOOP, false),
            secs = CustomThemeRules.clampSecs(Prefs.getInt(ctx, K_SECS, DEFAULT_SECS)),
            files = files,
            main = Prefs.getInt(ctx, K_MAIN, 0).coerceIn(0, (files.size - 1).coerceAtLeast(0))
        )
    }

    @Synchronized
    private fun update(ctx: Context, f: (CustomThemeRules.Cfg) -> CustomThemeRules.Cfg) {
        val c = f(cfg.value)
        if (c.on && c.files.isNotEmpty() && GardenTheme.selected.value) GardenTheme.setSelected(ctx, false)   // v3.91: the custom photos and the Sunset Garden theme exclude each other
        Prefs.putBool(ctx, K_ON, c.on); Prefs.putBool(ctx, K_LOOP, c.loop); Prefs.putInt(ctx, K_SECS, c.secs)
        Prefs.put(ctx, K_FILES, CustomThemeRules.encode(c.files)); Prefs.putInt(ctx, K_MAIN, c.main)
        cfg.value = c
    }

    fun setOn(ctx: Context, on: Boolean) = update(ctx) { it.copy(on = on && it.files.isNotEmpty()) }
    fun setLoop(ctx: Context, loop: Boolean) = update(ctx) { it.copy(loop = loop) }
    fun setSecs(ctx: Context, secs: Int) = update(ctx) { it.copy(secs = CustomThemeRules.clampSecs(secs)) }
    fun setMain(ctx: Context, i: Int) = update(ctx) { it.copy(main = i.coerceIn(0, (it.files.size - 1).coerceAtLeast(0))) }

    /** Deletes one photo. When it was the last one, the default theme comes back by itself. */
    fun remove(ctx: Context, name: String) {
        val app = ctx.applicationContext
        File(dir(app), name).delete(); cache.remove(name); thumbs.remove(name)
        update(app) { c ->
            val i = c.files.indexOf(name)
            if (i < 0) c else {
                val files = c.files.filter { it != name }
                c.copy(files = files, main = CustomThemeRules.mainAfterRemove(c.main, i, files.size), on = c.on && files.isNotEmpty())
            }
        }
    }

    /** Deletes every photo; the default theme comes back. */
    fun removeAll(ctx: Context) {
        val app = ctx.applicationContext
        cfg.value.files.forEach { File(dir(app), it).delete() }
        cache.evictAll(); thumbs.evictAll()
        update(app) { it.copy(files = emptyList(), main = 0, on = false) }
    }

    /**
     * Copies the chosen photos in (only as many as there is room for, max 10 in all). [done] runs on the main thread with how many were added and how many were
     * left out. The first photo ever added also switches the custom theme on.
     */
    fun add(ctx: Context, uris: List<Uri>, done: (added: Int, skipped: Int) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val names = ArrayList<String>()
            val room = CustomThemeRules.roomLeft(cfg.value.files.size)
            for (u in uris.take(room)) importOne(app, u)?.let { names.add(it) }
            if (names.isNotEmpty()) update(app) { c -> c.copy(files = (c.files + names).take(CustomThemeRules.MAX), on = if (c.files.isEmpty()) true else c.on) }
            val added = names.size
            Handler(Looper.getMainLooper()).post { done(added, uris.size - added) }
        }.start()
    }

    // ------------------------------------------------------------------------------------------ pictures on disk

    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount }
    private val thumbs = object : LruCache<String, Bitmap>(8 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount }

    private fun importOne(ctx: Context, uri: Uri): String? {
        val bmp = decodeUri(ctx, uri) ?: return null
        val name = "p" + java.lang.Long.toString(System.currentTimeMillis(), 36) + seq.incrementAndGet() + ".jpg"
        return try {
            val d = dir(ctx); d.mkdirs()
            FileOutputStream(File(d, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            name
        } catch (_: Throwable) { null } finally { bmp.recycle() }
    }

    /** Decodes a picked photo shrunk to MAX_SIDE and turned upright (EXIF), or null when it cannot be read. */
    private fun decodeUri(ctx: Context, uri: Uri): Bitmap? = try {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) null else {
            val longSide = max(o.outWidth, o.outHeight)
            var sample = 1
            while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            var b: Bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
            val deg = try {
                cr.openInputStream(uri)?.use { s ->
                    when (android.media.ExifInterface(s).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } ?: 0
            } catch (_: Throwable) { 0 }
            val ls = max(b.width, b.height)
            if (ls > MAX_SIDE) {
                val f = MAX_SIDE.toFloat() / ls
                val s = Bitmap.createScaledBitmap(b, (b.width * f).roundToInt().coerceAtLeast(1), (b.height * f).roundToInt().coerceAtLeast(1), true)
                if (s !== b) b.recycle()
                b = s
            }
            if (deg != 0) {
                val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
                if (r !== b) b.recycle()
                b = r
            }
            b
        }
    } catch (_: Throwable) { null }   // includes OutOfMemoryError: one bad photo is skipped, the app keeps running

    fun cached(name: String): Bitmap? = cache.get(name)

    /** Full-size photo from the saved copy (kept in a small memory cache). Call off the main thread. */
    fun load(ctx: Context, name: String): Bitmap? {
        cache.get(name)?.let { return it }
        val b = try { BitmapFactory.decodeFile(File(dir(ctx), name).path) } catch (_: Throwable) { null } ?: return null
        cache.put(name, b)
        return b
    }

    private fun thumb(ctx: Context, name: String): Bitmap? {
        thumbs.get(name)?.let { return it }
        val b = try { BitmapFactory.decodeFile(File(dir(ctx), name).path, BitmapFactory.Options().apply { inSampleSize = 8 }) } catch (_: Throwable) { null } ?: return null
        thumbs.put(name, b)
        return b
    }

    // ------------------------------------------------------------------------------------------ what the screens use

    /** True while the custom theme is really showing (selected and has a photo); recomposes the screen the moment it changes. */
    @Composable
    fun active(): Boolean = CustomThemeRules.usable(cfg.collectAsState().value)

    /**
     * The loop clock. Put it ONCE near the top of the app. Every N seconds it loads the next photo first (so the fade never shows a blank), then
     * switches to it; after the last photo it goes back to the first. It does nothing unless [CustomThemeRules.looping] is true.
     */
    @Composable
    fun Driver() {
        val app = LocalContext.current.applicationContext
        val c by cfg.collectAsState()
        val running = CustomThemeRules.looping(c)
        val files = c.files
        val secs = c.secs
        LaunchedEffect(running, secs, files) {
            if (!running) { index.value = 0; return@LaunchedEffect }
            val n = files.size
            if (index.value >= n) index.value = 0
            withContext(Dispatchers.IO) { load(app, files[index.value]) }
            while (true) {
                delay(secs * 1000L)
                val next = CustomThemeRules.nextIndex(index.value, n)
                withContext(Dispatchers.IO) { load(app, files[next]) }
                index.value = next
            }
        }
    }

    /**
     * The custom photo, filling its box (crop). Changing photo is a soft cross-fade; with [zoom] the photo also breathes in and out very slowly
     * (the same 24 s clock as the Venom theme, so it stands still when the phone's animations are off).
     */
    @Composable
    fun Photo(modifier: Modifier = Modifier, zoom: Boolean = true) {
        val c by cfg.collectAsState()
        val idx by index.collectAsState()
        val file = CustomThemeRules.shown(c, idx)
        val t = if (zoom) VenomTheme.clock() else null
        Crossfade(targetState = file, modifier = modifier, animationSpec = tween(1400), label = "customPhoto") { f ->
            if (f != null) PhotoLayer(f, t)
        }
    }

    @Composable
    private fun PhotoLayer(name: String, t: State<Float>?) {
        val ctx = LocalContext.current.applicationContext
        val bmp by produceState<Bitmap?>(initialValue = cached(name), name) {
            if (value == null) value = withContext(Dispatchers.IO) { load(ctx, name) }
        }
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    if (t != null) {
                        val s = 1.04f + 0.03f * ((sin(t.value * TWO_PI) + 1f) / 2f)   // always above 1, so the zoom never shows an edge
                        scaleX = s; scaleY = s
                    }
                }
            )
        } else {
            Box(Modifier.fillMaxSize().background(NisuUi.Bg))
        }
    }

    /** One photo in the Customization grid: the picture, its number, a border when it is the selected one, and a small x to remove it. */
    @Composable
    fun PhotoCell(name: String, number: Int, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit, onRemove: () -> Unit) {
        val ctx = LocalContext.current.applicationContext
        val shape = RoundedCornerShape(12.dp)
        val bmp by produceState<Bitmap?>(initialValue = thumbs.get(name), name) {
            if (value == null) value = withContext(Dispatchers.IO) { thumb(ctx, name) }
        }
        Box(
            modifier.aspectRatio(0.75f).clip(shape).background(NisuUi.Card)
                .border(if (selected) 2.dp else 1.dp, if (selected) NisuUi.Cyan else NisuUi.Line, shape)
                .clickable(onClick = onSelect)
        ) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Text(
                number.toString(), fontSize = 11.sp, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(26.dp).clip(CircleShape).background(Color(0xAA000000)).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) { Text("\u2715", fontSize = 12.sp, color = Color.White) }
        }
    }
}
