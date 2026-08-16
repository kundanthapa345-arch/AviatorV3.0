package com.example.aviatorv3

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.*
import android.view.*
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.min

class CaptureService : Service() {
    private lateinit var projection: MediaProjection
    private lateinit var reader: ImageReader
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var overlay: TextView? = null
    private var windowManager: WindowManager? = null
    private val history = ArrayDeque<Double>()
    private var lastValue: Double? = null
    private var round = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode =
            intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                ?: return START_NOT_STICKY
        val data =
            intent.getParcelableExtra<Intent>("data")
                ?: return START_NOT_STICKY

        createNotification()

        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager

        projection = manager.getMediaProjection(resultCode, data)
        createOverlay()
        startCapture()

        return START_STICKY
    }

    private fun createNotification() {
        val channel = NotificationChannel(
            "v3",
            "Aviator V3",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        val notification = Notification.Builder(this, "v3")
            .setContentTitle("Aviator V3")
            .setContentText("OCR is running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        startForeground(1001, notification)
    }

    private fun startCapture() {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels

        reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        reader.setOnImageAvailableListener({ source ->
            val image =
                source.acquireLatestImage()
                    ?: return@setOnImageAvailableListener

            try {
                val bitmap =
                    Bitmap.createBitmap(
                        width,
                        height,
                        Bitmap.Config.ARGB_8888
                    )

                bitmap.copyPixelsFromBuffer(
                    image.planes[0].buffer
                )

                recognize(bitmap)
            } finally {
                image.close()
            }
        }, handler)

        projection.createVirtualDisplay(
            "AviatorV3",
            width,
            height,
            dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
    }

    private fun recognize(bitmap: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->

                val regex =
                    Regex("""(?<![\d.])(\d+(?:\.\d+)?)\s*x""")

                val value =
                    regex.findAll(result.text)
                        .mapNotNull {
                            it.groupValues[1].toDoubleOrNull()
                        }
                        .filter {
                            it > 0 && it < 1_000_000
                        }
                        .lastOrNull()

                if (value != null && value != lastValue) {
                    lastValue = value
                    round++

                    history.addLast(value)

                    while (history.size > 500) {
                        history.removeFirst()
                    }

                    updatePrediction()
                }
            }
    }

    private fun updatePrediction() {
        if (history.isEmpty()) return

        val recent =
            history.takeLast(min(20, history.size))

        val sorted = recent.sorted()
        val median = sorted[sorted.size / 2]
        val average = recent.average()

        val estimate =
            max(
                1.01,
                min(
                    20.0,
                    median * 0.65 + average * 0.35
                )
            )

        overlay?.text =
            "V3 • Round #$round\n" +
            "Estimated: %.2fx\n".format(estimate) +
            "Rounds: ${history.size}"
    }

    private fun createOverlay() {
        windowManager =
            getSystemService(WINDOW_SERVICE)
                as WindowManager

        overlay = TextView(this).apply {
            text = "V3\nWaiting..."
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(18, 14, 18, 14)
            setBackgroundColor(
                Color.argb(235, 15, 23, 42)
            )
        }

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

        params.gravity = Gravity.TOP or Gravity.END
        params.x = 12
        params.y = 120

        windowManager?.addView(overlay, params)
    }

    override fun onDestroy() {
        reader.close()
        projection.stop()
        overlay?.let {
            windowManager?.removeView(it)
        }
        recognizer.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
