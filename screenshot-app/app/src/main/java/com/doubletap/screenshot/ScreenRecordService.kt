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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
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
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
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
                    startForeground(NOTIFICATION_ID, buildNotification(true))
                    startRecording(resultCode, data)
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

    private fun startRecording(resultCode: Int, data: Intent) {
        if (isRecording) return

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val file = File(cacheDir, "screen_record_${System.currentTimeMillis()}.mp4")
        outputFile = file

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(6_000_000)
                setOutputFile(file.absolutePath)
                prepare()
            }
            mediaRecorder = recorder

            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecord", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface, null, null
            )

            recorder.start()
            isRecording = true
            isRunning = true
        } catch (e: Exception) {
            android.os.Handler(mainLooper).post {
                Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
            }
            cleanup()
            stopSelf()
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
            mediaRecorder?.stop()
        } catch (_: Exception) {}

        cleanup()
        saveToGallery()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        mediaRecorder?.release()
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
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
