package com.opentune.ui.player

import android.content.ClipData
import android.content.res.Configuration
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.ui.draw.blur
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.opentune.data.LogExport
import com.opentune.data.covers.AlbumCovers
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.PlayerStyle
import com.opentune.data.settings.RemixPreset
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.data.together.Together
import com.opentune.playback.AudioFormatInfo
import com.opentune.playback.PlaybackRequests
import com.opentune.ui.LyricsState
import com.opentune.ui.PlayerViewModel
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.LocalBackdrop
import com.opentune.ui.components.LocalHazeState
import com.opentune.ui.components.LocalSongMenu
import com.opentune.ui.components.MarqueeText
import com.opentune.ui.components.MenuRow
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SongMenuSheet
import com.opentune.ui.components.glass
import com.opentune.ui.theme.PlayerTheme
import com.opentune.ui.theme.rememberArtworkSeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically

/**
 * The playback position, refreshed every frame while playing and the caller
 * is on screen (MediaController extrapolates between updates, so this is
 * smooth), and a few times a second while paused, to catch seeks.
 */
@Composable
fun rememberPlaybackPosition(vm: PlayerViewModel): () -> Long {
    val position = remember { mutableLongStateOf(vm.positionMs()) }
    LaunchedEffect(vm) {
        while (true) {
            if (vm.isPlaying.value) withFrameMillis { } else delay(250)
            position.longValue = vm.positionMs()
        }
    }
    return { position.longValue }
}

/** Everything the full player shows, so it can be drawn without a live player. */
data class PlayerUiState(
    val song: Song,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val hasNext: Boolean,
    val shuffle: Boolean,
    val repeatMode: Int,
    val durationMs: Long,
    val lyrics: LyricsState,
    val queue: List<Song>,
    val currentIndex: Int,
    val upNext: List<Int>,
    val sound: SoundSettings,
    val theme: ThemeSettings,
    val ui: InterfaceSettings = InterfaceSettings(),
    /** "Playing from" label: an album, a playlist, Search… */
    val source: String? = null,
    val audioFormat: AudioFormatInfo? = null,
    /** Off, shuffle or smart shuffle, for the shuffle button. [shuffle] is the player's own shuffle order. */
    val shuffleMode: com.opentune.playback.ShuffleMode = if (shuffle) com.opentune.playback.ShuffleMode.ON else com.opentune.playback.ShuffleMode.OFF,
    /** The queue came from a playlist or album, so the shuffle button offers smart shuffle. */
    val smartShuffleAvailable: Boolean = false,
)

class PlayerActions(
    val togglePlay: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val toggleShuffle: () -> Unit,
    val cycleRepeat: () -> Unit,
    val seekTo: (Long) -> Unit,
    val playIndex: (Int) -> Unit,
    val removeIndex: (Int) -> Unit,
    val collapse: () -> Unit,
    val moveIndex: (Int, Int) -> Unit = { _, _ -> },
    val openTogether: () -> Unit = {},
    val openEqualizer: () -> Unit = {},
)

@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    onCollapse: () -> Unit,
    /** True while the player sits fully open, not being dragged down. */
    onCovering: (Boolean) -> Unit = {},
    onTogether: () -> Unit = {},
    onEqualizer: () -> Unit = {},
    /** Docked beside the page on a large screen; see [PlayerLayout]. */
    docked: Boolean = false,
) {
    val song by vm.currentSong.collectAsState()
    val current = song ?: return
    val theme by AppSettings.theme.collectAsState()
    val sound by AppSettings.sound.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isBuffering by vm.isBuffering.collectAsState()
    val hasNext by vm.hasNext.collectAsState()
    val shuffle by vm.shuffleEnabled.collectAsState()
    val shuffleMode by vm.shuffleMode.collectAsState()
    val smartShuffleAvailable by vm.smartShuffleAvailable.collectAsState()
    val repeat by vm.repeatMode.collectAsState()
    val duration by vm.durationMs.collectAsState()
    val lyrics by vm.lyrics.collectAsState()
    val queue by vm.queue.collectAsState()
    val currentIndex by vm.currentIndex.collectAsState()
    val upNext by vm.upNext.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val shown = current
    val source by vm.source.collectAsState()
    val audioFormat by vm.audioFormat.collectAsState()
    val actions = remember(vm, onCollapse) {
        PlayerActions(
            togglePlay = vm::togglePlayPause,
            next = vm::skipNext,
            previous = vm::skipPrevious,
            toggleShuffle = { vm.cycleShuffle() },
            cycleRepeat = vm::cycleRepeatMode,
            seekTo = vm::seekTo,
            playIndex = vm::playQueueItem,
            removeIndex = vm::removeQueueItem,
            collapse = onCollapse,
            moveIndex = vm::moveQueueItem,
            openTogether = onTogether,
            openEqualizer = onEqualizer,
        )
    }
    PlayerLayout(
        PlayerUiState(
            shown, isPlaying, isBuffering, hasNext, shuffle, repeat, duration,
            lyrics, queue, currentIndex, upNext, sound, theme, ui, source, audioFormat,
            shuffleMode, smartShuffleAvailable,
        ),
        position = rememberPlaybackPosition(vm),
        buffered = vm::bufferedPositionMs,
        actions = actions,
        seeks = rememberSeekCount(vm),
        onCovering = onCovering,
        docked = docked,
    )
}

/** A count that rises on each manual seek, for the lyrics clock to take a jump cleanly. */
@Composable
fun rememberSeekCount(vm: PlayerViewModel): () -> Long {
    val count = remember { mutableLongStateOf(0L) }
    LaunchedEffect(vm) { vm.seeks.collect { count.longValue++ } }
    return { count.longValue }
}

private enum class Pane { COVER, LYRICS, QUEUE }

@Composable
fun PlayerLayout(
    state: PlayerUiState,
    position: () -> Long,
    buffered: () -> Long,
    actions: PlayerActions,
    /** Rises on each manual seek, for the lyrics clock to take jumps cleanly. */
    seeks: () -> Long = { 0L },
    initialLyrics: Boolean = false,
    onCovering: (Boolean) -> Unit = {},
    /**
     * Docked in a pane beside the page, on a large screen: the upright
     * layout whatever the screen's orientation, nothing to drag down, and
     * [PlayerActions.collapse] folds the pane away instead of closing a sheet.
     */
    docked: Boolean = false,
) {
    val current = state.song
    val theme = state.theme
    val sound = state.sound

    var paneName by rememberSaveable { mutableStateOf(if (initialLyrics) Pane.LYRICS.name else Pane.COVER.name) }
    val pane = Pane.valueOf(paneName)
    fun toggle(p: Pane) { paneName = if (pane == p) Pane.COVER.name else p.name }
    // The cover between the lyrics or queue header and the full cover: tap the
    // small one and it grows into the big one (the lyrics make way); swipe up on
    // the big one and it goes back into the header of the view it came from.
    var returnPane by rememberSaveable { mutableStateOf<String?>(null) }
    var smallCover by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val flight = remember { PaneFlight() }
    var showRemix by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showOffset by remember { mutableStateOf(false) }
    var showLyricsSource by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showSignal by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    // Lyrics run on their own clock, shifted by this song's offset, or the
    // one found by comparing lyrics sources when none is set.
    val lyricsSettings by AppSettings.lyrics.collectAsState()
    val autoOffsetMs = ((state.lyrics as? LyricsState.Found)?.lyrics as? com.opentune.data.lyrics.Lyrics.Synced)?.autoOffsetMs ?: 0L
    val offsetMs = com.opentune.data.lyrics.lyricsOffset(lyricsSettings.offsets[current.videoId], (state.lyrics as? LyricsState.Found)?.lyrics)
    val lyricsPosition: () -> Long = { position() + offsetMs }
    // For the lyrics clock: the intent to play (true through a buffer stall, so the
    // words coast rather than snapping back), and [seeks], which rises on each seek so
    // a jump is taken cleanly instead of being refused as a backward reading.
    val lyricsPlaying: () -> Boolean = { state.isPlaying || state.isBuffering }
    val lyricsSeeks: () -> Long = seeks
    // Long-press on the line being sung: shift this song's lyrics so that line starts now.
    val syncLine: (Long) -> Unit = { lineStart ->
        val shift = lineStart - position()
        AppSettings.setLyricsOffset(current.videoId, shift)
        Toast.makeText(context, "Lyrics moved %+.1f s for this song".format(shift / 1000f), Toast.LENGTH_SHORT).show()
    }
    // Lyrics take the screen: the controls go after LYRICS_FOCUS_MS untouched while
    // synced lyrics play on the lyrics view, and come back at the next touch.
    var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var controlsAway by remember { mutableStateOf(false) }
    val focusable = state.ui.lyricsFocus && pane == Pane.LYRICS && state.isPlaying &&
        ((state.lyrics as? LyricsState.Found)?.lyrics is com.opentune.data.lyrics.Lyrics.Synced) &&
        !showMenu && !showRemix && !showSleep && !showCast && !showOffset && !showLyricsSource && !showDetails && !showSignal
    LaunchedEffect(touchedAt, focusable) {
        controlsAway = false
        if (!focusable) return@LaunchedEffect
        delay(LYRICS_FOCUS_MS)
        controlsAway = true
    }
    // What came of an "Upgrade quality" from the menu: switched, already the best, or not in time.
    LaunchedEffect(Unit) {
        com.opentune.playback.PlaybackRequests.upgradeResult.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    // Swiping up through the lyrics slides the controls down out of the way;
    // swiping back down brings them up again. Only the finger's own scrolling
    // counts, past a short distance, so the list's glide to the next line doesn't.
    var scrolledAway by remember { mutableStateOf(false) }
    LaunchedEffect(pane) { if (pane != Pane.LYRICS) scrolledAway = false }
    val swipePx = with(LocalDensity.current) { 24.dp.toPx() }
    val lyricsScroll = remember(swipePx) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            private var run = 0f
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                val dy = available.y
                if ((run > 0f && dy < 0f) || (run < 0f && dy > 0f)) run = 0f
                run += dy
                if (run <= -swipePx) { scrolledAway = true; run = 0f }
                else if (run >= swipePx) { scrolledAway = false; run = 0f }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val controlsHidden = controlsAway || (scrolledAway && pane == Pane.LYRICS)
    val liked by LibraryStore.liked.collectAsState()
    val isLiked = liked.any { it.videoId == current.videoId }
    val device = rememberOutputDeviceName()
    val outputRoute = rememberOutputRoute()
    val sleepLabel = sleepTimerLabel()

    // Drag down anywhere outside the lyrics and queue lists to close.
    // Plain state, written straight from the drag: the release spring then
    // can't be cancelled by a drag step still on its way, which used to leave
    // the player parked part-way down after a short pull.
    var dragOffset by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val covering by rememberUpdatedState(onCovering)
    LaunchedEffect(Unit) {
        snapshotFlow { dragOffset == 0f }.distinctUntilChanged().collect { covering(it) }
    }
    // The cover leaves the card as it's dragged down and shrinks toward the
    // mini player with the finger; let go short and it settles back with the card.
    LaunchedEffect(docked, state.ui.reduceAnimation) {
        if (docked || state.ui.reduceAnimation) return@LaunchedEffect
        snapshotFlow { dragOffset }.collect { com.opentune.ui.components.PlayerMorph.dragClose(it) }
    }
    val scope = rememberCoroutineScope()
    val dismissPx = with(LocalDensity.current) { 140.dp.toPx() }
    val backdrop = rememberLayerBackdrop()
    val style = state.ui.playerStyle
    val minimal = style == PlayerStyle.MINIMAL
    // Docked in a short window (an unfolded phone on its side), the extras
    // under the controls make way so the cover keeps some size.
    val windowHeight = with(LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() }
    val roomy = !docked || windowHeight >= DOCKED_ROOMY_HEIGHT
    // On its side the player has its own layout.
    val landscape = !docked && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Full-screen cover only fits the layouts built around a plain square cover.
    val fullCover = !landscape && state.ui.fullScreenCover && pane == Pane.COVER && (style == PlayerStyle.CLASSIC || style == PlayerStyle.MINIMAL)
    // Where the cover area ends, measured from the top of the player, so a
    // full-screen cover can run down to the title on any screen height.
    var playerCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var coverBottomPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    // Only the plain square cover flies; the vinyl, cassette and other designs just change view.
    val flies = !state.ui.reduceAnimation && !docked && !landscape &&
        style != PlayerStyle.VINYL && style != PlayerStyle.CASSETTE && style != PlayerStyle.HALO &&
        style != PlayerStyle.POLAROID && style != PlayerStyle.LYRICS_FIRST &&
        !(state.ui.fullScreenCover && (style == PlayerStyle.CLASSIC || style == PlayerStyle.MINIMAL))
    fun expandCover() {
        returnPane = pane.name
        val from = smallCover
        if (flies && from != null) flight.start(from, toSmall = false)
        paneName = Pane.COVER.name
    }
    fun collapseCover() {
        val back = returnPane?.let { runCatching { Pane.valueOf(it) }.getOrNull() }?.takeIf { it != Pane.COVER } ?: Pane.QUEUE
        val from = com.opentune.ui.components.PlayerMorph.playerCover?.let(com.opentune.ui.components.PlayerMorph::scaled)
        smallCover = null
        if (flies && from != null) flight.start(from, toSmall = true)
        paneName = back.name
        returnPane = null
    }
    // Flies the cover to wherever its new place turns out to be, once that's laid out.
    LaunchedEffect(flight.runs) {
        if (flight.runs == 0) return@LaunchedEffect
        try {
            flight.t.snapTo(0f)
            var tries = 0
            while (flight.target(smallCover) == null && tries++ < 30) delay(16)
            if (flight.target(smallCover) == null) return@LaunchedEffect
            flight.t.animateTo(1f, tween(440, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        } finally {
            flight.end()
        }
    }
    var upPull by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val upPx = with(LocalDensity.current) { 64.dp.toPx() }

    PlayerTheme(seed = rememberArtworkSeed(current.thumbnailUrl), settings = theme) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Dragging down turns the player into a card over the app,
                    // the way iOS sheets do: it shrinks from the top edge and
                    // its corners round, so the screen behind shows around it.
                    val p = (dragOffset / (dismissPx * 2)).coerceIn(0f, 1f)
                    translationY = dragOffset * 0.9f
                    val scale = 1f - 0.12f * p
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    shape = RoundedCornerShape(40.dp * p)
                    clip = p > 0f
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    enabled = !docked,
                    state = rememberDraggableState { delta ->
                        // Upward, with the card at rest: a swipe up on the cover, counted on its own.
                        if (delta < 0f && dragOffset == 0f) upPull += delta
                        dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                    },
                    // A finger landing on the card mid-spring takes hold of it where it is.
                    onDragStarted = { settling?.cancel(); upPull = 0f },
                    onDragStopped = { velocity ->
                        if (pane == Pane.COVER && style != PlayerStyle.LYRICS_FIRST && dragOffset == 0f && (upPull < -upPx || velocity < -1_500f)) {
                            // Swiped up on the cover: it goes back up into the lyrics or queue header.
                            collapseCover()
                        } else if (dragOffset > dismissPx || velocity > 2_000f) {
                            // Leave the card as it is: the exit slide carries it away from here.
                            actions.collapse()
                        } else {
                            settling = scope.launch {
                                androidx.compose.animation.core.animate(dragOffset, 0f, velocity, spring(dampingRatio = 0.82f, stiffness = 420f)) { v, _ ->
                                    dragOffset = v.coerceAtLeast(0f)
                                }
                                dragOffset = 0f
                            }
                        }
                    },
                )
                .onGloballyPositioned { playerCoords = it },
        ) {
            // The backdrop is the layer the player's Liquid Glass bends.
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                PlayerBackdrop(
                    theme.playerBackground,
                    current.thumbnailUrl,
                    Modifier.fillMaxSize(),
                    animate = !state.ui.reduceAnimation,
                    fullCover = fullCover,
                    fullCoverBottom = with(LocalDensity.current) { coverBottomPx.takeIf { it > 0f }?.toDp() },
                    movingCover = state.ui.movingCover && !state.ui.reduceAnimation,
                    playing = state.isPlaying,
                    change = if (state.ui.reduceAnimation) com.opentune.data.settings.CoverChange.FADE else state.ui.coverChange,
                    index = state.currentIndex,
                )
            }

            // Behind the lyrics, the cover's colours drift slowly.
            androidx.compose.animation.AnimatedVisibility(
                visible = pane == Pane.LYRICS && !landscape,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.fillMaxSize(),
            ) {
                val scheme = MaterialTheme.colorScheme
                AmbientLights(
                    listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.inversePrimary, scheme.primaryContainer),
                    playing = state.isPlaying,
                    animate = !state.ui.reduceAnimation,
                    modifier = Modifier.fillMaxSize(),
                    field = true,
                )
            }

            // The player draws its own dark surface, so it sets its own text and
            // icon color rather than inheriting the app's (light in light theme).
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                LocalBackdrop provides backdrop,
                LocalHazeState provides null,
            ) {
            // On its side the cover goes left and the rest stacks on the right;
            // lyrics and the queue open in the cover's place.
            if (landscape) {
                LandscapePlayer(
                    state, position, lyricsPosition, actions,
                    liked = isLiked,
                    device = sleepLabel ?: device,
                    onLike = { LibraryStore.setLiked(current, !isLiked) },
                    onMore = { showMenu = true },
                    onSignal = { showSignal = true },
                    onLyrics = { toggle(Pane.LYRICS) },
                    onQueue = { toggle(Pane.QUEUE) },
                    showLines = pane != Pane.LYRICS,
                    side = when (pane) {
                        Pane.COVER -> null
                        Pane.LYRICS -> { ->
                            LyricsView(
                                state.lyrics,
                                lyricsPosition,
                                onSeek = { actions.seekTo((it - offsetMs).coerceAtLeast(0)) },
                                modifier = Modifier.fillMaxSize().nestedScroll(lyricsScroll).focusIn(!state.ui.reduceAnimation),
                                synced = state.ui.syncedLyrics,
                                blur = state.ui.blurLyrics,
                                shareSong = current,
                                onSyncLine = syncLine,
                                playing = lyricsPlaying,
                                seeks = lyricsSeeks,
                            )
                        }
                        Pane.QUEUE -> { ->
                            QueuePane(
                                queue = state.queue,
                                currentIndex = state.currentIndex,
                                upNext = state.upNext,
                                isPlaying = state.isPlaying,
                                shuffle = state.shuffle,
                                smartShuffle = state.shuffleMode == com.opentune.playback.ShuffleMode.SMART,
                                onPlayIndex = actions.playIndex,
                                onRemoveIndex = actions.removeIndex,
                                onMove = actions.moveIndex,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
                )
            } else Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 24.dp)
                    // Every touch anywhere on the player, seen before the parts under it
                    // handle it and never taken from them: it counts as being looked at.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val now = System.currentTimeMillis()
                                // A few times a second is plenty, and keeps a drag from recomposing the player each frame.
                                if (e.changes.any { it.pressed } && (controlsAway || now - touchedAt > 250)) touchedAt = now
                            }
                        }
                    },
            ) {
                // Grab handle, then "Playing from" or the remix pill.
                // Docked, there's nothing to pull down: a button at the end folds the pane away.
                Column(Modifier.fillMaxWidth().padding(top = if (docked) 0.dp else 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!docked) Box(
                        Modifier.size(width = 40.dp, height = 5.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                            .clickable(onClick = actions.collapse),
                    )
                    Box(if (docked) Modifier.fillMaxWidth().height(48.dp) else Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                        if (docked) {
                            Box(
                                Modifier.align(Alignment.CenterEnd).size(40.dp).clip(CircleShape).clickable(onClick = actions.collapse),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Hide player", Modifier.size(26.dp))
                            }
                        }
                        val room by Together.room.collectAsState()
                        AnimatedContent(sound.isDefault, label = "remixPill", modifier = if (docked) Modifier.padding(horizontal = 44.dp) else Modifier) { normal ->
                            if (room != null) {
                                TogetherPill(room!!, actions.openTogether)
                            } else if (normal) {
                                if (!state.ui.hideSongStatus) SongStatus(state.source)
                            } else {
                                AssistChip(
                                    onClick = { showRemix = true },
                                    label = { Text(RemixPreset.matching(sound)?.label ?: "Custom remix") },
                                    leadingIcon = { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(18.dp)) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                }

                // Cover mode: the art fills the space and the title sits below it.
                // Lyrics and queue: a compact header, then the list.
                // Lyrics and queue share one layout (the header stays put and only the
                // list below it changes), so the outer change is cover against list.
                AnimatedContent(
                    if (pane == Pane.COVER) Pane.COVER else Pane.LYRICS,
                    transitionSpec = {
                        when {
                            state.ui.reduceAnimation -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                            // Lyrics rise into place on a soft spring while the cover sinks back.
                            targetState == Pane.LYRICS ->
                                (fadeIn(tween(220)) + slideInVertically(spring(dampingRatio = 1f, stiffness = 650f)) { it / 5 }) togetherWith
                                    (fadeOut(tween(220)) + scaleOut(tween(240, easing = FastOutSlowInEasing), 0.9f))
                            // And going back, the cover comes forward as they drop away.
                            initialState == Pane.LYRICS ->
                                (fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 1f, stiffness = 650f), 0.9f)) togetherWith
                                    (fadeOut(tween(220)) + slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it / 6 })
                            else ->
                                (fadeIn(tween(320)) + scaleIn(tween(240, easing = FastOutSlowInEasing), 0.94f)) togetherWith
                                    (fadeOut(tween(180)) + scaleOut(tween(220), 0.98f))
                        }
                    },
                    label = "pane",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { p ->
                    Column(Modifier.fillMaxSize()) {
                        if (p == Pane.COVER && style == PlayerStyle.LYRICS_FIRST) {
                            // The lyrics are the page; the cover shrinks to the header.
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(current.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(84.dp), RoundedCornerShape(14.dp))
                                Spacer(Modifier.width(16.dp))
                                TitleRow(current, isLiked, onLike = { LibraryStore.setLiked(current, !isLiked) }, onMore = { showMenu = true }, compact = true)
                            }
                            LyricsView(
                                state.lyrics,
                                lyricsPosition,
                                onSeek = { actions.seekTo((it - offsetMs).coerceAtLeast(0)) },
                                modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(lyricsScroll),
                                synced = state.ui.syncedLyrics,
                                blur = state.ui.blurLyrics,
                                shareSong = current,
                                onSyncLine = syncLine,
                                playing = lyricsPlaying,
                                seeks = lyricsSeeks,
                            )
                        } else if (p == Pane.COVER) {
                            Box(Modifier.weight(1f).fillMaxWidth().then(if (docked) Modifier.fitSquare() else Modifier), contentAlignment = Alignment.Center) {
                                when {
                                    fullCover ->
                                        ArtworkSwipeArea(
                                            onSwipeNext = actions.next,
                                            onSwipePrevious = actions.previous,
                                            modifier = Modifier.fillMaxSize().onGloballyPositioned { c ->
                                                val root = playerCoords ?: return@onGloballyPositioned
                                                if (root.isAttached && c.isAttached) {
                                                    coverBottomPx = root.localPositionOf(c, androidx.compose.ui.geometry.Offset(0f, c.size.height.toFloat())).y
                                                }
                                            },
                                        )
                                    style == PlayerStyle.VINYL ->
                                        VinylPane(current, state.isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous, animate = !state.ui.reduceAnimation)
                                    style == PlayerStyle.CASSETTE ->
                                        CassettePane(
                                            current, state.isPlaying,
                                            progress = { if (state.durationMs > 0) position() / state.durationMs.toFloat() else 0f },
                                            onSwipeNext = actions.next, onSwipePrevious = actions.previous, animate = !state.ui.reduceAnimation,
                                        )
                                    style == PlayerStyle.HALO ->
                                        HaloPane(current, state.isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous, animate = !state.ui.reduceAnimation)
                                    style == PlayerStyle.POLAROID ->
                                        PolaroidPane(current, state.isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous, animate = !state.ui.reduceAnimation)
                                    else ->
                                        ArtworkPane(
                                            current, state.isPlaying, onSwipeNext = actions.next, onSwipePrevious = actions.previous,
                                            moving = state.ui.movingCover && !state.ui.reduceAnimation,
                                            change = if (state.ui.reduceAnimation) com.opentune.data.settings.CoverChange.FADE else state.ui.coverChange,
                                            index = state.currentIndex,
                                            onSeekBy = { d -> actions.seekTo((position() + d).coerceIn(0L, state.durationMs.coerceAtLeast(0L))) },
                                            glow = state.ui.coverGlow && !state.ui.reduceAnimation,
                                        )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            TitleRow(current, isLiked, onLike = { LibraryStore.setLiked(current, !isLiked) }, onMore = { showMenu = true })
                        } else {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(
                                    current.thumbnailUrl.artworkAt(ROW_ART_PX),
                                    Modifier.size(64.dp)
                                        .onGloballyPositioned { smallCover = it.boundsInRoot() }
                                        .graphicsLayer { alpha = if (flight.active) 0f else 1f }
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(onClickLabel = "Show the cover") { expandCover() },
                                    RoundedCornerShape(10.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                TitleRow(current, isLiked, onLike = { LibraryStore.setLiked(current, !isLiked) }, onMore = { showMenu = true }, compact = true)
                            }
                            // Between lyrics and queue the new list slides up from below on a soft
                            // spring while the old one lifts a little and fades, under a still header.
                            AnimatedContent(
                                pane.takeIf { it != Pane.COVER } ?: Pane.LYRICS,
                                transitionSpec = {
                                    if (state.ui.reduceAnimation) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                                    else (slideInVertically(spring(dampingRatio = 0.9f, stiffness = 360f)) { it } + fadeIn(tween(260))) togetherWith
                                        (slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { -it / 6 } + fadeOut(tween(220)))
                                },
                                label = "list",
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            ) { list ->
                                Column(Modifier.fillMaxSize()) {
                                    if (list == Pane.LYRICS) {
                                        LyricsView(
                                            state.lyrics,
                                            lyricsPosition,
                                            onSeek = { actions.seekTo((it - offsetMs).coerceAtLeast(0)) },
                                            modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(lyricsScroll).focusIn(!state.ui.reduceAnimation),
                                            synced = state.ui.syncedLyrics,
                                            blur = state.ui.blurLyrics,
                                            shareSong = current,
                                            onSyncLine = syncLine,
                                            playing = lyricsPlaying,
                                            seeks = lyricsSeeks,
                                        )
                                    } else {
                                        Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                                        QueuePane(
                                            queue = state.queue,
                                            currentIndex = state.currentIndex,
                                            upNext = state.upNext,
                                            isPlaying = state.isPlaying,
                                            shuffle = state.shuffle,
                                            smartShuffle = state.shuffleMode == com.opentune.playback.ShuffleMode.SMART,
                                            onPlayIndex = actions.playIndex,
                                            onRemoveIndex = actions.removeIndex,
                                            onMove = actions.moveIndex,
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val synced = (state.lyrics as? LyricsState.Found)?.lyrics as? com.opentune.data.lyrics.Lyrics.Synced
                // Lyrics are timed against the song shifted by the offset; the bar is the song's own time.
                val choruses = remember(synced, offsetMs) { synced?.let { com.opentune.data.lyrics.SongParts.choruses(it).map { ms -> ms - offsetMs } }.orEmpty() }
                val marks = remember(synced, offsetMs) { synced?.let { com.opentune.data.lyrics.SongParts.marks(it).map { ms -> ms - offsetMs } }.orEmpty() }
                val nextChorus by androidx.compose.runtime.produceState<Long?>(null, choruses) {
                    while (true) {
                        value = com.opentune.data.lyrics.SongParts.nextChorus(choruses, position())
                        kotlinx.coroutines.delay(500)
                    }
                }
                // Lyrics with nothing in the way: a few seconds untouched while they
                // play, and the controls slide down out of sight.
                AnimatedVisibility(
                    !controlsHidden,
                    enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = 380f), expandFrom = Alignment.Top) + slideInVertically(spring(dampingRatio = 0.9f, stiffness = 380f)) { it / 2 } + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(520, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + slideOutVertically(tween(520, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(tween(360)),
                ) {
                Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(state.ui.skipToChorus && nextChorus != null, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                        SkipToChorus { nextChorus?.let { actions.seekTo((it - 250).coerceAtLeast(0)) } }
                    }
                }
                if (pane != Pane.LYRICS && state.ui.syncedLyrics && !minimal && roomy && !(pane == Pane.COVER && style == PlayerStyle.LYRICS_FIRST)) {
                    LyricPreview(state.lyrics, lyricsPosition, onOpen = { paneName = Pane.LYRICS.name }, Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                // The line at a point of the song, for the bubble shown while the bar is dragged.
                val lyricAt: ((Long) -> String?)? = if (state.ui.lyricScrubPreview && synced != null) {
                    { ms -> synced.lines.lastOrNull { it.startMs <= ms + offsetMs }?.text?.takeIf { it.isNotBlank() } }
                } else null
                SeekBar(position, buffered, state.durationMs, onSeek = actions.seekTo, wavy = state.ui.wavySeekbar && !state.ui.reduceAnimation, playing = state.isPlaying, lyricAt = lyricAt, marks = marks, quality = { if (state.ui.sourcePill) SourceQualityBadge(current.videoId, state.audioFormat, onClick = { showSignal = true }) })
                if (state.ui.statsForNerds) {
                    Box(Modifier.clickable { showSignal = true }) { NerdStatsLine(state.audioFormat) }
                }
                PlayerControls(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    hasNext = state.hasNext,
                    onTogglePlay = actions.togglePlay,
                    onNext = actions.next,
                    onPrevious = actions.previous,
                    style = state.ui.controlStyle,
                    animate = !state.ui.reduceAnimation,
                    progress = { if (state.durationMs > 0) position() / state.durationMs.toFloat() else 0f },
                )
                if (!state.ui.hideVolumeBar && !minimal && roomy) VolumeBar(Modifier.padding(vertical = 4.dp))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassToggle(Icons.Rounded.Lyrics, "Lyrics", pane == Pane.LYRICS, { toggle(Pane.LYRICS) })
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AnimatedContent(pane == Pane.QUEUE, label = "pill") { inQueue ->
                            if (inQueue) {
                                ModesPill(state.shuffleMode, state.smartShuffleAvailable, state.repeatMode, actions.toggleShuffle, actions.cycleRepeat)
                            } else {
                                Row(
                                    Modifier.height(52.dp).clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GlassToggle(Icons.Rounded.Bedtime, "Sleep timer", sleepLabel != null, { showSleep = true })
                                    GlassToggle(
                                        when {
                                            outputRoute == OutputRoute.CAST -> Icons.Rounded.Cast
                                            outputRoute.isHeadset -> Icons.Rounded.Headphones
                                            else -> Icons.Rounded.Smartphone
                                        },
                                        "Audio output",
                                        outputRoute != OutputRoute.PHONE,
                                        { showOutput = true },
                                    )
                                }
                            }
                        }
                    }
                    GlassToggle(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", pane == Pane.QUEUE, { toggle(Pane.QUEUE) })
                }
                if (!minimal && roomy) Text(
                    sleepLabel ?: device,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                )
                }
                }
                // Where the controls went: a faint handle, and a swipe or touch brings them back.
                AnimatedVisibility(controlsHidden, enter = fadeIn(tween(400)), exit = fadeOut(tween(150))) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 44.dp, height = 5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)))
                    }
                }
            }
            }
            // The cover on its way between the header and the full cover, over the rest.
            if (flight.active) {
                val from = flight.from
                val to = flight.target(smallCover)
                val origin = playerCoords?.takeIf { it.isAttached }?.positionInRoot() ?: androidx.compose.ui.geometry.Offset.Zero
                if (from != null && to != null) {
                    val t = flight.t.value
                    fun lerp(a: Float, b: Float) = a + (b - a) * t
                    val density = LocalDensity.current
                    val small = 10f
                    val big = 28f
                    val corner = (if (flight.toSmall) lerp(big, small) else lerp(small, big)).dp
                    Artwork(
                        current.thumbnailUrl.artworkAt(com.opentune.data.model.PLAYER_ART_PX),
                        Modifier
                            .offset { androidx.compose.ui.unit.IntOffset((lerp(from.left, to.left) - origin.x).toInt(), (lerp(from.top, to.top) - origin.y).toInt()) }
                            .size(with(density) { lerp(from.width, to.width).toDp() }, with(density) { lerp(from.height, to.height).toDp() })
                            .shadow(with(density) { (if (flight.toSmall) 24f * (1f - t) else 24f * t).toDp() }, RoundedCornerShape(corner)),
                        RoundedCornerShape(corner),
                    )
                }
            }
        }

        // Winding down for the night: the whole player dims with the music.
        val windDown by com.opentune.playback.SleepTimer.windDown.collectAsState()
        if (windDown > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = (windDown * 0.55f).coerceIn(0f, 0.55f) }.background(Color.Black))
        }
        if (showRemix) RemixSheet(onDismiss = { showRemix = false })
        if (showOutput) com.opentune.ui.cast.AudioOutputSheet(
            onDismiss = { showOutput = false },
            onCast = { showOutput = false; showCast = true },
            onPipeline = { showOutput = false; showSignal = true },
        )
        if (showCast) com.opentune.ui.cast.CastSheet(onDismiss = { showCast = false })
        if (showSleep) SleepTimerDialog(onDismiss = { showSleep = false })
        if (showMenu) {
            SongMenuSheet(
                current,
                onDismiss = { showMenu = false },
                top = { close -> MenuRow(Icons.Rounded.HighQuality, "Upgrade quality") { close(); PlaybackRequests.upgradeQuality() } },
                tools = { close ->
                    MenuRow(Icons.Rounded.HighQuality, "Track details") { close(); showDetails = true }
                    MenuRow(Icons.Rounded.GraphicEq, "Signal path") { close(); showSignal = true }
                    MenuRow(Icons.Rounded.Groups, "Listen together") { close(); actions.openTogether() }
                    MenuRow(Icons.Rounded.Bedtime, sleepLabel ?: "Sleep timer") { close(); showSleep = true }
                    MenuRow(Icons.Rounded.Tune, "Lyrics offset") { close(); showOffset = true }
                    MenuRow(Icons.Rounded.Lyrics, "Lyrics source") { close(); showLyricsSource = true }
                    if (com.opentune.data.lyrics.Romanizer.available) {
                        MenuRow(Icons.Rounded.Translate, if (lyricsSettings.romanize) "Hide romanized lyrics" else "Show romanized lyrics") {
                            close()
                            AppSettings.updateLyrics { it.copy(romanize = !it.romanize) }
                        }
                    }
                    MenuRow(Icons.Rounded.Equalizer, "Equalizer") { close(); actions.openEqualizer() }
                    MenuRow(Icons.Rounded.GraphicEq, "Remix") { close(); showRemix = true }
                },
                end = { close ->
                    MenuRow(Icons.Rounded.BugReport, "Copy log") {
                        close()
                        scope.launch {
                            val log = LogExport.recent()
                            clipboard.setPrimaryClip(ClipData.newPlainText("OpenTune log", log))
                            Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
        if (showOffset) LyricsOffsetDialog(current.videoId, autoOffsetMs, onDismiss = { showOffset = false })
        if (showLyricsSource) LyricsSourceDialog(current, state.durationMs, onDismiss = { showLyricsSource = false })
        if (showDetails) TrackDetailsDialog(current, state.durationMs, onDismiss = { showDetails = false })
        if (showSignal) SignalPathDialog(state.audioFormat, onDismiss = { showSignal = false })
    }
}

/**
 * Shifts this song's lyrics earlier or later when they run out of step with
 * the audio. Kept per song. Starts from [autoMs], the shift found by
 * comparing lyrics sources, until one is set here; Reset goes back to it.
 */
@Composable
private fun LyricsOffsetDialog(videoId: String, autoMs: Long, onDismiss: () -> Unit) {
    val lyrics by AppSettings.lyrics.collectAsState()
    val manual = lyrics.offsets[videoId]
    val ms = manual ?: autoMs
    // A chosen 0 is kept when there's an automatic shift it overrides.
    fun set(v: Long) = AppSettings.setLyricsOffset(videoId, v.coerceIn(-10_000, 10_000), keepZero = autoMs != 0L)
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lyrics offset") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        ms == 0L -> "In step"
                        ms > 0 -> "%.1f s earlier".format(ms / 1000f)
                        else -> "%.1f s later".format(-ms / 1000f)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (manual == null && autoMs != 0L) {
                    Text(
                        "Set to match the other lyrics sources",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    "Earlier if the words come after the singing, later if they come before.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-500L to "−0.5", -100L to "−0.1", 100L to "+0.1", 500L to "+0.5").forEach { (step, label) ->
                        OutlinedButton(onClick = { set(ms + step) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label) }
                    }
                }
            }
        },
        confirmButton = { SheetButton("Done", onClick = onDismiss, closes = true) },
        dismissButton = { SheetButton("Reset", onClick = { AppSettings.setLyricsOffset(videoId, 0) }) },
    )
}

/** Title and artist with the heart and "…" glass buttons on the right. */
@Composable
private fun RowScope.TitleRow(song: Song, liked: Boolean, onLike: () -> Unit, onMore: () -> Unit, compact: Boolean = false) {
    Column(Modifier.weight(1f)) {
        // Tapping the title opens its album, and the artist opens their page.
        val menu = LocalSongMenu.current
        val album = song.albumId?.takeIf { menu != null && !song.videoId.startsWith("local:") }
        MarqueeText(
            song.title,
            Modifier.fillMaxWidth().then(if (album != null) Modifier.clickable(onClickLabel = "Open album") { menu?.openAlbum?.invoke(album) } else Modifier),
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
        )
        // Each artist's name opens their own page.
        val named = menu?.viewArtistNamed?.takeIf { !song.videoId.startsWith("local:") }
        com.opentune.ui.components.ArtistLinks(
            song.artist,
            onArtist = named?.let { open -> { name: String -> open(song, name) } },
            explicit = song.isExplicit == true,
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Spacer(Modifier.width(10.dp))
    HeartButton(liked, onLike)
    Spacer(Modifier.width(10.dp))
    Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onMore), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MoreHoriz, "More", Modifier.size(26.dp))
    }
}

@Composable
private fun TitleRow(song: Song, liked: Boolean, onLike: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TitleRow(song, liked, onLike, onMore, compact = false)
    }
}

/** The heart pops when tapped on. */
@Composable
private fun HeartButton(liked: Boolean, onClick: () -> Unit) {
    val haptics = com.opentune.ui.components.rememberHaptics()
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && !still) 0.94f else 1f,
        tween(if (still) 0 else 140), label = "heartPress",
    )
    // A small burst of hearts and sparks each time the button likes a song.
    val burst = remember { androidx.compose.animation.core.Animatable(1f) }
    var bursts by remember { mutableStateOf(0) }
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(bursts) {
        if (bursts == 0) return@LaunchedEffect
        launch { pop.snapTo(0.7f); pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 500f)) }
        burst.snapTo(0f)
        burst.animateTo(1f, tween(720, easing = androidx.compose.animation.core.LinearEasing))
    }
    val burstColors = listOf(Color(0xFFFF4D6D), Color(0xFFFF8FA3), Color(0xFFFFC2D1), MaterialTheme.colorScheme.primary)
    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(46.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(interactionSource = interactions, indication = com.opentune.ui.components.FocusRing(CircleShape)) {
                    haptics.pattern(if (liked) com.opentune.ui.components.Haptics.Pattern.OFF else com.opentune.ui.components.Haptics.Pattern.LIKE)
                    if (!liked && !still) bursts++
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (liked) "Remove from Liked" else "Like", Modifier.size(24.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                tint = if (liked) Color(0xFFFF4D6D) else LocalContentColor.current)
        }
        // Drawn past the button's edges, over whatever is around it.
        if (burst.value < 1f) LikeBurst(burst.value, bursts, burstColors, Modifier.requiredSize(150.dp))
    }
}

/**
 * The burst at [t] (0 to 1): small hearts and sparks flying out from the
 * button's middle on slightly different paths, growing then shrinking as
 * they fade. [seed] makes each burst a little different.
 */
@Composable
internal fun LikeBurst(t: Float, seed: Int, colors: List<Color>, modifier: Modifier) {
    val heart = remember {
        androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0.9f)
            cubicTo(-1.45f, -0.05f, -0.8f, -1.15f, 0f, -0.42f)
            cubicTo(0.8f, -1.15f, 1.45f, -0.05f, 0f, 0.9f)
            close()
        }
    }
    val pieces = remember(seed) {
        val r = kotlin.random.Random(seed * 7919 + 13)
        List(14) { i ->
            floatArrayOf(
                (i / 14f) * 2f * Math.PI.toFloat() + r.nextFloat() * 0.35f, // angle
                0.7f + r.nextFloat() * 0.6f, // how far
                0.6f + r.nextFloat() * 0.7f, // size
                if (i % 3 == 0) 0f else 1f, // spark or heart
                r.nextFloat() * 0.12f, // late start
            )
        }
    }
    androidx.compose.foundation.Canvas(modifier) {
        val c = center
        pieces.forEachIndexed { i, p ->
            val local = ((t - p[4]) / (1f - p[4])).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed
            val out = 1f - (1f - local) * (1f - local) * (1f - local)
            val dist = 58.dp.toPx() * p[1] * out
            // A little drift upward, like they float.
            val x = c.x + kotlin.math.cos(p[0]) * dist
            val y = c.y + kotlin.math.sin(p[0]) * dist - 10.dp.toPx() * out
            val grow = if (local < 0.3f) local / 0.3f else 1f - (local - 0.3f) / 0.7f
            val alpha = (1f - local * local).coerceIn(0f, 1f)
            val color = colors[i % colors.size].copy(alpha = alpha)
            if (p[3] == 0f) {
                drawCircle(color, 2.5.dp.toPx() * p[2] * (0.4f + grow), androidx.compose.ui.geometry.Offset(x, y))
            } else {
                val size = 6.dp.toPx() * p[2] * (0.3f + 0.9f * grow)
                withTransform({
                    translate(x, y)
                    rotate((p[0] * 57.3f + 90f) * 0.15f, pivot = androidx.compose.ui.geometry.Offset.Zero)
                    scale(size, size, pivot = androidx.compose.ui.geometry.Offset.Zero)
                }) { drawPath(heart, color) }
            }
        }
    }
}

/**
 * Narrows what it's on to its height, centred, when that's the smaller
 * side, so a cover sized by its width still fits a short space whole.
 */
private fun Modifier.fitSquare(): Modifier = layout { measurable, constraints ->
    val side = minOf(constraints.maxWidth, constraints.maxHeight)
    val placeable = measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, side), maxWidth = side))
    val width = constraints.maxWidth
    val height = placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) { placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2) }
}

/** Below this window height the docked player drops the lyric line, volume and output name. */
private val DOCKED_ROOMY_HEIGHT = 720.dp

/** How long the lyrics view goes untouched before the controls slide away. */
private const val LYRICS_FOCUS_MS = 5_000L

/** Mini player wired to the view model; the bottom bar places and sizes it. */
@Composable
fun MiniPlayerBar(vm: PlayerViewModel, onExpand: () -> Unit, modifier: Modifier = Modifier, inline: Boolean = false) {
    val song by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isBuffering by vm.isBuffering.collectAsState()
    val hasNext by vm.hasNext.collectAsState()
    val duration by vm.durationMs.collectAsState()
    val position = rememberPlaybackPosition(vm)
    val haptics = com.opentune.ui.components.rememberHaptics()
    val s = song ?: return
    MiniPlayer(
        song = s,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        hasNext = hasNext,
        progress = { if (duration > 0) position().toFloat() / duration else 0f },
        onTogglePlay = {
            haptics.pattern(if (isPlaying) com.opentune.ui.components.Haptics.Pattern.PAUSE else com.opentune.ui.components.Haptics.Pattern.PLAY)
            vm.togglePlayPause()
        },
        onNext = { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.NEXT); vm.skipNext() },
        onPrevious = { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.PREVIOUS); vm.skipPrevious() },
        onClick = onExpand,
        modifier = modifier,
        inline = inline,
    )
}

/** Shown where "Playing from" goes while in a room: whose room, and how many are in. */
@Composable
private fun TogetherPill(room: Together.Room, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                when {
                    room.hosting -> "Your room · ${room.members.size} listening"
                    room.holding -> "Paused for you · ${room.hostName ?: "room"}"
                    room.phase == Together.Phase.Live -> "With ${room.hostName ?: "the host"} · ${room.members.size} listening"
                    else -> "Listening together"
                },
            )
        },
        leadingIcon = { Icon(Icons.Rounded.Groups, null, Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/**
 * Brings what it's on into focus as it appears: from a soft blur and a
 * touch smaller to sharp, over half a second. Blur needs Android 12; before
 * that it only grows into place.
 */
@Composable
private fun Modifier.focusIn(animate: Boolean): Modifier {
    val v = remember { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) v.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
    val blur = (1f - v.value) * 2f
    return this
        .graphicsLayer { val s = 0.96f + 0.04f * v.value; scaleX = s; scaleY = s }
        .then(if (blur > 0.2f) Modifier.blur(blur.dp) else Modifier)
}


/**
 * The cover flying between the small one in the lyrics or queue header and the
 * full cover. [from] is where it left; it lands on the small cover when
 * [toSmall], on the full one otherwise, wherever that has been laid out.
 */
private class PaneFlight {
    var from by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
        private set
    var toSmall by mutableStateOf(false)
        private set
    var active by mutableStateOf(false)
        private set
    /** Bumped for each flight, so the runner starts again. */
    var runs by mutableStateOf(0)
        private set
    val t = androidx.compose.animation.core.Animatable(0f)

    fun start(from: androidx.compose.ui.geometry.Rect, toSmall: Boolean) {
        this.from = from
        this.toSmall = toSmall
        active = true
        com.opentune.ui.components.PlayerMorph.flying = true
        runs++
    }

    fun target(small: androidx.compose.ui.geometry.Rect?): androidx.compose.ui.geometry.Rect? =
        if (toSmall) small else com.opentune.ui.components.PlayerMorph.playerCover?.let(com.opentune.ui.components.PlayerMorph::scaled)

    fun end() {
        active = false
        from = null
        com.opentune.ui.components.PlayerMorph.flying = false
    }
}
