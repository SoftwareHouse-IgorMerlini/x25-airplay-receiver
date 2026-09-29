package io.github.jqssun.airplay.renderer

import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device decoder report. Run with a projector/TV connected over ADB:
 *   ./gradlew connectedDebugAndroidTest
 *   adb logcat -s X25DecoderTest
 */
@RunWith(AndroidJUnit4::class)
class DecoderCapabilityTest {

    private fun report(mime: String): List<CodecEntry> {
        val list = CodecInspector.decoders(mime)
        list.forEach { c ->
            Log.i(TAG, "${c.codecLabel} ${c.name} class=${c.codecClass} hw=${c.hardwareAccelerated} " +
                "swOnly=${c.softwareOnly} vendor=${c.vendor} max=${c.maxWidth}x${c.maxHeight} " +
                "fps@1080p=${c.maxFps1080p} achievable=${c.achievableFps1080p} lowLatency=${c.lowLatencyFeature} " +
                "perfPoints=${c.performancePoints}")
        }
        return list
    }

    @Test
    fun h264_decoderAvailable_andPreferredIsBestClass() {
        val list = report(MediaFormat.MIMETYPE_VIDEO_AVC)
        assertTrue("device has no H.264 decoder", list.isNotEmpty())
        // ranking invariant: nothing after the first entry has a better class
        assertTrue(list.all { it.codecClass.rank >= list.first().codecClass.rank })
        if (Build.VERSION.SDK_INT >= 29 && list.any { it.codecClass.isHardware }) {
            assertTrue("hardware decoder exists but is not preferred", list.first().codecClass.isHardware)
        }
    }

    @Test
    fun hevc_report() {
        // HEVC is optional; this only logs what exists
        report(MediaFormat.MIMETYPE_VIDEO_HEVC)
    }

    @Test
    fun preferredH264_canBeInstantiatedAt1080p() {
        val best = CodecInspector.decoders(MediaFormat.MIMETYPE_VIDEO_AVC).first()
        val r = CodecInspector.probe(best)
        Log.i(TAG, "probe ${best.name} ${r.width}x${r.height}: ok=${r.ok} ${r.millis}ms ${r.error ?: ""}")
        assertTrue("could not start ${best.name}: ${r.error}", r.ok)
    }

    @Test
    fun softwareFallback_exists() {
        val sw = CodecInspector.decoders(MediaFormat.MIMETYPE_VIDEO_AVC).filter { !it.codecClass.isHardware }
        Log.i(TAG, "software H.264 fallbacks: ${sw.map { it.name }}")
        assertTrue("no software H.264 fallback on this device", sw.isNotEmpty())
    }

    private companion object { const val TAG = "X25DecoderTest" }
}
