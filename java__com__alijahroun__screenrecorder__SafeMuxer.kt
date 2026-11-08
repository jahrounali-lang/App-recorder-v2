package com.alijahroun.screenrecorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Thread-safe MediaMuxer wrapper: starts once all expected tracks exist and buffers
 * early samples until then.
 */
internal class SafeMuxer(fd: FileDescriptor, private val expectedTracks: Int) {

    enum class Kind { VIDEO, AUDIO }

    private class Pending(val kind: Kind, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val tracks = HashMap<Kind, Int>()
    private val pending = ArrayList<Pending>()
    private var started = false
    private var stopped = false

    @Synchronized
    fun addTrack(kind: Kind, format: MediaFormat) {
        if (stopped || tracks.containsKey(kind)) return
        tracks[kind] = muxer.addTrack(format)
        if (tracks.size == expectedTracks) startNow()
    }

    @Synchronized
    fun write(kind: Kind, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (stopped || info.size <= 0) return
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        if (started) {
            val track = tracks[kind] ?: return
            muxer.writeSampleData(track, buffer, info)
        } else {
            val copy = ByteBuffer.allocate(info.size)
            copy.put(buffer)
            copy.flip()
            val copyInfo = MediaCodec.BufferInfo()
            copyInfo.set(0, info.size, info.presentationTimeUs, info.flags)
            pending.add(Pending(kind, copy, copyInfo))
        }
    }

    private fun startNow() {
        muxer.start()
        started = true
        for (p in pending) {
            val track = tracks[p.kind] ?: continue
            muxer.writeSampleData(track, p.data, p.info)
        }
        pending.clear()
    }

    /** Finalises the file. Returns true when a playable MP4 was produced. */
    @Synchronized
    fun stop(): Boolean {
        if (stopped) return false
        stopped = true
        return try {
            // One track never arrived (e.g. no audio data): finish with what we have.
            if (!started && tracks.isNotEmpty()) startNow()
            if (started) {
                muxer.stop()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        } finally {
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }
    }
}
