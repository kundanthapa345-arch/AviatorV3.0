package com.example.aviatorv3

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class CaptureService : Service() {
    private var overlayView: android.widget.TextView? = null
    private var overlayWindowManager: android.view.WindowManager? = null



    private lateinit var projection: MediaProjection
    private lateinit var imageReader: ImageReader

    private val handler = Handler(Looper.getMainLooper())

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val resultCode =
            intent?.getIntExtra(
                "resultCode",
                Activity.RESULT_CANCELED
            ) ?: return START_NOT_STICKY

        val data =
            intent.getParcelableExtra<Intent>("data")
                ?: return START_NOT_STICKY

        try {
            if (android.provider.Settings.canDrawOverlays(this)) {
    showOverlay()
}
createNotification()

            val manager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as android.media.projection.MediaProjectionManager

            projection =
                manager.getMediaProjection(
                    resultCode,
                    data
                )

            startCapture()

            return START_STICKY

        } catch (e: Exception) {
            showCrashError(e)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun createNotification() {

        val channel = NotificationChannel(
            "ocr",
            "OCR Screen Reader",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(channel)

        val notification =
            Notification.Builder(this, "ocr")
                .setContentTitle("Aviator V3 OCR Tester")
                .setContentText("Screen reading is running")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()

        startForeground(1001, notification)
    }

    private fun showCrashError(e: Exception) {
        val message = e.stackTraceToString().take(3500)

        val notification =
            Notification.Builder(this, "ocr")
                .setContentTitle("OCR diagnostic error")
                .setContentText(e.javaClass.simpleName)
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(message)
                )
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .build()

        getSystemService(NotificationManager::class.java)
            .notify(9999, notification)
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (::imageReader.isInitialized) {
                        imageReader.close()
                    }
                    stopSelf()
                }
            },
            handler
        )

        imageReader.setOnImageAvailableListener(
            { reader ->
                val image = reader.acquireLatestImage()
                    ?: return@setOnImageAvailableListener

                try {
                    showFrameReceived()

                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding =
                        rowStride - pixelStride * width

                    val bitmapWidth =
                        width + rowPadding / pixelStride

                    val bitmap =
                        android.graphics.Bitmap.createBitmap(
                            bitmapWidth,
                            height,
                            android.graphics.Bitmap.Config.ARGB_8888
                        )

                    bitmap.copyPixelsFromBuffer(buffer)

                    android.util.Log.d("OCR_DEBUG", "FRAME: ${width}x${height}, density=$density")
        val cropped =
                        if (bitmapWidth != width) {
                            android.graphics.Bitmap.createBitmap(
                                bitmap,
                                0,
                                0,
                                width,
                                height
                            )
                        } else {
                            bitmap
                        }

                    readText(cropped)

                    if (cropped !== bitmap) {
                        cropped.recycle()
                    }

                    bitmap.recycle()
                } catch (e: Exception) {
                    showCrashError(e)
                } finally {
                    image.close()
                }
            },
            handler
        )

        projection.createVirtualDisplay(
            "OCR-Screen-Reader",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler
        )
    }

    private fun showFrameReceived() {
        val notification =
            Notification.Builder(this, "ocr")
                .setContentTitle("OCR diagnostic")
                .setContentText("Screen frame received")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setAutoCancel(true)
                .build()

        getSystemService(NotificationManager::class.java)
            .notify(3000, notification)
    }

    private var ocrBusy = false

    private fun readText(bitmap: android.graphics.Bitmap) {
        if (ocrBusy) {
            return
        }

        ocrBusy = true

        val safeBitmap = bitmap.copy(
            android.graphics.Bitmap.Config.ARGB_8888,
            false
        )

        val input = InputImage.fromBitmap(safeBitmap, 0)

        recognizer.process(input)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
            val multiplier = extractMultiplier(text)
            if (multiplier != null) {
                saveRound(multiplier)
            updateEstimate()
                showResult("LAST ROUND: $multiplier")
            } else if (text.isNotEmpty()) {
                showResult(text)
            }

                if (text.isNotEmpty()) {
                    showResult(text)
                }
            }
            .addOnFailureListener { e ->
                showCrashError(e)
            }
            .addOnCompleteListener {
                ocrBusy = false
                safeBitmap.recycle()
            }
    }

    
private fun saveRound(multiplier: String) {
    val prefs = getSharedPreferences("round_history", MODE_PRIVATE)
    val oldJson = prefs.getString("history", "[]") ?: "[]"

    try {
        val array = org.json.JSONArray(oldJson)

        // Same latest multiplier ko repeatedly save na karein
        if (array.length() > 0) {
            val last = array.getJSONObject(array.length() - 1)
            if (last.optString("multiplier") == multiplier) return
        }

        val item = org.json.JSONObject()
        item.put("round", array.length() + 1)
        item.put("multiplier", multiplier)
        item.put("timestamp", System.currentTimeMillis())

        array.put(item)

        // Maximum 1000 rounds maintain karein
        while (array.length() > 1000) {
            array.remove(0)
        }

        // Round numbers ko 1-1000 sequence me rakhein
        for (i in 0 until array.length()) {
            array.getJSONObject(i).put("round", i + 1)
        }

        prefs.edit()
            .putString("history", array.toString())
            .apply()

    } catch (e: Exception) {
        showCrashError(e)
    }
}

private fun updateEstimate() {
    try {
        val prefs = getSharedPreferences("round_history", MODE_PRIVATE)
        val json = prefs.getString("history", "[]") ?: "[]"
        val array = org.json.JSONArray(json)

        val rounds = mutableListOf<Double>()

        for (i in 0 until array.length()) {
            val value = array.getJSONObject(i)
                .optString("multiplier")
                .replace("x", "", ignoreCase = true)
                .toDoubleOrNull()

            if (value != null) {
                rounds.add(value)
            }
        }

        val result = PredictionEngine.estimate(rounds)

        if (result.estimatedX > 0.0) {
            updateOverlay(
                rounds.lastOrNull()?.let { "${it}x" } ?: "--",
                result.estimatedX,
                result.confidence
            )

            showResult(
                "LAST ROUND: ${rounds.lastOrNull() ?: 0.0}x\n" +
                "ESTIMATED X: %.2fx\n".format(result.estimatedX) +
                "CONFIDENCE: ${result.confidence}%"
            )
        }
    } catch (e: Exception) {
        showCrashError(e)
    }
}


private fun showOverlay() {
    if (overlayView != null) return

    val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
    overlayWindowManager = wm

    val view = android.widget.TextView(this).apply {
        text = "LAST ROUND: --\\nESTIMATED X: --\\nCONFIDENCE: --"
        textSize = 14f
        setPadding(18, 12, 18, 12)
        setTextColor(android.graphics.Color.WHITE)
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    val params = android.view.WindowManager.LayoutParams(
        android.view.WindowManager.LayoutParams.WRAP_CONTENT,
        android.view.WindowManager.LayoutParams.WRAP_CONTENT,
        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        android.graphics.PixelFormat.TRANSLUCENT
    )

    params.gravity = android.view.Gravity.TOP or android.view.Gravity.END
    params.x = 16
    params.y = 120

    wm.addView(view, params)
    overlayView = view
}

private fun updateOverlay(lastRound: String, estimatedX: Double, confidence: Int) {
    overlayView?.text =
        "LAST ROUND: $lastRound\\n" +
        "ESTIMATED X: %.2fx\\n".format(estimatedX) +
        "CONFIDENCE: $confidence%"
}

private fun hideOverlay() {
    try {
        overlayView?.let { overlayWindowManager?.removeView(it) }
    } catch (_: Exception) {
    }

    overlayView = null
    overlayWindowManager = null
}

private fun extractMultiplier(text: String): String? {
    val normalized = text
        .replace(",", ".")
        .replace(" ", "")

    val regex = Regex(
        """(?<![0-9.])(\d{1,3}(?:\.\d{1,2})?)\s*[xX](?!\w)"""
    )

    val match = regex.find(normalized) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null

    if (value < 1.0 || value > 1000.0) return null

    return "${match.groupValues[1]}x"
}

private fun showResult(text: String) {

        val shortText =
            if (text.length > 120) {
                text.take(120) + "..."
            } else {
                text
            }

        val notification =
            Notification.Builder(
                this,
                "ocr"
            )
                .setContentTitle("OCR detected text")
                .setContentText(shortText)
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(text)
                )
                .setSmallIcon(
                    android.R.drawable.ic_menu_view
                )
                .setAutoCancel(true)
                .build()

        getSystemService(
            NotificationManager::class.java
        ).notify(
            2000,
            notification
        )
    }

    override fun onDestroy() {

        if (::imageReader.isInitialized) {
            imageReader.close()
        }

        if (::projection.isInitialized) {
            projection.stop()
        }

        recognizer.close()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
