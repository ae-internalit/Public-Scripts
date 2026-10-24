package com.doubletap.screenshot

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer

/**
 * Screen recorder built on MediaCodec and MediaMuxer rather than MediaRecorder,
 * because MediaRecorder accepts only a microphone source and cannot consume the
 * AudioRecord that playback capture produces. Encoding the tracks separately and
 * muxing them is the only way to record the audio playing inside other apps.
 */
class ScreenRecorder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val density: Int,
    private val outputPath: String,
    private val audioMode: String,
    private val onError: (Throwable) -> Unit,
    private val onProjectionLost: () -> Unit = {}
) {

    companion object {
        const val AUDIO_NONE = "none"
        const val AUDIO_MIC = "mic"
        const val AUDIO_DEVICE = "device"
        const val AUDIO_BOTH = "both"

        private const val SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128_000
        private const val VIDEO_BITRATE = 6_000_000
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 2
        private const val TIMEOUT_US = 10_000L

        /** Playback capture needs API 29; below that only the mic is reachable. */
        fun deviceAudioSupported(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var muxer: MediaMuxer? = null

    private var deviceRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    @Volatile private var muxerEverStarted = false
    private val muxerLock = Object()

    @Volatile private var running = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private val wantsDevice =
        (audioMode == AUDIO_DEVICE || audioMode == AUDIO_BOTH) && deviceAudioSupported()
    private val wantsMic = audioMode == AUDIO_MIC || audioMode == AUDIO_BOTH
    private val hasAudio = wantsDevice || wantsMic
    private val channelCount = if (wantsDevice) 2 else 1

    fun start() {
        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Required from API 34: createVirtualDisplay throws without a callback
        // registered first. onStop also fires when the user revokes capture
        // from the system UI, which must tear the recording down.
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                onProjectionLost()
            }
        }
        projectionCallback?.let { projection.registerCallback(it, Handler(Looper.getMainLooper())) }

        startVideo()
        if (hasAudio) startAudio()

        running = true

        videoThread = Thread { drainVideo() }.apply { start() }
        if (hasAudio) {
            audioThread = Thread { pumpAudio() }.apply { start() }
        }
    }

    private fun startVideo() {
        // Round to even dimensions; H.264 rejects odd sizes on many encoders.
        val w = width and 1.inv()
        val h = height and 1.inv()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        videoEncoder = encoder

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecord", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
    }

    private fun startAudio() {
        val channelMask =
            if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        audioEncoder = encoder

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE)

        if (wantsDevice) {
            deviceRecord = buildPlaybackRecord(channelMask, minBuffer)
            deviceRecord?.startRecording()
        }
        if (wantsMic) {
            micRecord = buildMicRecord(minBuffer)
            micRecord?.startRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildPlaybackRecord(channelMask: Int, minBuffer: Int): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        // Apps may opt out of being captured, so some sources stay silent.
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        return AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun buildMicRecord(minBuffer: Int): AudioRecord =
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2
        )

    private fun maybeStartMuxer() {
        synchronized(muxerLock) {
            if (muxerStarted) return
            if (videoTrack < 0) return
            if (hasAudio && audioTrack < 0) return
            muxer?.start()
            muxerStarted = true
            muxerEverStarted = true
            muxerLock.notifyAll()
        }
    }

    private fun drainVideo() {
        val encoder = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            videoTrack = muxer?.addTrack(encoder.outputFormat) ?: -1
                        }
                        maybeStartMuxer()
                    }
                    index >= 0 -> {
                        writeSample(encoder, index, info, videoTrack)
                        // Set by signalEndOfInputStream in stop(); everything
                        // the encoder held has now been muxed.
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun writeSample(
        encoder: MediaCodec, index: Int, info: MediaCodec.BufferInfo, track: Int
    ) {
        val buffer = encoder.getOutputBuffer(index)
        if (buffer != null && info.size > 0 && track >= 0) {
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                synchronized(muxerLock) {
                    if (muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer?.writeSampleData(track, buffer, info)
                    }
                }
            }
        }
        encoder.releaseOutputBuffer(index, false)
    }

    /** Reads PCM, mixes the two sources when both are on, and feeds the encoder. */
    private fun pumpAudio() {
        val encoder = audioEncoder ?: return
        val info = MediaCodec.BufferInfo()
        val chunkFrames = 1024
        val deviceBuf = ShortArray(chunkFrames * channelCount)
        val micBuf = ShortArray(chunkFrames)
        var framesWritten = 0L

        try {
            while (running) {
                var read = 0

                if (wantsDevice) {
                    read = deviceRecord?.read(deviceBuf, 0, deviceBuf.size) ?: 0
                    if (read <= 0) {
                        drainAudio(encoder, info)
                        continue
                    }
                    if (wantsMic) {
                        val frames = read / channelCount
                        val micRead = micRecord?.read(micBuf, 0, frames) ?: 0
                        for (i in 0 until minOf(frames, micRead)) {
                            val m = micBuf[i].toInt()
                            for (c in 0 until channelCount) {
                                val pos = i * channelCount + c
                                deviceBuf[pos] = clamp(deviceBuf[pos] + m)
                            }
                        }
                    }
                } else {
                    read = micRecord?.read(deviceBuf, 0, deviceBuf.size) ?: 0
                    if (read <= 0) {
                        drainAudio(encoder, info)
                        continue
                    }
                }

                val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val input: ByteBuffer? = encoder.getInputBuffer(inIndex)
                    if (input != null) {
                        input.clear()
                        input.asShortBuffer().put(deviceBuf, 0, read)
                        val bytes = read * 2
                        val pts = framesWritten * 1_000_000L / SAMPLE_RATE
                        encoder.queueInputBuffer(inIndex, 0, bytes, pts, 0)
                        framesWritten += read / channelCount
                    } else {
                        encoder.queueInputBuffer(inIndex, 0, 0, 0, 0)
                    }
                }

                drainAudio(encoder, info)
            }
        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun clamp(value: Int): Short =
        when {
            value > Short.MAX_VALUE -> Short.MAX_VALUE
            value < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> value.toShort()
        }

    private fun drainAudio(encoder: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        audioTrack = muxer?.addTrack(encoder.outputFormat) ?: -1
                    }
                    maybeStartMuxer()
                }
                index >= 0 -> writeSample(encoder, index, info, audioTrack)
                else -> return
            }
        }
    }

    fun stop() {
        // Flush the encoder before tearing anything down: `running` stays true
        // so the video drain keeps going until it sees end-of-stream, which is
        // what finalises the trailing frames and the file's moov atom.
        try { videoEncoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { videoThread?.join(3000) } catch (_: InterruptedException) {}

        running = false
        try { audioThread?.join(2000) } catch (_: InterruptedException) {}

        try { deviceRecord?.stop() } catch (_: Exception) {}
        try { micRecord?.stop() } catch (_: Exception) {}
        try { deviceRecord?.release() } catch (_: Exception) {}
        try { micRecord?.release() } catch (_: Exception) {}
        deviceRecord = null
        micRecord = null

        try { videoEncoder?.stop() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        videoEncoder = null

        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null

        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        synchronized(muxerLock) {
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            muxerStarted = false
        }

        projectionCallback?.let {
            try { projection.unregisterCallback(it) } catch (_: Exception) {}
        }
        projectionCallback = null

        try { projection.stop() } catch (_: Exception) {}
    }

    /** True when the muxer actually received data; an empty file is not a recording. */
    fun producedOutput(): Boolean = muxerEverStarted
}
