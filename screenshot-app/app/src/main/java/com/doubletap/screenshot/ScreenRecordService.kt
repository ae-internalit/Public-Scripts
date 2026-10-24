package com.doubletap.screenshot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.widget.Toast
import java.io.File

class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var isRecording = false
    private var outputFile: File? = null

    companion object {
        const val ACTION_START = "com.doubletap.screenshot.RECORD_START"
        const val ACTION_STOP = "com.doubletap.screenshot.RECORD_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "screen_record"
        private const val NOTIFICATION_ID = 2

        var isRunning = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val mode = enterForeground()
                    if (mode != null) {
                        startRecording(resultCode, data, mode)
                    } else {
                        stopSelf()
                    }
                } else {
                    stopSelf()
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Screen Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while recording screen"
            setShowBadge(false)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(recording: Boolean): Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (recording) "Recording Screen..." else "Screen Record"
        val text = if (recording) "Tap Stop to save recording" else "Ready to record"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopPending
                ).build()
            )
            .build()
    }

    private fun audioMode(): String {
        val prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
        val mode = prefs.getString(ScreenshotService.KEY_AUDIO_MODE, ScreenRecorder.AUDIO_DEVICE)
            ?: ScreenRecorder.AUDIO_DEVICE
        // Playback capture goes through AudioRecord, so every audio mode needs
        // RECORD_AUDIO, not just the microphone ones.
        if (mode != ScreenRecorder.AUDIO_NONE && !hasMicPermission()) {
            android.os.Handler(mainLooper).post {
                Toast.makeText(this, R.string.record_audio_needed, Toast.LENGTH_LONG).show()
            }
            return ScreenRecorder.AUDIO_NONE
        }
        return mode
    }

    /**
     * Android 14 validates every foreground-service type named here, so asking
     * for `microphone` without RECORD_AUDIO rejects the whole service rather
     * than just the audio. Request the microphone type only when audio will
     * actually be captured, and drop to silent video if it is refused anyway.
     *
     * Returns the audio mode the recording should use, or null if the service
     * could not enter the foreground at all.
     */
    private fun enterForeground(): String? {
        val mode = audioMode()

        if (tryForeground(mode)) return mode
        if (mode == ScreenRecorder.AUDIO_NONE) return null

        // Audio was refused at the service level; silent video still beats nothing.
        return if (tryForeground(ScreenRecorder.AUDIO_NONE)) {
            android.os.Handler(mainLooper).post {
                Toast.makeText(this, R.string.record_audio_refused, Toast.LENGTH_LONG).show()
            }
            ScreenRecorder.AUDIO_NONE
        } else {
            null
        }
    }

    private fun tryForeground(mode: String): Boolean {
        val types = if (mode == ScreenRecorder.AUDIO_NONE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(true), types)
            true
        } catch (e: Exception) {
            reportError(e)
            false
        }
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun startRecording(resultCode: Int, data: Intent, mode: String) {
        if (isRecording) return

        val metrics = resources.displayMetrics
        val file = File(cacheDir, "screen_record_${System.currentTimeMillis()}.mp4")
        outputFile = file

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("No MediaProjection")
            mediaProjection = projection

            val rec = ScreenRecorder(
                projection = projection,
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                density = metrics.densityDpi,
                outputPath = file.absolutePath,
                audioMode = mode,
                onError = { error -> reportError(error) }
            )
            rec.start()
            recorder = rec
            isRecording = true
            isRunning = true
        } catch (e: Exception) {
            reportError(e)
            cleanup()
            stopSelf()
        }
    }

    private fun reportError(error: Throwable) {
        ScreenshotService.instance?.recordError("screen recording", error)
        android.os.Handler(mainLooper).post {
            Toast.makeText(this, "Recording error: $error", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            stopSelf()
            return
        }
        isRecording = false
        isRunning = false

        try {
            recorder?.stop()
        } catch (_: Exception) {}

        cleanup()
        saveToGallery()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        recorder = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun saveToGallery() {
        val file = outputFile ?: return
        if (!file.exists() || file.length() == 0L) return

        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "ScreenRecord_${System.currentTimeMillis()}")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Screenshots")
            }

            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
            file.delete()

            android.os.Handler(mainLooper).post {
                Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            android.os.Handler(mainLooper).post {
                Toast.makeText(this, "Failed to save recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        if (isRecording) stopRecording()
        isRunning = false
        super.onDestroy()
    }
}
