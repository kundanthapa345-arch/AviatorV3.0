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

        imageReader =
            ImageReader.newInstance(
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

                val image =
                    reader.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                try {

                    val plane = image.planes[0]

                    val bitmap =
                        android.graphics.Bitmap.createBitmap(
                            width,
                            height,
                            android.graphics.Bitmap.Config.ARGB_8888
                        )

                    bitmap.copyPixelsFromBuffer(
                        plane.buffer
                    )

                    readText(bitmap)

                    bitmap.recycle()

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

    private fun readText(
        bitmap: android.graphics.Bitmap
    ) {

        val input =
            InputImage.fromBitmap(bitmap, 0)

        recognizer
            .process(input)
            .addOnSuccessListener { result ->

                val text =
                    result.text.trim()

                if (text.isNotEmpty()) {
                    showResult(text)
                }
            }
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
