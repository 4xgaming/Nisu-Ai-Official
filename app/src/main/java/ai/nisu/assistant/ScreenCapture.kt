package ai.nisu.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Screen frames as small JPEGs.
 * Android 11+: uses the Accessibility screenshot API (no extra prompt).
 * Android 8-10 (or fallback): uses MediaProjection after the user allows screen capture once.
 */
object ScreenCapture {
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var pw = 0
    private var ph = 0
    @Volatile var lastError: String = ""
    /** The accessibility screenshot came back black: use the MediaProjection picture instead (needs the one-time screen capture permission). */
    @Volatile var preferProjection = false
    @Volatile private var lastJpeg: ByteArray? = null
    private var appCtx: Context? = null
    private var dens = 0

    /** True when a frame can really be taken right now (accessibility screenshot on Android 11+, or a live MediaProjection). */
    fun available(): Boolean = (Build.VERSION.SDK_INT >= 30 && NisuAccessibilityService.inst != null) || projection != null

    fun hasProjection(): Boolean = projection != null

    /** The projection was ended by the system (status-bar chip / another app) and nothing else can capture: the share switch must go OFF. */
    @Volatile var onEnded: (() -> Unit)? = null

    @Suppress("DEPRECATION")
    private fun metrics(ctx: Context): DisplayMetrics {
        val dm = DisplayMetrics()
        ctx.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(dm)
        return dm
    }

    fun startProjection(ctx: Context, mp: MediaProjection) {
        stop()
        appCtx = ctx.applicationContext
        projection = mp
        val dm = metrics(ctx)
        pw = (dm.widthPixels / 2).coerceAtLeast(16); ph = (dm.heightPixels / 2).coerceAtLeast(16); dens = dm.densityDpi / 2
        // Android 14+: the callback MUST be registered before the virtual display is created
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                val mine = projection === mp
                stop()
                if (mine) onEnded?.invoke()
            }
        }, Handler(Looper.getMainLooper()))
        val r = ImageReader.newInstance(pw, ph, PixelFormat.RGBA_8888, 2)
        reader = r
        vd = mp.createVirtualDisplay("nisu", pw, ph, dens,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
    }

    /** Screen rotated: resize the SAME virtual display (Android 14+ allows only one per projection token). */
    private fun followRotation() {
        val c = appCtx ?: return
        val v = vd ?: return
        val dm = metrics(c)
        val w = (dm.widthPixels / 2).coerceAtLeast(16); val h = (dm.heightPixels / 2).coerceAtLeast(16)
        if (w == pw && h == ph) return
        try {
            val old = reader
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            v.resize(w, h, dens)
            v.surface = r.surface
            reader = r; pw = w; ph = h
            try { old?.close() } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /** Stops capturing and forgets the last frame, so a stale picture is never sent after the share is turned off. */
    fun stop() {
        val p = projection
        projection = null
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { p?.stop() } catch (_: Exception) {}
        vd = null; reader = null; lastJpeg = null
    }

    fun grabJpeg(): ByteArray? {
        if (preferProjection && projection != null) { grabProjection()?.let { return it } }
        val acc = NisuAccessibilityService.inst
        if (Build.VERSION.SDK_INT >= 30 && acc != null) {
            var out: ByteArray? = null
            val l = CountDownLatch(1)
            try {
                acc.takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = r.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                            out = bmp?.copy(Bitmap.Config.ARGB_8888, false)?.let { toJpeg(it) }
                            hb.close()
                        } catch (_: Exception) {}
                        l.countDown()
                    }
                    override fun onFailure(errorCode: Int) { lastError = "screenshot refused, code $errorCode (1 internal, 2 no accessibility, 3 too fast, 4 invalid display, 5 secure window)"; l.countDown() }
                })
                l.await(2, TimeUnit.SECONDS)
            } catch (_: Exception) {}
            if (out != null) { lastJpeg = out; lastError = ""; return out }
            if (lastError.isBlank()) lastError = "no screenshot came back"
        }
        return grabProjection()
    }

    /** null when nothing new could be captured (the caller then simply sends nothing, never an old frame). */
    private fun grabProjection(): ByteArray? {
        if (projection == null) return null
        followRotation()
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val p = img.planes[0]
            val rowPad = p.rowStride - p.pixelStride * pw
            val full = Bitmap.createBitmap(pw + rowPad / p.pixelStride, ph, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            val j = toJpeg(Bitmap.createBitmap(full, 0, 0, pw, ph))
            lastJpeg = j
            return j
        } catch (_: Exception) {
            return null
        } finally { img.close() }
    }

    private fun toJpeg(b: Bitmap): ByteArray {
        val s = 800f / max(b.width, b.height)
        val sc = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        val o = ByteArrayOutputStream()
        sc.compress(Bitmap.CompressFormat.JPEG, 55, o)
        return o.toByteArray()
    }
}
