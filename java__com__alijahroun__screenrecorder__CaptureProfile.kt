package com.alijahroun.screenrecorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What this device can really do (screen + H.264 encoder). */
class DeviceCaps(
    val screenWidth: Int,
    val screenHeight: Int,
    val densityDpi: Int,
    /** Highest refresh rate among the display modes. NOT the same thing as the video FPS. */
    val refreshHz: Float,
    val codecInfo: MediaCodecInfo
) {
    val videoCaps: MediaCodecInfo.VideoCapabilities
        get() = codecInfo.getCapabilitiesForType(CaptureProfile.MIME).videoCapabilities

    val isHardware: Boolean
        get() = codecInfo.isHardwareAccelerated && !codecInfo.isSoftwareOnly
}

/** Concrete recording parameters. */
data class CaptureConfig(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val fps: Int,
    val bitrate: Int
)

object CaptureProfile {
    const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    private val AUTO_FPS_STEPS = intArrayOf(120, 90, 60, 30, 24)

    // ---------------------------------------------------------------- probing

    @Suppress("DEPRECATION")
    fun probe(context: Context): DeviceCaps? {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val refresh = display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
        val codec = findEncoder() ?: return null
        return DeviceCaps(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            refreshHz = refresh,
            codecInfo = codec
        )
    }

    private fun findEncoder(): MediaCodecInfo? {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
        }
        return encoders.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly }
            ?: encoders.firstOrNull()
    }

    // ---------------------------------------------------------------- sizing

    private fun alignDown(value: Int, alignment: Int): Int {
        val a = max(alignment, 2)
        return value - value % a
    }

    /** Largest size <= (w0,h0) that the encoder accepts, keeping the aspect ratio. */
    fun fitSize(vc: MediaCodecInfo.VideoCapabilities, w0: Int, h0: Int): Pair<Int, Int>? {
        var w = alignDown(w0, vc.widthAlignment)
        var h = alignDown(h0, vc.heightAlignment)
        repeat(60) {
            if (w < 160 || h < 160) return null
            if (vc.isSizeSupported(w, h)) return w to h
            w = alignDown((w * 0.92f).toInt(), vc.widthAlignment)
            h = alignDown((h * 0.92f).toInt(), vc.heightAlignment)
        }
        return null
    }

    fun nativeSize(caps: DeviceCaps): Pair<Int, Int>? =
        fitSize(caps.videoCaps, caps.screenWidth, caps.screenHeight)

    private fun encoderMaxFps(caps: DeviceCaps, w: Int, h: Int): Int =
        runCatching { caps.videoCaps.getSupportedFrameRatesFor(w, h).upper.toInt() }
            .getOrElse { 30 }

    fun bitrateFor(w: Int, h: Int, fps: Int, caps: DeviceCaps): Int {
        val raw = (w.toLong() * h * fps * 0.07).toLong()
            .coerceIn(2_000_000L, 40_000_000L).toInt()
        return runCatching { caps.videoCaps.bitrateRange.clamp(raw) }.getOrDefault(raw)
    }

    // ---------------------------------------------------------------- options for the UI

    fun resolutionOptions(caps: DeviceCaps): List<ResolutionChoice> {
        val shortSide = min(caps.screenWidth, caps.screenHeight)
        val result = mutableListOf(ResolutionChoice.AUTO)
        listOf(ResolutionChoice.P480, ResolutionChoice.P720, ResolutionChoice.P1080).forEach { c ->
            if (c.height <= shortSide) {
                val scale = c.height.toFloat() / shortSide
                val size = fitSize(
                    caps.videoCaps,
                    (caps.screenWidth * scale).roundToInt(),
                    (caps.screenHeight * scale).roundToInt()
                )
                if (size != null) result += c
            }
        }
        val native = nativeSize(caps)
        if (native != null && shortSide !in listOf(480, 720, 1080)) result += ResolutionChoice.MAX
        return result
    }

    fun resolutionLabel(choice: ResolutionChoice, caps: DeviceCaps): String =
        if (choice == ResolutionChoice.MAX) {
            val n = nativeSize(caps)
            if (n != null) "Maximum (${n.first}×${n.second})" else choice.label
        } else choice.label

    /** 0 = automatic, followed by only the rates that are really possible. */
    fun fpsOptions(caps: DeviceCaps): List<Int> {
        val (w, h) = nativeSize(caps) ?: return listOf(0)
        val enc = encoderMaxFps(caps, w, h)
        val ceiling = min(enc, caps.refreshHz.roundToInt())
        val rates = intArrayOf(24, 30, 60, 90).filter { if (it <= 30) it <= enc else it <= ceiling }
        return listOf(0) + rates
    }

    // ---------------------------------------------------------------- resolving

    fun resolve(s: RecorderSettings, caps: DeviceCaps): CaptureConfig {
        val vc = caps.videoCaps
        val shortSide = min(caps.screenWidth, caps.screenHeight)

        val choice = if (s.automatic) ResolutionChoice.AUTO else s.resolution
        val targetShort = when (choice) {
            ResolutionChoice.AUTO, ResolutionChoice.MAX -> shortSide
            else -> min(choice.height, shortSide)
        }
        val scale = targetShort.toFloat() / shortSide
        val wanted = fitSize(
            vc,
            (caps.screenWidth * scale).roundToInt(),
            (caps.screenHeight * scale).roundToInt()
        ) ?: fitSize(vc, caps.screenWidth / 2, caps.screenHeight / 2)
        ?: error("The video encoder does not support any usable size")
        val (w, h) = wanted

        val ceiling = min(encoderMaxFps(caps, w, h), caps.refreshHz.roundToInt())
        val fps = if (s.automatic || s.fps == 0) {
            AUTO_FPS_STEPS.firstOrNull { it <= ceiling } ?: max(ceiling, 15)
        } else {
            min(s.fps, max(ceiling, 24))
        }
        return CaptureConfig(w, h, caps.densityDpi, fps, bitrateFor(w, h, fps, caps))
    }

    /** A slightly cheaper config to try when the encoder refuses [c]; null when nothing is left. */
    fun degrade(c: CaptureConfig, caps: DeviceCaps): CaptureConfig? {
        val lowerFps = intArrayOf(90, 60, 30, 24).firstOrNull { it < c.fps }
        if (lowerFps != null) {
            return c.copy(fps = lowerFps, bitrate = bitrateFor(c.width, c.height, lowerFps, caps))
        }
        val size = fitSize(caps.videoCaps, (c.width * 0.75f).toInt(), (c.height * 0.75f).toInt())
            ?: return null
        if (size.first >= c.width) return null
        return c.copy(
            width = size.first,
            height = size.second,
            bitrate = bitrateFor(size.first, size.second, c.fps, caps)
        )
    }
}
