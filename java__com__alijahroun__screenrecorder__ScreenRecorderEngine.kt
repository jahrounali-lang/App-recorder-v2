package com.alijahroun.screenrecorder

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.io.IOException
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Screen (MediaProjection -> VirtualDisplay -> H.264 surface encoder) and optional audio
 * (device playback capture and/or microphone -> AAC) recorded into one MP4 through MediaMuxer.
 *
 * Pause/resume is implemented by dropping samples while paused and shifting later timestamps,
 * so the resulting file simply has no gap.
 */
class ScreenRecorderEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val caps: DeviceCaps,
    requested: CaptureConfig,
    private val audioMode: AudioMode,
    private val output: ParcelFileDescriptor
) {
    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHUNK_FRAMES = 1024
    }

    /** The configuration actually used (may be lower than requested if the encoder refused). */
    var config: CaptureConfig = requested
        private set

    /** Non-fatal notes for the user (e.g. "device audio unavailable"). */
    val warnings = mutableListOf<String>()

    val hasAudio: Boolean get() = audioReady

    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var muxer: SafeMuxer? = null

    private var audioCodec: MediaCodec? = null
    private var internalRec: AudioRecord? = null
    private var micRec: AudioRecord? = null
    private var audioReady = false

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var needKeyFrame = false
    @Volatile private var pausedTotalUs = 0L
    private var pauseStartUs = 0L
    private var recordStartUs = 0L
    @Volatile private var fatalError: Throwable? = null

    private var lastAudioPtsUs = -1L

    private fun nowUs() = System.nanoTime() / 1000

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        recordStartUs = nowUs()
        setupAudio()

        val (codec, usedConfig) = createVideoEncoder(config)
        config = usedConfig
        videoCodec = codec
        inputSurface = codec.createInputSurface()

        muxer = SafeMuxer(output.fileDescriptor, if (audioReady) 2 else 1)
        codec.start()

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecorder",
            config.width,
            config.height,
            config.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        ) ?: throw IOException("Could not create the virtual display")

        running = true
        videoThread = thread(name = "sr-video", isDaemon = true) { videoLoop(codec) }
        if (audioReady) audioThread = thread(name = "sr-audio", isDaemon = true) { audioLoop() }
    }

    fun pause() {
        if (!running || paused) return
        pauseStartUs = nowUs()
        paused = true
    }

    fun resume() {
        if (!running || !paused) return
        pausedTotalUs += nowUs() - pauseStartUs
        needKeyFrame = true
        try {
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            videoCodec?.setParameters(b)
        } catch (_: Exception) {
        }
        paused = false
    }

    /** Stops and finalises the file. Returns true when a playable MP4 was written. */
    fun stop(): Boolean {
        if (!running) {
            abort()
            return false
        }
        running = false
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            videoCodec?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        videoThread?.join(5000)
        audioThread?.join(5000)
        val ok = muxer?.stop() ?: false
        releaseCodecs()
        return ok
    }

    /** Throws everything away (used when start-up fails). */
    fun abort() {
        running = false
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        releaseCodecs()
        try {
            muxer?.stop()
        } catch (_: Exception) {
        }
    }

    private fun releaseCodecs() {
        try {
            videoCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            videoCodec?.release()
        } catch (_: Exception) {
        }
        videoCodec = null
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null
        try {
            audioCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            audioCodec?.release()
        } catch (_: Exception) {
        }
        audioCodec = null
        for (rec in listOf(internalRec, micRec)) {
            try {
                rec?.stop()
            } catch (_: Exception) {
            }
            try {
                rec?.release()
            } catch (_: Exception) {
            }
        }
        internalRec = null
        micRec = null
    }

    // ------------------------------------------------------------------ video

    private fun videoFormat(c: CaptureConfig): MediaFormat =
        MediaFormat.createVideoFormat(CaptureProfile.MIME, c.width, c.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, c.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, c.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Keep producing frames while the screen is static.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
        }

    /** Tries the requested config; if the encoder refuses it, steps down fps, then size. */
    private fun createVideoEncoder(requested: CaptureConfig): Pair<MediaCodec, CaptureConfig> {
        var current: CaptureConfig? = requested
        var attempts = 0
        while (current != null && attempts < 10) {
            attempts++
            val codec = MediaCodec.createByCodecName(caps.codecInfo.name)
            try {
                codec.configure(videoFormat(current), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                if (attempts > 1) {
                    warnings += "Encoder limits: recording at ${current.width}×${current.height} @ ${current.fps} fps"
                }
                return codec to current
            } catch (e: Exception) {
                try {
                    codec.release()
                } catch (_: Exception) {
                }
                current = CaptureProfile.degrade(current, caps)
            }
        }
        throw IOException("The video encoder could not be configured")
    }

    private fun videoLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var firstEncPts = -1L
        var firstArrivalRel = 0L
        var lastPts = -1L
        try {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        muxer?.addTrack(SafeMuxer.Kind.VIDEO, codec.outputFormat)

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

                        if (buffer != null && info.size > 0 && !isConfig) {
                            if (firstEncPts < 0) {
                                firstEncPts = info.presentationTimeUs
                                firstArrivalRel = nowUs() - recordStartUs
                            }
                            if (needKeyFrame && isKey) needKeyFrame = false
                            if (!paused && !needKeyFrame) {
                                var pts = info.presentationTimeUs - firstEncPts +
                                    firstArrivalRel - pausedTotalUs
                                if (pts <= lastPts) pts = lastPts + 1
                                lastPts = pts
                                info.presentationTimeUs = pts
                                muxer?.write(SafeMuxer.Kind.VIDEO, buffer, info)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (eos) break
                    }
                    // INFO_TRY_AGAIN_LATER and others: just poll again
                }
            }
        } catch (e: Exception) {
            fatalError = e
        }
    }

    // ------------------------------------------------------------------ audio

    private fun setupAudio() {
        if (audioMode == AudioMode.NONE) return
        val wantInternal = audioMode == AudioMode.INTERNAL || audioMode == AudioMode.BOTH
        val wantMic = audioMode == AudioMode.MIC || audioMode == AudioMode.BOTH

        if (wantInternal) {
            internalRec = buildInternalRecord()
            if (internalRec == null) {
                warnings += "Device audio capture is not available on this device"
            }
        }
        if (wantMic) {
            micRec = buildMicRecord()
            if (micRec == null) warnings += "Microphone could not be opened"
        }
        if (internalRec == null && micRec == null) return

        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioCodec = codec
            audioReady = true
        } catch (e: Exception) {
            warnings += "Audio encoder unavailable - recording without audio"
            for (rec in listOf(internalRec, micRec)) {
                try {
                    rec?.release()
                } catch (_: Exception) {
                }
            }
            internalRec = null
            micRec = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildInternalRecord(): AudioRecord? = try {
        val playback = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(playback)
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(min, CHUNK_FRAMES * 4 * 8))
            .build()
        if (rec.state == AudioRecord.STATE_INITIALIZED) rec else {
            rec.release()
            null
        }
    } catch (e: Exception) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun buildMicRecord(): AudioRecord? = try {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(min, CHUNK_FRAMES * 2 * 8))
            .build()
        if (rec.state == AudioRecord.STATE_INITIALIZED) rec else {
            rec.release()
            null
        }
    } catch (e: Exception) {
        null
    }

    /** Blocking read of exactly [count] shorts. Returns false on error. */
    private fun readFully(rec: AudioRecord, dst: ShortArray, count: Int): Boolean {
        var offset = 0
        while (offset < count) {
            val n = rec.read(dst, offset, count - offset, AudioRecord.READ_BLOCKING)
            if (n < 0) return false
            offset += n
        }
        return true
    }

    private fun audioLoop() {
        val codec = audioCodec ?: return
        val internal = internalRec
        val mic = micRec
        val internalBuf = ShortArray(CHUNK_FRAMES * 2)
        val micBuf = ShortArray(CHUNK_FRAMES)
        val mixed = ShortArray(CHUNK_FRAMES * 2)
        val info = MediaCodec.BufferInfo()
        var fedFrames = 0L
        var baseUs = -1L
        val chunkUs = CHUNK_FRAMES * 1_000_000L / SAMPLE_RATE

        try {
            internal?.startRecording()
            mic?.startRecording()

            while (running) {
                if (internal != null && !readFully(internal, internalBuf, CHUNK_FRAMES * 2)) break
                if (mic != null && !readFully(mic, micBuf, CHUNK_FRAMES)) break

                if (baseUs < 0) baseUs = max(0L, nowUs() - recordStartUs - chunkUs)
                if (paused) {
                    drainAudio(codec, info, untilEos = false)
                    continue
                }

                for (i in 0 until CHUNK_FRAMES) {
                    val m = if (mic != null) micBuf[i].toInt() else 0
                    val l = (if (internal != null) internalBuf[2 * i].toInt() else 0) + m
                    val r = (if (internal != null) internalBuf[2 * i + 1].toInt() else 0) + m
                    mixed[2 * i] = l.coerceIn(-32768, 32767).toShort()
                    mixed[2 * i + 1] = r.coerceIn(-32768, 32767).toShort()
                }
                val pts = baseUs + fedFrames * 1_000_000L / SAMPLE_RATE
                if (!queueAudio(codec, mixed, CHUNK_FRAMES * 2, pts, info)) break
                fedFrames += CHUNK_FRAMES
                drainAudio(codec, info, untilEos = false)
            }
        } catch (e: Exception) {
            fatalError = e
        } finally {
            try {
                // End of stream, then drain what is left.
                val idx = codec.dequeueInputBuffer(50_000)
                if (idx >= 0) {
                    codec.queueInputBuffer(
                        idx, 0, 0, baseUs.coerceAtLeast(0) + fedFrames * 1_000_000L / SAMPLE_RATE,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    drainAudio(codec, info, untilEos = true)
                }
            } catch (_: Exception) {
            }
            try {
                internal?.stop()
            } catch (_: Exception) {
            }
            try {
                mic?.stop()
            } catch (_: Exception) {
            }
        }
    }

    private fun queueAudio(
        codec: MediaCodec,
        data: ShortArray,
        shortCount: Int,
        ptsUs: Long,
        info: MediaCodec.BufferInfo
    ): Boolean {
        repeat(30) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx) ?: return false
                buf.clear()
                buf.order(ByteOrder.LITTLE_ENDIAN)
                buf.asShortBuffer().put(data, 0, shortCount)
                codec.queueInputBuffer(idx, 0, shortCount * 2, ptsUs, 0)
                return true
            }
            drainAudio(codec, info, untilEos = false)
        }
        return false
    }

    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo, untilEos: Boolean) {
        var idleRounds = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    muxer?.addTrack(SafeMuxer.Kind.AUDIO, codec.outputFormat)

                index >= 0 -> {
                    idleRounds = 0
                    val buffer = codec.getOutputBuffer(index)
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        var pts = info.presentationTimeUs
                        if (pts <= lastAudioPtsUs) pts = lastAudioPtsUs + 1
                        lastAudioPtsUs = pts
                        info.presentationTimeUs = pts
                        muxer?.write(SafeMuxer.Kind.AUDIO, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return
                }

                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    if (++idleRounds > 300) return // ~3 s without output
                }
            }
        }
    }
}
