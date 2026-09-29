package io.github.jqssun.airplay.renderer

/**
 * X25 latency profiles. All three keep the zero-copy path (MediaCodec -> Surface, never
 * through CPU memory); they only differ in how frames are presented and how much the
 * audio buffer is allowed to absorb.
 *
 * - LOW: decoder renders straight into the SurfaceView surface and every frame is released
 *   as soon as it is decoded. Smallest audio cushion.
 * - BALANCED: direct SurfaceView rendering, frames released at the VSYNC matching their
 *   presentation time (smoother pacing, at most about one frame of extra delay).
 * - STABLE: upstream path - decoder renders into an app-owned SurfaceTexture that a GL thread
 *   blits (GPU, no CPU copy) to the SurfaceView; survives surface re-creation without a codec
 *   restart. Largest audio cushion.
 */
enum class LatencyMode(
    val key: String,
    val directRender: Boolean,
    val scheduledRelease: Boolean,
    // index into Prefs.ADAPTIVE_PERCENTILES (0 = lowest latency .. 4 = best stability)
    val audioAdaptiveStep: Int,
    val audioLowLatency: Boolean,
) {
    LOW("low", directRender = true, scheduledRelease = false, audioAdaptiveStep = 0, audioLowLatency = true),
    // audio values equal the upstream defaults, so a fresh install is already consistent with BALANCED
    BALANCED("balanced", directRender = true, scheduledRelease = true, audioAdaptiveStep = 3, audioLowLatency = false),
    STABLE("stable", directRender = false, scheduledRelease = true, audioAdaptiveStep = 4, audioLowLatency = false);

    companion object {
        fun fromKey(key: String?): LatencyMode = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}
