package io.github.jqssun.airplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.renderer.CodecEntry
import io.github.jqssun.airplay.renderer.CodecRanking
import io.github.jqssun.airplay.viewmodel.MainViewModel

/** X25 hardware decoder diagnostics: what the device offers and what is actually running. */
@Composable
fun DiagnosticsScreen(viewModel: MainViewModel) {
    val codecs by viewModel.codecList.collectAsState()
    val probes by viewModel.probeResults.collectAsState()
    val probing by viewModel.probing.collectAsState()
    val info by viewModel.debugInfo.collectAsState()
    val view = LocalView.current
    LaunchedEffect(Unit) { viewModel.refreshCodecList() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DiagCard("Rendering") {
            DiagRow("Hardware accelerated (window)", CodecRanking.yesNo(view.isHardwareAccelerated))
            DiagRow("Video surface", "SurfaceView (MediaCodec output Surface, no Bitmap / no CPU YUV->RGB)")
            DiagRow("Direct MediaCodec -> SurfaceView", if (info.activeCodec == null) "—" else CodecRanking.yesNo(info.directRender))
        }

        DiagCard("Active decoder") {
            val a = info.activeCodec
            if (a == null) {
                Text(
                    "No decoder running. Start mirroring from the iPhone; the values below are read from the " +
                        "MediaCodec instance that actually started.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                DiagRow("Codec", "${a.codecLabel} — ${a.name}")
                DiagRow("Decoder", if (a.codecClass.isHardware) "HARDWARE" else "SOFTWARE")
                DiagRow("Hardware codec", CodecRanking.yesNo(a.hardwareAccelerated))
                DiagRow("Software only", CodecRanking.yesNo(a.softwareOnly))
                DiagRow("Vendor", CodecRanking.yesNo(a.vendor))
                DiagRow("Class", a.codecClass.name + if (a.codecClass.verified) "" else " (name heuristic, not verified by platform)")
                DiagRow("Stream", "${info.videoRes}  ${"%.1f".format(info.renderFps)} fps  ${info.kbpsStr}")
            }
        }

        DiagCard("Capabilities summary") {
            for (label in listOf("H.264", "HEVC")) {
                val list = codecs.filter { it.codecLabel == label }
                val best = list.firstOrNull()
                val hw = list.filter { it.codecClass.isHardware }
                DiagRow("$label decoders", "${list.size} total, ${hw.size} hardware, ${list.size - hw.size} software")
                if (best != null) {
                    DiagRow("$label preferred", best.name)
                    DiagRow("$label max resolution", "${best.maxWidth}x${best.maxHeight}")
                    DiagRow("$label max FPS @1080p", best.maxFps1080p?.let { "%.0f".format(it) } ?: "not supported")
                } else {
                    DiagRow("$label preferred", "none (not available)")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.runDecoderProbe() }, enabled = !probing, modifier = Modifier.dpadFocus()) {
                Text(if (probing) "Testing..." else "Run decoder test")
            }
            OutlinedButton(onClick = { viewModel.refreshCodecList() }, modifier = Modifier.dpadFocus()) {
                Text("Refresh")
            }
        }
        if (probes.isNotEmpty()) {
            DiagCard("Decoder test (create + configure + start + release)") {
                probes.forEach { r ->
                    DiagRow(
                        "${r.entry.codecLabel} ${r.entry.name}",
                        if (r.ok) "OK ${r.width}x${r.height} (${r.millis} ms)" else "FAIL: ${r.error}",
                        valueColor = if (r.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Text("All H.264 / HEVC decoders (preference order)", style = MaterialTheme.typography.titleSmall)
        codecs.forEach { CodecCard(it) }
    }
}

@Composable
private fun CodecCard(c: CodecEntry) {
    DiagCard("${c.codecLabel} — ${c.name}") {
        DiagRow("Class", c.codecClass.name)
        DiagRow("Hardware accelerated", CodecRanking.yesNo(c.hardwareAccelerated))
        DiagRow("Software only", CodecRanking.yesNo(c.softwareOnly))
        DiagRow("Vendor", CodecRanking.yesNo(c.vendor))
        c.alias?.let { DiagRow("Alias", CodecRanking.yesNo(it)) }
        DiagRow("Max resolution", "${c.maxWidth}x${c.maxHeight}")
        DiagRow("Max FPS @1080p", c.maxFps1080p?.let { "%.0f".format(it) } ?: "not supported")
        c.achievableFps1080p?.let { DiagRow("Measured FPS @1080p", "%.0f".format(it)) }
        DiagRow("Low-latency feature", CodecRanking.yesNo(c.lowLatencyFeature))
        DiagRow("Adaptive playback", CodecRanking.yesNo(c.adaptivePlayback))
        DiagRow("Performance points", if (c.performancePoints.isEmpty()) "not published" else c.performancePoints.joinToString("\n"))
    }
}

@Composable
private fun DiagCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.45f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = valueColor,
            modifier = Modifier.weight(0.55f)
        )
    }
}
