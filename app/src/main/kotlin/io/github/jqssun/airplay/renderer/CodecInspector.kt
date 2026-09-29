package io.github.jqssun.airplay.renderer

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log

/** Snapshot of one decoder as reported by MediaCodecList (plain data, safe to keep in UI state). */
data class CodecEntry(
    val name: String,
    val mime: String,
    val codecClass: CodecClass,
    // raw platform flags, null below API 29 (platform does not report them)
    val hardwareAccelerated: Boolean?,
    val softwareOnly: Boolean?,
    val vendor: Boolean?,
    val alias: Boolean?,
    val maxWidth: Int,
    val maxHeight: Int,
    // highest frame rate the codec declares at 1920x1080 (or its max size if smaller), null if unsupported
    val maxFps1080p: Double?,
    // measured/achievable frame rate at 1080p published by the vendor (API 23+), often null
    val achievableFps1080p: Double?,
    // MediaCodecInfo.VideoCapabilities.PerformancePoint list (API 29+), empty if not published
    val performancePoints: List<String>,
    val lowLatencyFeature: Boolean,
    val adaptivePlayback: Boolean,
) {
    val codecLabel: String get() = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "H.264"
}

/** Result of actually instantiating a decoder (configure + start + stop + release). */
data class ProbeResult(val entry: CodecEntry, val width: Int, val height: Int, val ok: Boolean, val millis: Long, val error: String?)

object CodecInspector {
    private const val TAG = "CodecInspector"
    val VIDEO_MIMES = listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)

    /** every H.264/HEVC decoder on the device, best first (vendor HW > android HW > SW) */
    fun decoders(mime: String): List<CodecEntry> {
        val infos = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodecList query failed", e)
            emptyList()
        }
        return CodecRanking.sort(infos.mapNotNull { entry(it, mime) }) { it.codecClass }
    }

    fun allDecoders(): List<CodecEntry> = VIDEO_MIMES.flatMap { decoders(it) }

    fun entry(info: MediaCodecInfo, mime: String): CodecEntry? = try {
        val caps = info.getCapabilitiesForType(mime)
        val video = caps.videoCapabilities
        val q = Build.VERSION.SDK_INT >= 29
        val hw = if (q) info.isHardwareAccelerated else null
        val sw = if (q) info.isSoftwareOnly else null
        val vendor = if (q) info.isVendor else null
        val maxW = video.supportedWidths.upper
        val maxH = video.supportedHeights.upper
        val (tw, th) = _target1080p(video)
        val maxFps = runCatching {
            if (video.isSizeSupported(tw, th)) video.getSupportedFrameRatesFor(tw, th).upper else null
        }.getOrNull()
        val achievable = runCatching { video.getAchievableFrameRatesFor(tw, th)?.upper }.getOrNull()
        val points = if (q) runCatching { video.supportedPerformancePoints?.map { it.toString() } }.getOrNull().orEmpty()
            else emptyList()
        CodecEntry(
            name = info.name,
            mime = mime,
            codecClass = CodecRanking.classify(info.name, hw, sw, vendor),
            hardwareAccelerated = hw,
            softwareOnly = sw,
            vendor = vendor,
            alias = if (q) info.isAlias else null,
            maxWidth = maxW,
            maxHeight = maxH,
            maxFps1080p = maxFps,
            achievableFps1080p = achievable,
            performancePoints = points,
            lowLatencyFeature = Build.VERSION.SDK_INT >= 30 &&
                runCatching { caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency) }.getOrDefault(false),
            adaptivePlayback = runCatching { caps.isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback) }
                .getOrDefault(false),
        )
    } catch (e: Exception) {
        Log.w(TAG, "caps query failed for ${info.name}", e)
        null
    }

    /**
     * Really creates the decoder, configures it for 1080p (or its max size) in ByteBuffer mode,
     * starts and releases it. Proves the codec can be instantiated on this device.
     */
    fun probe(entry: CodecEntry): ProbeResult {
        val w = minOf(1920, entry.maxWidth)
        val h = minOf(1080, entry.maxHeight)
        val t0 = SystemClock.elapsedRealtime()
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createByCodecName(entry.name)
            val format = MediaFormat.createVideoFormat(entry.mime, w, h)
            codec.configure(format, null, null, 0)
            codec.start()
            codec.stop()
            ProbeResult(entry, w, h, true, SystemClock.elapsedRealtime() - t0, null)
        } catch (e: Exception) {
            ProbeResult(entry, w, h, false, SystemClock.elapsedRealtime() - t0, e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            runCatching { codec?.release() }
        }
    }

    private fun _target1080p(video: MediaCodecInfo.VideoCapabilities): Pair<Int, Int> {
        val w = minOf(1920, video.supportedWidths.upper)
        val h = minOf(1080, video.supportedHeights.upper)
        // align to the codec's size granularity so isSizeSupported doesn't reject odd sizes
        val wa = video.widthAlignment.coerceAtLeast(1)
        val ha = video.heightAlignment.coerceAtLeast(1)
        return (w / wa * wa) to (h / ha * ha)
    }
}
