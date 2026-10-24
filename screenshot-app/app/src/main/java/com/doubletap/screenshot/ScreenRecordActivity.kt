package com.doubletap.screenshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
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
