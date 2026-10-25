package com.doubletap.screenshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/**
 * A transparent shim that only asks for MediaProjection consent. It must not
 * extend AppCompatActivity: its translucent theme is a framework theme, and
 * AppCompat throws at startup unless given a Theme.AppCompat descendant.
 */
class ScreenRecordActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 1000

        fun start(context: Context) {
            val intent = Intent(context, ScreenRecordActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ScreenRecordService.isRunning) {
            ScreenRecordService.stop(this)
            finish()
            return
        }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Asking plainly makes Android 14+ offer "entire screen or one app",
        // which is a choice nobody wants to make before every recording. Naming
        // the display up front reduces the prompt to a single confirmation.
        val capture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mgr.createScreenCaptureIntent()
        }

        @Suppress("DEPRECATION")
        startActivityForResult(capture, REQUEST_CODE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            ScreenRecordService.start(this, resultCode, data)
        }
        finish()
    }
}
