package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps

class VideoRenderer(ctx: Context) {

    private val lock = Object()
    private val pipeline = VideoPipeline()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    // surface the running codec currently renders into (display in direct mode, else pipeline)
    private var outputSurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set
    // X25: rendered frame rate from output frame intervals (xx.x)
    @Volatile var renderFps = 0f; private set
    // X25: frames sitting inside the decoder, expressed in ms
    @Volatile var decoderBufferMs = 0; private set
    // X25: time from frame arrival (JNI) to its release to the display surface, moving average
    @Volatile var latencyMs = 0f; private set
    // X25: verified description of the decoder that actually started (null until a codec runs)
    @Volatile var activeCodec: CodecEntry? = null; private set
    // X25: true while the codec renders straight into the SurfaceView surface
    @Volatile var directRenderActive = false; private set
    // X25: where decoded frames go right now: DIRECT, GPU, PARKED (no visible screen), FALLBACK (decoder refused direct)
    @Volatile var renderPath = "—"; private set
    // X25: user-visible events (Logs tab)
    var eventLog: ((String) -> Unit)? = null
    private var _directRefused = false

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    // X25: Prefs.AUTO / Prefs.HARDWARE / Prefs.SOFTWARE
    var decoderMode: String = Prefs.DEF_DECODER_MODE
    var forceHevc = false

    // X25: MediaCodec -> SurfaceView without the GL blit; applied from the next codec start
    var directRender: Boolean = false
        set(v) = synchronized(lock) {
            field = v
            // a surface can only have one producer: never let GL hold the display in direct mode
            pipeline.setDisplaySurface(if (v) null else displaySurface)
        }

    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L
    private var _queued = 0L
    private var _released = 0L
    // pts(us) -> arrival nanoTime, bounded
    private val _arrivals = object : LinkedHashMap<Long, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?) = size > 64
    }

    fun setResolution(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
    }

    // doesn't restart codec: pipeline mode re-points GL, direct mode swaps codec output surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        if (!directRender) {
            pipeline.setDisplaySurface(surface)
            if (codec != null) renderPath = "GPU"
            return@synchronized
        }
        val c = codec ?: return@synchronized
        if (outputSurface !== surface) _switchOutput(c, surface, toDisplay = true)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        if (!directRender || !directRenderActive) {
            pipeline.setDisplaySurface(null)
        }
        if (codec != null) renderPath = "PARKED"
        val c = codec ?: return@synchronized
        if (outputSurface === surface) {
            // park decoder output on the pipeline's texture so it keeps its reference frames
            val parking = pipeline.inputSurface
            if (parking == null) stopCodec() else _switchOutput(c, parking, toDisplay = false)
        }
    }

    private fun _switchOutput(c: MediaCodec, target: Surface, toDisplay: Boolean) {
        try {
            c.setOutputSurface(target)
            outputSurface = target
            directRenderActive = toDisplay
            renderPath = if (toDisplay) "DIRECT" else "PARKED"
            Log.i(TAG, "codec output -> ${if (toDisplay) "SurfaceView (direct)" else "pipeline (parked)"}")
        } catch (e: Exception) {
            Log.w(TAG, "setOutputSurface failed (toDisplay=$toDisplay)", e)
            if (toDisplay) {
                // decoder refuses surface swaps: keep it on the pipeline and let GL present instead
                directRenderActive = false
                _directRefused = true
                renderPath = "FALLBACK"
                eventLog?.invoke("Decoder refused direct SurfaceView output (setOutputSurface: ${e.message}); using GPU path")
                pipeline.setDisplaySurface(target)
            } else {
                stopCodec()
            }
        }
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean): Boolean = synchronized(lock) {
        when (decoderMode) {
            Prefs.SOFTWARE -> {
                // software only: HEVC is not advertised, software HEVC at 1080p is too heavy for projector SoCs
                avcDecoder = selector.software(DecoderSelector.AVC, w, h) ?: selector.avc().also {
                    Log.w(TAG, "no software AVC decoder found, using ${it?.name}")
                }
                hevcDecoder = null
            }
            else -> {
                avcDecoder = selector.avc()
                hevcDecoder = if (!h265) null
                    else selector.hevc(avcDecoder, w, h, fps) ?: if (forceHevc) selector.hevcHardware() else null
            }
        }
        maxFps = fps
        Log.i(TAG, "decoders ($decoderMode): avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) } ?: (1920 to 1080)

    /** highest frame rate the selected AVC decoder declares for w x h (null = unknown) */
    fun maxFpsFor(w: Int, h: Int): Int? = runCatching {
        val caps = avcDecoder?.videoCaps(DecoderSelector.AVC) ?: return null
        val (cw, ch) = if (caps.isSizeSupported(w, h)) w to h else h to w
        caps.getSupportedFrameRatesFor(cw, ch).upper.toInt()
    }.getOrNull()

    val selectedAvc: MediaCodecInfo? get() = avcDecoder
    val selectedHevc: MediaCodecInfo? get() = hevcDecoder

    // codec per mirror session; pipeline persists across sessions
    fun startSession() = synchronized(lock) { _resetStats() }

    fun stopSession() = synchronized(lock) { stopCodec(); activeCodec = null }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        renderFps = 0f; decoderBufferMs = 0; latencyMs = 0f
        _framesThisSec = 0; _bytesThisSec = 0
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            val meanNs = _meanFrameIntervalNs()
            renderFps = if (meanNs > 0) (1e9 / meanNs).toFloat() else 0f
            val pending = (_queued - _released).coerceAtLeast(0)
            decoderBufferMs = if (meanNs > 0) (pending * meanNs / 1_000_000L).toInt() else 0
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps render=${"%.1f".format(renderFps)} bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames buffer=${decoderBufferMs}ms latency=${latencyMs.toInt()}ms " +
            "codec=$codecName direct=$directRenderActive res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        val arrivalNs = System.nanoTime()
        synchronized(lock) {
            _updateStats(data.size)
            if (videoWidth == 0 || videoHeight == 0) return

            if (codec == null || isH265 != currentH265) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!_isKeyframe(data, isH265)) {
                    if (codec != null) stopCodec()
                    return
                }
                stopCodec()
            }

            try {
                if (codec == null) startCodec(isH265)
                _feedToCodec(data, ntpTimeNs, arrivalNs)
                drainOutput()
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long, arrivalNs: Long) {
        val c = codec ?: return
        // dropping a frame desyncs decoder until the next keyframe, but source would only send one on (re)connect
        val retries = if (firstFrameQueued) FEED_RETRIES else FIRST_FEED_RETRIES
        repeat(retries) {
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                val ptsUs = ntpTimeNs / 1000
                c.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
                _arrivals[ptsUs] = arrivalNs
                _queued++
                firstFrameQueued = true
                return
            }
            drainOutput()
        }
        droppedFrames++
        Log.w(TAG, "Decoder input queue full; dropping frame. drops=$droppedFrames")
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val key = if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 21 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
                if (key) return true
            }
            i++
        }
        return false
    }

    private fun startCodec(h265: Boolean) {
        // pipeline always runs: it is the output in STABLE mode and the parking target in direct mode
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val parking = pipeline.inputSurface ?: return
        val display = displaySurface?.takeIf { directRender && it.isValid }
        val target = display ?: parking
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
        try {
            _startWithLadder(info, mime, target, h265)
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            if (decoderMode != Prefs.AUTO) throw e // HARDWARE: no silent software fallback
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, target, h265)
        }
        outputSurface = target
        directRenderActive = target === display
        renderPath = when {
            directRenderActive -> "DIRECT"
            !directRender -> "GPU"
            else -> "PARKED"
        }
        eventLog?.invoke("Video decoder ${activeCodec?.name} (${activeCodec?.codecClass}) render=$renderPath")
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName) " +
            "class=${activeCodec?.codecClass} direct=$directRenderActive")
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        var tryNum = 0
        while (true) {
            val format = _format(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                _startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265, mime)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
        if (selector.adaptive(info, mime)) {
            setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
        if (enforceSdr) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean, mime: String) {
        try {
            c.configure(format, surface, null, 0)
            // keep aspect handling in the view layer (FIT/FILL/STRETCH); decoder just scales to the surface
            c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
        // read the flags of the instance that actually started, not of the one we asked for
        activeCodec = runCatching { CodecInspector.entry(c.codecInfo, mime) }.getOrNull()
    }

    private fun stopCodec() {
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        _queued = 0L
        _released = 0L
        _arrivals.clear()
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
        outputSurface = null
        directRenderActive = false
        renderPath = "—"
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx < 0) break
            _recordOutputFrameTime()
            val ptsUs = info.presentationTimeUs
            val now = System.nanoTime()
            var renderAtNs = now
            if (scheduledOutputBufferRelease) {
                // schedule frame at VSYNC matching its NTP presentation time
                if (_ptsBaseUs == Long.MIN_VALUE) {
                    _ptsBaseUs = ptsUs
                    _wallBaseNs = now
                }
                renderAtNs = _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L
                c.releaseOutputBuffer(idx, renderAtNs)
            } else {
                c.releaseOutputBuffer(idx, true)
            }
            _released++
            _arrivals.remove(ptsUs)?.let { arrival ->
                val ms = (maxOf(renderAtNs, now) - arrival) / 1_000_000f
                latencyMs = if (latencyMs == 0f) ms else latencyMs * 0.9f + ms * 0.1f
            }
        }
    }

    fun release() = synchronized(lock) {
        stopCodec()
        activeCodec = null
        pipeline.release()
        _resetStats()
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _meanFrameIntervalNs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0
        var sum = 0L
        for (i in 0 until count) sum += _frameIntervalsNs[i]
        return sum / count
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        private const val FEED_WAIT_US = 20_000L
        private const val FEED_RETRIES = 10
        private const val FIRST_FEED_RETRIES = 50
    }
}
