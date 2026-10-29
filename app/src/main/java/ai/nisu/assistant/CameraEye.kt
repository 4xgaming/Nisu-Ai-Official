package ai.nisu.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * v2.62: NISU AI's eye. Opens the phone camera (back or front) with the plain Camera2 API and keeps the newest frame as a small JPEG
 * (about one per second). AssistantService sends those frames to Gemini Live like the screen share does, so she can describe what is
 * in front of the camera, live. Nothing is recorded or saved; the frame lives in memory only and is replaced by the next one.
 */
object CameraEye {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var dev: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var rotation = 0
    @Volatile private var last: ByteArray? = null
    @Volatile private var lastAt = 0L
    @Volatile private var sentAt = 0L
    @Volatile var onLost: ((String) -> Unit)? = null

    /** The newest frame, but only once (null when it was already taken): the caller sends every frame one time. */
    fun fresh(): ByteArray? {
        val f = last ?: return null
        if (lastAt == sentAt) return null
        sentAt = lastAt
        return f
    }

    /** Opens the camera. Returns null when frames are arriving, else a short reason. Blocks up to about 6 seconds. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun start(ctx: Context, facing: String): String? {
        stop()
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (facing.lowercase().startsWith("f")) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = try { mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want } } catch (e: Exception) { null }
            ?: return "This phone has no " + (if (want == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back") + " camera."
        val ch = mgr.getCameraCharacteristics(id)
        rotation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
        val size = sizes?.filter { it.width <= 1280 }?.maxByOrNull { it.width * it.height } ?: sizes?.minByOrNull { it.width * it.height }
            ?: return "The camera gave no usable picture size."

        val t = HandlerThread("nisu-eye").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        last = null; lastAt = 0L; sentAt = 0L
        val first = CountDownLatch(1)
        var error: String? = null

        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastAt >= 900L) {
                    last = toJpeg(img); lastAt = now
                    first.countDown()
                }
            } catch (_: Exception) {
            } finally { img.close() }
        }, h)

        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    dev = c
                    try {
                        c.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val req = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    req.addTarget(r.surface)
                                    req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    s.setRepeatingRequest(req.build(), null, h)
                                } catch (e: Exception) { error = "The camera could not start: " + (e.message ?: e.javaClass.simpleName).take(80); first.countDown() }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { error = "The camera could not be configured."; first.countDown() }
                        }, h)
                    } catch (e: Exception) { error = "The camera could not start: " + (e.message ?: e.javaClass.simpleName).take(80); first.countDown() }
                }
                override fun onDisconnected(c: CameraDevice) {
                    error = "The camera was taken by another app."; first.countDown()
                    val cb = onLost; stop(); cb?.invoke("The camera was taken by another app.")
                }
                override fun onError(c: CameraDevice, code: Int) {
                    error = "The camera is not available (error $code). Another app may be using it, or Android blocks the camera in the background."; first.countDown()
                    val cb = onLost; stop(); cb?.invoke("camera error $code")
                }
            }, h)
        } catch (e: SecurityException) {
            stop(); return "Camera permission is missing."
        } catch (e: Exception) {
            stop(); return "The camera could not be opened: " + (e.message ?: e.javaClass.simpleName).take(80)
        }
        val got = first.await(6, TimeUnit.SECONDS)
        if (error != null) { val e = error; stop(); return e }
        if (!got || last == null) { stop(); return "No picture arrived from the camera." }
        return null
    }

    fun stop() {
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { dev?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; dev = null; reader = null
        try { thread?.quitSafely() } catch (_: Exception) {}
        thread = null; handler = null
        last = null
    }

    private fun toJpeg(img: Image): ByteArray {
        val w = img.width; val h = img.height
        val nv = ByteArray(w * h * 3 / 2)
        val yP = img.planes[0]
        var pos = 0
        for (row in 0 until h) { yP.buffer.position(row * yP.rowStride); yP.buffer.get(nv, pos, w); pos += w }
        val uP = img.planes[1]; val vP = img.planes[2]
        val ps = uP.pixelStride; val rs = uP.rowStride
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            val i = row * rs + col * ps
            nv[pos++] = vP.buffer.get(i); nv[pos++] = uP.buffer.get(i)
        }
        val raw = ByteArrayOutputStream()
        YuvImage(nv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 70, raw)
        var bmp = BitmapFactory.decodeByteArray(raw.toByteArray(), 0, raw.size()) ?: return raw.toByteArray()
        val m = Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        val s = 800f / max(bmp.width, bmp.height)
        if (s < 1f) m.postScale(s, s)
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
        return out.toByteArray()
    }
}
