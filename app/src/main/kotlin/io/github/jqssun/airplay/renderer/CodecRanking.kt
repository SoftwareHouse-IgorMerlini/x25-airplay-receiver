package io.github.jqssun.airplay.renderer

/**
 * Pure (Android-free) decoder classification so it can be unit tested on the JVM.
 *
 * Preference order requested for X25:
 *   1. hardware vendor codec
 *   2. hardware Android (non-vendor) codec
 *   3. software codec, only as fallback
 *
 * On Android 10+ (API 29) the flags come from MediaCodecInfo.isHardwareAccelerated /
 * isSoftwareOnly / isVendor and are authoritative. Below API 29 the platform does not
 * expose them, so the class is derived from the codec name and marked as unverified.
 */
enum class CodecClass(val rank: Int, val verified: Boolean) {
    HW_VENDOR(0, true),
    HW_ANDROID(1, true),
    // API < 29: looks like a hardware codec by name, but the platform cannot confirm it
    HW_UNVERIFIED(2, false),
    SOFTWARE(3, true),
    // API < 29: looks like a software codec by name
    SOFTWARE_UNVERIFIED(3, false);

    val isHardware: Boolean get() = this == HW_VENDOR || this == HW_ANDROID || this == HW_UNVERIFIED
}

object CodecRanking {

    private val softwarePrefixes = listOf("omx.google.", "c2.android.", "omx.ffmpeg.", "c2.ffmpeg.")

    /** true if the codec name follows a known software naming pattern */
    fun looksSoftware(name: String): Boolean {
        val n = name.lowercase()
        if (softwarePrefixes.any { n.startsWith(it) }) return true
        // vendor software fallbacks, e.g. OMX.qcom.video.decoder.hevcswvdec, OMX.SEC.hevc.sw.dec
        if (n.contains(".sw.") || n.endsWith("swvdec") || n.contains("swdec")) return true
        // anything outside the omx./c2. namespaces is a plain software component
        return !n.startsWith("omx.") && !n.startsWith("c2.")
    }

    /**
     * @param hardware  MediaCodecInfo.isHardwareAccelerated, null when API < 29
     * @param softwareOnly MediaCodecInfo.isSoftwareOnly, null when API < 29
     * @param vendor MediaCodecInfo.isVendor, null when API < 29
     */
    fun classify(name: String, hardware: Boolean?, softwareOnly: Boolean?, vendor: Boolean?): CodecClass {
        if (hardware != null && softwareOnly != null && vendor != null) {
            return when {
                softwareOnly || !hardware -> CodecClass.SOFTWARE
                vendor -> CodecClass.HW_VENDOR
                else -> CodecClass.HW_ANDROID
            }
        }
        return if (looksSoftware(name)) CodecClass.SOFTWARE_UNVERIFIED else CodecClass.HW_UNVERIFIED
    }

    /** stable sort: best class first, original MediaCodecList order kept inside a class */
    fun <T> sort(items: List<T>, classOf: (T) -> CodecClass): List<T> =
        items.withIndex().sortedWith(compareBy({ classOf(it.value).rank }, { it.index })).map { it.value }

    /** YES / NO / UNKNOWN text for diagnostics; never claims YES without platform confirmation */
    fun yesNo(v: Boolean?): String = when (v) {
        true -> "YES"
        false -> "NO"
        null -> "UNKNOWN (API<29)"
    }
}
