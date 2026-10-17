package com.opentune.ui.player

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.LyricWord
import com.opentune.data.lyrics.Romanizer
import com.opentune.data.lyrics.VOICE_SECOND
import com.opentune.data.lyrics.VOICE_TOGETHER
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.activeIndex
import com.opentune.ui.LyricsState
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.Placeholder
import com.opentune.ui.theme.LyricsTextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import com.opentune.data.settings.LyricsAnimation
import androidx.compose.runtime.collectAsState
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.LyricsAlign
import kotlin.math.abs
import kotlinx.coroutines.delay

@Composable
fun LyricsView(
    state: LyricsState,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    synced: Boolean = true,
    blur: Boolean = true,
    /** Long-press on a line: it's being sung now, so the song's lyrics move to match. */
    onSyncLine: ((Long) -> Unit)? = null,
    /** The song, for sharing its lines as a lyric card; no share button without it. */
    shareSong: com.opentune.data.model.Song? = null,
) {
    // The song and its lines as they were when sharing began, so a song
    // change while the sheet is open can't mix one song's words with another's.
    var sharing by remember { mutableStateOf<Pair<com.opentune.data.model.Song, List<String>>?>(null) }
    Box(modifier) {
        when (state) {
            is LyricsState.Loading -> Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SettingUpBadge()
                listOf(0.8f, 0.6f, 0.9f, 0.5f, 0.7f).forEach {
                    Placeholder(Modifier.fillMaxWidth(it).height(28.dp), MaterialTheme.shapes.small)
                }
            }
            is LyricsState.NotFound -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                MessageState(Icons.Rounded.Lyrics, "No lyrics for this one", message = "None of your lyrics sources has this track yet. You can change them in Settings › Lyrics sources.")
            }
            is LyricsState.Found -> when (val lyrics = state.lyrics) {
                is Lyrics.Synced ->
                    if (synced) SyncedLyrics(lyrics.lines, position, onSeek, blur, lyrics.source, onSyncLine)
                    else PlainLyrics(lyrics.lines.joinToString("\n") { it.text }, lyrics.source, note = null)
                is Lyrics.Plain -> PlainLyrics(lyrics.text, lyrics.source)
            }
        }
        val found = (state as? LyricsState.Found)?.lyrics
        if (shareSong != null && found != null) {
            // Lines with words in them (synced lyrics carry empty ones for the gaps), with
            // their start times so the sheet can open on the line being sung.
            val timed = remember(found) {
                when (found) {
                    is Lyrics.Synced -> found.lines.filter { it.text.isNotBlank() }.map { it.text.trim() to it.startMs }
                    is Lyrics.Plain -> found.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { it to -1L }
                }
            }
            val lines = remember(timed) { timed.map { it.first } }
            var startAt by remember { mutableStateOf(-1) }
            val haptics = com.opentune.ui.components.rememberHaptics()
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                    .clickable {
                        haptics.tick()
                        val now = position()
                        startAt = if (found is Lyrics.Synced) timed.indexOfLast { it.second <= now } else -1
                        sharing = shareSong to lines
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.FormatQuote, "Share lyrics", Modifier.size(20.dp))
            }
            sharing?.let { (song, shared) -> com.opentune.ui.share.LyricCardSheet(song, shared, startAt = startAt, onDismiss = { sharing = null }) }
        }
    }
}

@Composable
private fun PlainLyrics(text: String, source: String, note: String? = "These lyrics aren't synced to the music.") {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        val style = lyricsStyle()
        val romanize = AppSettings.lyrics.collectAsState().value.romanize
        val romanized by androidx.compose.runtime.produceState<List<String?>>(emptyList(), text, romanize) {
            value = if (!romanize) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Romanizer.lines(text.lines()) }
        }
        val dimmed = MaterialTheme.colorScheme.onSurfaceVariant
        val shown = remember(text, romanized, dimmed) {
            if (romanized.none { it != null }) AnnotatedString(text) else buildAnnotatedString {
                text.lines().forEachIndexed { i, line ->
                    if (i > 0) append("\n")
                    append(line)
                    romanized.getOrNull(i)?.let { r ->
                        append("\n")
                        withStyle(SpanStyle(color = dimmed, fontSize = style.fontSize * 0.5f)) { append(r) }
                    }
                }
            }
        }
        Text(shown, Modifier.fillMaxWidth(), style = style.copy(fontSize = style.fontSize * 0.8f, lineHeight = style.lineHeight * 0.85f))
        Credit(source)
    }
}

/**
 * Line-by-line lyrics that follow the music. The current line is bright and
 * full size and its words light up as they're sung; the rest are dimmed,
 * slightly smaller and (on Android 12+) softly blurred by distance. The list
 * glides to keep the current line about a third of the way down. Scrolling by
 * hand pauses that for a few seconds; tapping a line jumps the song there.
 */
@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    blur: Boolean,
    source: String,
    onSyncLine: ((Long) -> Unit)? = null,
) {
    val ui by AppSettings.ui.collectAsState()
    val reduced = ui.reduceAnimation || !android.animation.ValueAnimator.areAnimatorsEnabled()
    val animation = if (reduced) LyricsAnimation.MINIMAL else ui.lyricsAnimation
    val listState = rememberLazyListState()
    val romanize = AppSettings.lyrics.collectAsState().value.romanize
    val romanized by androidx.compose.runtime.produceState<List<String?>>(emptyList(), lines, romanize) {
        value = if (!romanize) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Romanizer.lines(lines.map { it.text }) }
    }
    // Two singers trade lines: the second voice's sit on the other side.
    val duet = remember(lines) { lines.any { it.voice == VOICE_SECOND } }
    // A smooth clock that never steps back while playing, so a word that has just
    // been sung can't count as unsung again and play its motion twice.
    val clock = rememberLyricsClock(position)
    val pos by rememberUpdatedState(clock)
    // A line lights a moment before its timestamp, so it's there as the singer starts it.
    val active by remember(lines) { derivedStateOf { lines.activeIndex(pos() + LINE_LEAD_MS) } }
    var following by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }

    // A drag we didn't start means the listener wants to look around.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) following = false
        }
    }
    LaunchedEffect(following, listState.isScrollInProgress) {
        if (!following && !listState.isScrollInProgress) {
            delay(RESUME_FOLLOW_MS)
            following = true
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val anchor = maxHeight * 0.3f
        LaunchedEffect(active, following, reduced) {
            if (!following) return@LaunchedEffect
            autoScrolling = true
            // +1 for the intro item ahead of the first line.
            try {
                glideTo(listState, (active + 1).coerceAtLeast(0), reduced)
            } finally {
                autoScrolling = false
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight * 0.6f),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "intro") {
                val firstStart = lines.firstOrNull()?.startMs ?: 0L
                val waiting by remember(firstStart) { derivedStateOf { firstStart > 2_500 && pos() < firstStart - 300 } }
                IntroDots(visible = waiting)
            }
            itemsIndexed(lines, key = { i, line -> "$i:${line.startMs}" }) { i, line ->
                LyricLineView(
                    line = line,
                    distance = i - active,
                    isActive = i == active,
                    animation = animation,
                    blurEnabled = blur && following,
                    position = clock,
                    romanized = romanized.getOrNull(i),
                    duet = duet,
                    onClick = {
                        onSeek(line.startMs)
                        following = true
                    },
                    onLongClick = onSyncLine?.let { sync -> { sync(line.startMs); following = true } },
                )
            }
            item(key = "credit") { Credit(source) }
        }

        AnimatedVisibility(
            visible = !following,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        ) {
            AssistChip(
                onClick = { following = true },
                label = { Text("Back to the music") },
                leadingIcon = { Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

/** Scroll [index] to the top of the content area (the anchor), smoothly when it's near. */
private suspend fun glideTo(state: LazyListState, index: Int, reduced: Boolean) {
    val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item != null && !reduced) {
        state.animateScrollBy(item.offset.toFloat(), tween(durationMillis = 650, easing = FastOutSlowInEasing))
    } else {
        state.scrollToItem(index)
    }
}

/** How one [LyricsAnimation] treats lines: sizes, blur per line away, and glow. */
private class LineMotion(
    val activeScale: Float,
    val inactiveScale: Float,
    val blurPerLine: Float,
    val glow: Boolean,
    val inactiveAlpha: Float,
)

private fun motionOf(animation: LyricsAnimation) = when (animation) {
    LyricsAnimation.FLUID -> LineMotion(1f, 0.97f, 1.8f, glow = false, inactiveAlpha = 0.8f)
    LyricsAnimation.KARAOKE -> LineMotion(1f, 1f, 0f, glow = true, inactiveAlpha = 0.55f)
    LyricsAnimation.ZOOM -> LineMotion(1.08f, 0.86f, 1.9f, glow = false, inactiveAlpha = 0.6f)
    LyricsAnimation.MINIMAL -> LineMotion(1f, 1f, 0f, glow = false, inactiveAlpha = 0.6f)
}

@Composable
private fun LyricLineView(
    line: LyricLine,
    distance: Int,
    isActive: Boolean,
    animation: LyricsAnimation,
    blurEnabled: Boolean,
    position: () -> Long,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    romanized: String? = null,
    duet: Boolean = false,
) {
    val motion = motionOf(animation)
    val bright = MaterialTheme.colorScheme.onSurface
    val dim = bright.copy(alpha = 0.45f)
    val scale by animateFloatAsState(
        if (isActive) motion.activeScale else motion.inactiveScale,
        // Critically damped: the line settles into size without overshooting, which read as a bounce.
        spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "lineScale",
    )
    val alpha by animateFloatAsState(
        if (isActive) 1f else (motion.inactiveAlpha - 0.06f * (minOf(abs(distance), 5) - 1) - if (distance < 0) 0.1f else 0f).coerceAtLeast(0.18f),
        tween(if (animation == LyricsAnimation.MINIMAL) 200 else 400),
        label = "lineAlpha",
    )
    val blur by animateDpAsState(
        if (!blurEnabled || isActive) 0.dp else (minOf(abs(distance), 6) * motion.blurPerLine).dp,
        tween(500),
        label = "lineBlur",
    )
    val ui by AppSettings.ui.collectAsState()
    val centred = ui.lyricsAlign == LyricsAlign.CENTER
    // In a duet the second voice's lines sit on the right and lines sung together in the middle.
    val align = when {
        !duet || centred -> if (centred) TextAlign.Center else TextAlign.Start
        line.voice == VOICE_SECOND -> TextAlign.End
        line.voice == VOICE_TOGETHER -> TextAlign.Center
        else -> TextAlign.Start
    }
    val origin = when (align) { TextAlign.Center -> 0.5f; TextAlign.End -> 1f; else -> 0f }
    val base = lyricsStyle().copy(textAlign = align)
    val style = if ((motion.glow || ui.lyricsGlow) && isActive) base.copy(shadow = Shadow(bright.copy(alpha = 0.55f), blurRadius = 24f)) else base
    val text = remember(line) { if (line.words.isEmpty()) line.text else line.words.joinToString("") { it.text } }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Where each word sits, worked out once per layout rather than every frame.
    val wordBoxes = remember(layout, line) {
        val l = layout ?: return@remember emptyList()
        var start = 0
        line.words.map { w ->
            val end = (start + w.text.length).coerceAtMost(text.length)
            val box = if (end > start) l.getPathForRange(start, end).getBounds() else Rect.Zero
            start = end
            box
        }
    }
    val sweep = isActive && line.words.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(remember { MutableInteractionSource() }, indication = null, onLongClick = onLongClick, onClick = onClick)
            .padding(vertical = 10.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                transformOrigin = TransformOrigin(origin, 0.5f)
                // The sweep below cuts into the text's own pixels, so it needs a layer of its own.
                if (sweep) compositingStrategy = CompositingStrategy.Offscreen
            }
            .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0.dp) Modifier.blur(blur) else Modifier),
    ) {
        Text(
            text = text,
            style = style,
            color = if (isActive) bright else dim,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    when {
                        sweep -> Modifier.drawWithContent { drawContent(); sweepWords(line, wordBoxes, position()) }
                        else -> Modifier
                    },
                ),
        )
        // Backing vocals, smaller and softer, under the line they're sung with.
        line.background?.let {
            Text(
                it,
                style = base.copy(fontSize = base.fontSize * 0.62f, lineHeight = base.lineHeight * 0.62f),
                color = (if (isActive) bright else dim).copy(alpha = if (isActive) 0.6f else 0.25f),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
        romanized?.let {
            Text(
                it,
                style = base.copy(fontSize = base.fontSize * 0.5f, lineHeight = base.lineHeight * 0.55f, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = (if (isActive) bright else dim).copy(alpha = if (isActive) 0.75f else 0.3f),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/**
 * Lights the active line word by word, the highlight sweeping along each
 * word as it's sung. The text is drawn bright; what isn't reached yet is
 * faded back to the dim lyric colour. This happens at draw time, so the
 * line isn't rebuilt and measured again every frame.
 */
private fun DrawScope.sweepWords(line: LyricLine, boxes: List<Rect>, positionMs: Long) {
    val feather = 14.dp.toPx()
    line.words.forEachIndexed { i, word ->
        val box = boxes.getOrNull(i) ?: return@forEachIndexed
        if (box.isEmpty) return@forEachIndexed
        val span = (word.endMs - word.startMs).coerceAtLeast(1)
        // Even through the word: an eased fill rushed the start and lagged the end, off the singing.
        val t = ((positionMs - word.startMs).toFloat() / span).coerceIn(0f, 1f)
        if (t >= 1f) return@forEachIndexed
        val litX = box.left + (box.width + feather) * t
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                1f to UNLIT,
                startX = litX - feather,
                endX = litX,
            ),
            topLeft = Offset(box.left, box.top),
            size = Size(box.width, box.height),
            blendMode = BlendMode.DstOut,
        )
    }
}

/** Cuts bright text down to the dim lyric colour's 0.45 alpha. */
private val UNLIT = Color.Black.copy(alpha = 0.55f)


/** While the sources are asked: a small pill saying the lyrics are on their way, with a turning mark. */
@Composable
internal fun SettingUpBadge(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(CircleShape)
            .background(colors.onSurface.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            Modifier.size(12.dp), color = colors.onSurface, strokeWidth = 1.5.dp, trackColor = colors.onSurface.copy(alpha = 0.2f),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Text("Setting up the lyrics…", style = MaterialTheme.typography.labelLarge, color = colors.onSurface.copy(alpha = 0.85f))
    }
}

/** Three dots breathing in sequence while an intro plays. */
@Composable
private fun IntroDots(visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val transition = rememberInfiniteTransition(label = "intro")
        Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { i ->
                val s by transition.animateFloat(
                    0.6f,
                    1f,
                    infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse),
                    label = "dot",
                )
                Box(
                    Modifier
                        .size(14.dp)
                        .graphicsLayer { scaleX = s; scaleY = s; alpha = s }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface),
                )
            }
        }
    }
}

@Composable
private fun Credit(source: String) {
    Spacer(Modifier.height(24.dp))
    Text(
        "Lyrics from $source",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Out of time? Long-press the line being sung to line them up.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 4.dp),
    )
}

private const val RESUME_FOLLOW_MS = 3_000L
/** How far ahead of its timestamp a line lights up. */
private const val LINE_LEAD_MS = 150L

/** The lyrics style at the size and alignment chosen in Settings. */
@Composable
private fun lyricsStyle(): androidx.compose.ui.text.TextStyle {
    val ui = AppSettings.ui.collectAsState().value
    val scale = ui.lyricsTextScale
    return LyricsTextStyle.copy(
        fontSize = LyricsTextStyle.fontSize * scale,
        lineHeight = LyricsTextStyle.lineHeight * scale,
        textAlign = if (ui.lyricsAlign == LyricsAlign.CENTER) TextAlign.Center else TextAlign.Start,
    )
}
