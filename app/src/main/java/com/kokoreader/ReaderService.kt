package com.kokoreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ReaderService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_STOP = "com.kokoreader.STOP"
        private const val CHANNEL_ID = "kokoreader"
        private const val NOTIF_ID = 1
        private const val TAG = "KokoReader"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private lateinit var windowManager: WindowManager
    private lateinit var worker: HandlerThread
    private lateinit var workerHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tts: KokoroTts
    @Volatile private var speaking = false

    override fun onCreate() {
        super.onCreate()
        try {
            worker = HandlerThread("koko-worker").also { it.start() }
            workerHandler = Handler(worker.looper)
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            // KokoroTts pre-warms the shared engine on its own worker thread;
            // the 134MB model is staged as a file + mmap'd (v0.3 OOM root cause fixed).
            tts = KokoroTts(this)
            Log.i(TAG, "service created")
        } catch (t: Throwable) {
            Log.e(TAG, "service onCreate failed", t)
            try {
                Toast.makeText(this, "KokoReader failed to start: ${t.message}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "explicit stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // Foreground start failed (e.g. FGS-not-allowed): keep the overlay up
            // anyway so the button never silently vanishes; capture will report why.
            Log.e(TAG, "startForeground failed, continuing with overlay only", e)
            uiToast("Foreground service blocked: ${e.message}")
        }
        val rc = intent?.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
            ?: android.app.Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (rc == android.app.Activity.RESULT_OK && data != null) {
            try {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(rc, data).also { mp ->
                    mp.registerCallback(object : MediaProjection.Callback() {
                        // System revoked capture (e.g. user stopped via status bar).
                        // Keep the overlay alive; only tear down the capture pipeline.
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection stopped by system; overlay stays up")
                            uiToast("Screen capture stopped — tap Start reading again")
                            teardownCapture()
                        }
                    }, workerHandler)
                }
                setupCapturePipeline()
                Log.i(TAG, "capture pipeline ready")
            } catch (e: Exception) {
                Log.e(TAG, "MediaProjection setup failed", e)
                uiToast("Screen capture failed: ${e.message}")
            }
        } else {
            Log.w(TAG, "started without fresh consent (restart?); overlay only")
        }
        // Overlay must survive every error path — never leave the user buttonless.
        showOverlay()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "KokoReader", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KokoReader running")
            .setContentText("Floating button active. Tap 🔊 to read the screen.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun setupCapturePipeline() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi
        imageReader?.close()
        virtualDisplay?.release()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "koko-capture", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, workerHandler
        )
    }

    private fun teardownCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        projection = null
    }

    // ---- Floating overlay: persistent + draggable ----

    private fun showOverlay() {
        if (overlayView != null) {
            Log.d(TAG, "overlay already shown")
            return
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 300
            }
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_controls, null)
            view.findViewById<Button>(R.id.overlayCapture).setOnClickListener { captureAndSpeak() }
            view.findViewById<Button>(R.id.overlayNext).setOnClickListener { captureAndSpeak() }
            makeDraggable(view, params)
            windowManager.addView(view, params)
            overlayView = view
            overlayParams = params
            Log.i(TAG, "overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "addView failed — overlay permission likely revoked", e)
            uiToast("Overlay blocked: grant 'Display over other apps', then Start reading again")
        }
    }

    /** Drag with tap disambiguation: movement beyond touch-slop = drag, else click. */
    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var downTime = 0L
        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    downTime = SystemClock.uptimeMillis()
                    false // let children still get press state; we decide on MOVE/UP
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "updateViewLayout failed", e)
                        }
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        dragging = false
                        // Swallow the UP so a drag-release doesn't trigger a button tap.
                        // Long-press (>500ms) without move is still a tap.
                        val dt = SystemClock.uptimeMillis() - downTime
                        if (dt < 500) true else { v.performClick(); false }
                    } else false
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    false
                }
                else -> false
            }
        }
    }

    // ---- Capture → OCR → speak ----

    /** MANUAL next-page only: each tap captures the current screen, OCRs it, speaks it. */
    private fun captureAndSpeak() {
        try {
            if (speaking) {
                Log.i(TAG, "tap while speaking → stop")
                try { tts.stop() } catch (t: Throwable) { Log.w(TAG, "stop failed", t) }
                speaking = false
                return
            }
        } catch (t: Throwable) {
            Log.e(TAG, "tap handler failed", t)
            uiToast("Tap failed: ${t.message}")
            return
        }
        val reader = imageReader
        if (reader == null) {
            Log.w(TAG, "tap with no capture pipeline")
            uiToast("Capture not ready — open KokoReader and tap Start reading first")
            return
        }
        workerHandler.post {
            // VirtualDisplay frames arrive asynchronously; the first tap after
            // consent often finds an empty queue. Retry briefly before giving up.
            var image: android.media.Image? = null
            repeat(15) {
                image = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    Log.w(TAG, "acquireLatestImage failed", e)
                    null
                }
                if (image != null) return@repeat
                try { Thread.sleep(100) } catch (_: InterruptedException) { return@repeat }
            }
            val img = image
            if (img == null) {
                Log.w(TAG, "no frame after 1.5s; asking user to retry")
                uiToast("No screen frame yet — wait a second and tap again")
                return@post
            }
            try {
                val bitmap = imageToBitmap(img)
                Log.i(TAG, "frame captured ${bitmap.width}x${bitmap.height}")
                runOcr(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "frame conversion failed", e)
                uiToast("Capture failed: ${e.message}")
            } finally {
                img.close()
            }
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
    }

    private fun runOcr(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                val text = visionText.text.trim()
                // No text persistence: text lives in RAM only, never written to disk.
                Log.i(TAG, "OCR ok, ${text.length} chars")
                if (text.isEmpty()) {
                    Toast.makeText(this, "No text found on this screen", Toast.LENGTH_SHORT).show()
                } else {
                    speaking = true
                    // KokoroTts always produces audio: ONNX voice, else Android system TTS.
                    tts.speak(text) {
                        speaking = false
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                Toast.makeText(this, "OCR failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun uiToast(msg: String) {
        mainHandler.post {
            try {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w(TAG, "toast failed: $msg", e)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the activity away must NOT remove the floating button.
        Log.i(TAG, "task removed; overlay stays")
    }

    override fun onDestroy() {
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        }
        overlayView = null
        overlayParams = null
        teardownCapture()
        try { tts.close() } catch (_: Exception) {}
        worker.quitSafely()
        Log.i(TAG, "service destroyed (explicit stop)")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private const val MEDIA_PROJECTION_SERVICE = Context.MEDIA_PROJECTION_SERVICE
private const val WINDOW_SERVICE = Context.WINDOW_SERVICE
private const val NOTIFICATION_SERVICE = Context.NOTIFICATION_SERVICE
