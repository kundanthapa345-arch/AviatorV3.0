package com.example.aviatorv3

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val requestCode = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 40, 30, 30)
        }

        layout.addView(TextView(this).apply {
            text = "OCR Screen Reader"
            textSize = 22f
        })

        layout.addView(Button(this).apply {
            text = "Allow Floating Window"

            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })

        layout.addView(Button(this).apply {
            text = "Start Screen Reading"

            setOnClickListener {
                val manager =
                    getSystemService(MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager

                startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    requestCode
                )
            }
        })

        setContentView(layout)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != this.requestCode) {
            return
        }

        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(
                this,
                "Screen sharing was not approved",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val serviceIntent =
                Intent(this, CaptureService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }

            startForegroundService(serviceIntent)

            Toast.makeText(
                this,
                "Screen reader service starting...",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Service error: ${e.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
