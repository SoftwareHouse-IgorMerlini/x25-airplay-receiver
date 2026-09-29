package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecRankingTest {

    @Test
    fun platformFlags_areAuthoritative() {
        assertEquals(CodecClass.HW_VENDOR, CodecRanking.classify("c2.mtk.avc.decoder", true, false, true))
        assertEquals(CodecClass.HW_ANDROID, CodecRanking.classify("c2.example.avc.decoder", true, false, false))
        assertEquals(CodecClass.SOFTWARE, CodecRanking.classify("c2.android.avc.decoder", false, true, false))
        // a vendor codec flagged software-only is still software
        assertEquals(CodecClass.SOFTWARE, CodecRanking.classify("OMX.qcom.video.decoder.hevcswvdec", false, true, true))
        // name says hardware but platform says software: platform wins
        assertEquals(CodecClass.SOFTWARE, CodecRanking.classify("OMX.amlogic.avc.decoder.awesome", false, false, true))
    }

    @Test
    fun belowApi29_nameHeuristic_isMarkedUnverified() {
        val hw = CodecRanking.classify("OMX.amlogic.avc.decoder.awesome", null, null, null)
        assertEquals(CodecClass.HW_UNVERIFIED, hw)
        assertTrue(hw.isHardware)
        assertFalse(hw.verified)
        assertEquals(CodecClass.SOFTWARE_UNVERIFIED, CodecRanking.classify("OMX.google.h264.decoder", null, null, null))
        assertEquals(CodecClass.SOFTWARE_UNVERIFIED, CodecRanking.classify("c2.android.hevc.decoder", null, null, null))
        assertEquals(CodecClass.SOFTWARE_UNVERIFIED, CodecRanking.classify("OMX.SEC.hevc.sw.dec", null, null, null))
        assertEquals(CodecClass.SOFTWARE_UNVERIFIED, CodecRanking.classify("FFmpegDecoder", null, null, null))
    }

    @Test
    fun sort_prefersVendorHardware_thenAndroidHardware_thenSoftware_stable() {
        data class C(val name: String, val cls: CodecClass)
        val input = listOf(
            C("c2.android.avc.decoder", CodecClass.SOFTWARE),
            C("c2.generic.avc.decoder", CodecClass.HW_ANDROID),
            C("c2.vendor.a.avc.decoder", CodecClass.HW_VENDOR),
            C("c2.vendor.b.avc.decoder", CodecClass.HW_VENDOR),
            C("OMX.google.h264.decoder", CodecClass.SOFTWARE),
        )
        val sorted = CodecRanking.sort(input) { it.cls }.map { it.name }
        assertEquals(
            listOf(
                "c2.vendor.a.avc.decoder", "c2.vendor.b.avc.decoder",
                "c2.generic.avc.decoder",
                "c2.android.avc.decoder", "OMX.google.h264.decoder",
            ),
            sorted
        )
    }

    @Test
    fun yesNo_neverClaimsYesWithoutPlatformValue() {
        assertEquals("YES", CodecRanking.yesNo(true))
        assertEquals("NO", CodecRanking.yesNo(false))
        assertTrue(CodecRanking.yesNo(null).startsWith("UNKNOWN"))
    }

    @Test
    fun latencyModes() {
        assertEquals(LatencyMode.BALANCED, LatencyMode.fromKey(null))
        assertEquals(LatencyMode.BALANCED, LatencyMode.fromKey("garbage"))
        assertTrue(LatencyMode.LOW.directRender)
        assertFalse(LatencyMode.LOW.scheduledRelease)
        assertFalse(LatencyMode.STABLE.directRender)
        assertTrue(LatencyMode.LOW.audioAdaptiveStep < LatencyMode.STABLE.audioAdaptiveStep)
        LatencyMode.entries.forEach { assertTrue(it.audioAdaptiveStep in 0..4) }
    }
}
