package com.opentune.ui.player

import android.media.AudioManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentune.data.NerdStats
import com.opentune.data.settings.AppSettings
import com.opentune.playback.AudioFormatInfo
import com.opentune.playback.BitPerfectUsb
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton

/**
 * Every stage the sound passes through, from the stream to the speaker, drawn
 * as one timeline with a light running down it. Each node's colour says what
 * it's doing: green untouched, amber changing the sound on purpose, grey
 * information.
 */
@Composable
fun SignalPathDialog(format: AudioFormatInfo?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val am = remember { context.getSystemService(AudioManager::class.java) }
    val eq by AppSettings.equalizer.collectAsState()
    val sound by AppSettings.sound.collectAsState()
    val pb by AppSettings.playback.collectAsState()
    val source by NerdStats.playbackSource.collectAsState()
    val lossless by NerdStats.externalSource.collectAsState()
    val gain by NerdStats.loudnessGainDb.collectAsState()
    val speakerHold by NerdStats.loudnessOffOnSpeaker.collectAsState()
    val device = rememberOutputDeviceName()
    val route = rememberOutputRoute()
    val bitPerfect by BitPerfectUsb.status.collectAsState()
    val mixerRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    val volume = am?.getStreamVolume(AudioManager.STREAM_MUSIC)
    val maxVolume = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    val dspStages = buildList {
        if (eq.enabled) add("equalizer")
        if (sound.bassBoost > 0) add("bass boost")
        if (pb.spatialAudio) add("stereo widening")
        if (sound.space != com.opentune.data.settings.Space3DSpeed.OFF) add("3D sound")
        else if (sound.eightD != com.opentune.data.settings.EightD.OFF) add("8D")
        if (pb.clarity) add("clarity")
        if (eq.balance != 0f) add("balance")
    }
    val rate = format?.sampleRateHz ?: source?.sampleRate

    val stages = buildList {
        add(Stage(Icons.Rounded.MusicNote, Tone.INFO, "Source", source?.provider ?: "Waiting for the stream"))
        add(Stage(Icons.Rounded.HighQuality, Tone.INFO, "Audio quality", signalQuality(format, source)))
        source?.selectionDetail?.let { add(Stage(Icons.Rounded.Tune, Tone.INFO, "Source selection", it)) }
        add(
            Stage(
                Icons.Rounded.GraphicEq,
                if (dspStages.isEmpty()) Tone.CLEAN else Tone.CHANGED,
                "App DSP",
                if (dspStages.isEmpty()) "Bypassed: samples pass through unchanged" else dspStages.joinToString(", ") + ", with headroom so boosts don't clip",
            ),
        )
        add(
            Stage(
                Icons.Rounded.Speed,
                if (sound.speed == 1f && sound.pitch == 1f) Tone.CLEAN else Tone.CHANGED,
                "Tempo",
                if (sound.speed == 1f && sound.pitch == 1f) "1.00x, no time-stretching" else "%.2fx speed, %.2fx pitch".format(sound.speed, sound.pitch),
            ),
        )
        add(
            Stage(
                Icons.AutoMirrored.Rounded.VolumeUp,
                if (gain == null) Tone.CLEAN else Tone.CHANGED,
                "Loudness",
                when {
                    !pb.loudnessNormalization -> "Off: every song at its own level"
                    lossless != null -> "No loudness measurement for this external master"
                    speakerHold && (gain ?: 0f) > 0.05f -> "%+.1f dB on the phone speaker: a quiet song lifted, never turned down".format(gain)
                    speakerHold -> "Full level on the phone speaker: songs aren't turned down there"
                    gain == null -> "No figure for this song yet"
                    else -> "%+.1f dB to YouTube's reference level".format(gain)
                },
            ),
        )
        add(
            Stage(
                Icons.Rounded.Equalizer,
                if (pb.systemEffects) Tone.CHANGED else Tone.CLEAN,
                "System effects",
                if (pb.systemEffects) "Open: the phone's effects may process the sound" else "Closed: no device effects",
            ),
        )
        add(
            Stage(
                Icons.Rounded.Layers,
                if (rate != null && mixerRate != null && rate != mixerRate) Tone.CHANGED else Tone.CLEAN,
                "Android mixer",
                when {
                    mixerRate == null -> "Rate unknown"
                    rate != null && rate != mixerRate -> "$mixerRate Hz: Android resamples from $rate Hz"
                    else -> "$mixerRate Hz, no resampling"
                } + if (pb.floatOutput) " · 32-bit float" else " · 16-bit",
            ),
        )
        add(
            Stage(
                Icons.Rounded.Grain,
                Tone.CLEAN,
                "Dither",
                if (pb.floatOutput) "Bypassed: 32-bit float keeps full precision to the mixer"
                else "TPDF dither, shaped above hearing, on the 16-bit step so quiet tails stay clean",
            ),
        )
        when (val bp = bitPerfect) {
            is BitPerfectUsb.Status.Active -> add(Stage(Icons.Rounded.Verified, Tone.CLEAN, "Bit-perfect", "On: ${bp.device} gets ${bp.sampleRate} Hz ${bp.bits}-bit untouched by Android"))
            is BitPerfectUsb.Status.NoMatch -> add(Stage(Icons.Rounded.Verified, Tone.CHANGED, "Bit-perfect", "The DAC doesn't offer ${bp.sampleRate} Hz bit-perfect; using the normal mixer"))
            BitPerfectUsb.Status.NoDac -> add(Stage(Icons.Rounded.Verified, Tone.INFO, "Bit-perfect", "Waiting for a USB DAC"))
            BitPerfectUsb.Status.Unsupported -> add(Stage(Icons.Rounded.Verified, Tone.INFO, "Bit-perfect", "Needs Android 14 or newer"))
            BitPerfectUsb.Status.Off -> Unit
        }
        add(
            Stage(
                Icons.Rounded.Tune,
                if (bitPerfect is BitPerfectUsb.Status.Active && volume != maxVolume) Tone.CHANGED else Tone.INFO,
                "System volume",
                (if (volume != null && maxVolume != null) "$volume of $maxVolume" else "Unknown") +
                    if (bitPerfect is BitPerfectUsb.Status.Active) " · applied by the app; full volume is bit-exact" else "",
            ),
        )
        val outIcon = when {
            route == OutputRoute.CAST -> Icons.Rounded.Cast
            route.isHeadset -> Icons.Rounded.Headphones
            else -> Icons.Rounded.Speaker
        }
        add(Stage(outIcon, Tone.INFO, "Output", device))
    }

    FloatingDialog(
        onDismissRequest = onDismiss,
        icon = { androidx.compose.material3.Icon(Icons.Rounded.GraphicEq, null) },
        title = { Text("Audio pipeline") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Live signal path, decoder to output",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                Timeline(stages)
            }
        },
        confirmButton = { SheetButton("Close", onClick = onDismiss, closes = true) },
    )
}

/** One node on the pipeline: an icon, a tone colour, and what it's doing. */
private data class Stage(val icon: ImageVector, val tone: Tone, val title: String, val detail: String)

private enum class Tone(val color: Color) {
    CLEAN(Color(0xFF4CAF50)),
    CHANGED(Color(0xFFFFB300)),
    INFO(Color(0xFF9E9E9E)),
}

/** The nodes down a single rail, with a light beam sweeping top to bottom. */
@Composable
private fun Timeline(stages: List<Stage>) {
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val beam = if (still) null else rememberInfiniteTransition(label = "pipeline")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "beam")
    val beamColor = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().drawWithContent {
            drawContent()
            val p = beam?.value ?: return@drawWithContent
            val cx = RAIL_WIDTH.toPx() / 2f
            val half = 9.dp.toPx()
            val len = 64.dp.toPx()
            val y = p * size.height
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to beamColor,
                    1f to Color.Transparent,
                    startY = y - len / 2f,
                    endY = y + len / 2f,
                ),
                topLeft = Offset(cx - half, y - len / 2f),
                size = Size(half * 2f, len),
                blendMode = BlendMode.Plus,
            )
        },
    ) {
        Column {
            stages.forEachIndexed { i, stage ->
                StageRow(stage, first = i == 0, last = i == stages.lastIndex)
            }
        }
    }
}

private val RAIL_WIDTH = 44.dp
private val NODE_SIZE = 34.dp

/** The node's centre down from the top of its row, where the connector line meets it. */
private val NODE_CENTER = 23.dp

@Composable
private fun StageRow(stage: Stage, first: Boolean, last: Boolean) {
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(RAIL_WIDTH).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = NODE_CENTER.toPx()
                val w = 2.dp.toPx()
                if (!first) drawLine(lineColor, Offset(cx, 0f), Offset(cx, cy), w)
                if (!last) drawLine(lineColor, Offset(cx, cy), Offset(cx, size.height), w)
            }
            Box(
                Modifier
                    .padding(top = NODE_CENTER - NODE_SIZE / 2)
                    .align(Alignment.TopCenter)
                    .size(NODE_SIZE)
                    .clip(CircleShape)
                    .background(stage.tone.color.copy(alpha = 0.16f))
                    .border(1.5.dp, stage.tone.color.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(stage.icon, null, tint = stage.tone.color, modifier = Modifier.size(17.dp))
            }
        }
        Column(Modifier.padding(start = 6.dp, top = 12.dp, bottom = if (last) 4.dp else 18.dp)) {
            Text(stage.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stage.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Reports the selected media format; output mixer precision is shown separately. */
internal fun signalQuality(format: AudioFormatInfo?, source: NerdStats.Source?): String {
    val codec = format?.codec
    val bits = format?.bitDepth ?: source?.bits
    val rate = format?.sampleRateHz ?: source?.sampleRate
    val kbps = format?.bitrateKbps ?: source?.kbps
    return listOfNotNull(
        codec,
        bits?.let { "$it-bit" },
        rate?.let { "$it Hz" },
        format?.channels?.let { if (it == 2) "stereo" else "$it ch" },
        kbps?.let { "$it kbps" },
        if (codec?.contains("FLAC", true) == true) "lossless" else null,
        if (codec != null && kbps == null) "bitrate not reported" else null,
    ).joinToString(" · ").ifEmpty { "Waiting for the actual stream format" }
}
