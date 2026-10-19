package com.opentune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Page(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    SEARCH("Search", Icons.Rounded.Search),
    LIKED("Liked", Icons.Rounded.Favorite),
    RECENT("Recent", Icons.Rounded.History),
    QUEUE("Up next", Icons.AutoMirrored.Rounded.QueueMusic),
}

private val Scheme = darkColorScheme(
    primary = Color(0xFFC8EE9B),
    onPrimary = Color(0xFF14200A),
    background = Color(0xFF101210),
    surface = Color(0xFF101210),
    surfaceContainer = Color(0xFF181B17),
    surfaceContainerHigh = Color(0xFF20241F),
    secondaryContainer = Color(0xFF2E3A24),
    onSecondaryContainer = Color(0xFFDDF5C2),
)

@Composable
fun DesktopApp() {
    var page by remember { mutableStateOf(Page.HOME) }
    // An album, playlist or artist opened from Home, over the page.
    var opened by remember { mutableStateOf<ShelfItem?>(null) }
    MaterialTheme(colorScheme = Scheme) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Spacer(Modifier.height(12.dp))
                        Page.entries.forEach { p ->
                            NavigationRailItem(selected = p == page, onClick = { page = p; opened = null }, icon = { Icon(p.icon, null) }, label = { Text(p.label) })
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val collection = opened
                        if (collection != null) CollectionPage(collection, onBack = { opened = null })
                        else when (page) {
                            Page.HOME -> HomePage(onOpen = { opened = it })
                            Page.SEARCH -> SearchPage()
                            Page.LIKED -> {
                                val liked by Library.liked.collectAsState()
                                SongPage("Liked songs", liked, "Songs you like show here.")
                            }
                            Page.RECENT -> {
                                val recent by Library.recent.collectAsState()
                                SongPage("Recent", recent, "Nothing played yet.")
                            }
                            Page.QUEUE -> {
                                val queue by Player.queue.collectAsState()
                                val index by Player.index.collectAsState()
                                SongPage("Up next", queue, "The queue is empty.", current = index, onPlay = { _, i -> Player.playAt(i) })
                            }
                        }
                    }
                }
                HorizontalDivider()
                NowPlayingBar()
            }
        }
    }
}

@Composable
private fun HomePage(onOpen: (ShelfItem) -> Unit) {
    var shelves by remember { mutableStateOf<List<Pages.Shelf>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() {
        failed = false
        scope.launch { runCatching { Pages.home() }.onSuccess { shelves = it }.onFailure { failed = true } }
    }
    LaunchedEffect(Unit) { load() }
    val loaded = shelves
    when {
        failed -> Message("Couldn't load Home. Check your connection.", action = "Try again" to ::load)
        loaded == null -> Loading()
        else -> LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Title("Home") }
            items(loaded) { shelf ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(shelf.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    val songs = remember(shelf) { shelf.items.mapNotNull(Pages::songOf) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(shelf.items) { item ->
                            Card(item) {
                                val song = Pages.songOf(item)
                                if (song != null) Player.playAll(songs, songs.indexOfFirst { it.videoId == song.videoId }) else onOpen(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPage() {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run() {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        searching = true
        failed = false
        scope.launch {
            runCatching { Pages.search(q) }.onSuccess { results = it }.onFailure { failed = true }
            searching = false
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Title("Search")
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text("Songs and artists") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        val found = results
        when {
            searching -> Loading()
            failed -> Message("Couldn't search. Check your connection.")
            found == null -> Unit
            found.isEmpty() -> Message("No songs found.")
            else -> SongList(found) { songs, i -> Player.playAll(songs, i) }
        }
    }
}

@Composable
private fun SongPage(
    title: String,
    songs: List<Song>,
    empty: String,
    current: Int = -1,
    onPlay: (List<Song>, Int) -> Unit = { s, i -> Player.playAll(s, i) },
) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Title(title)
        if (songs.isEmpty()) Message(empty) else SongList(songs, current, onPlay)
    }
}

@Composable
private fun SongList(songs: List<Song>, current: Int = -1, onPlay: (List<Song>, Int) -> Unit) {
    val liked by Library.liked.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
        itemsIndexed(songs, key = { i, s -> "$i:${s.videoId}" }) { i, song ->
            val isLiked = liked.any { it.videoId == song.videoId }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (i == current) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                    .clickable { onPlay(songs, i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(song.thumbnailUrl, 44)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                song.durationText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { Library.setLiked(song, !isLiked) }) {
                    Icon(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Unlike" else "Like")
                }
            }
        }
    }
}

@Composable
private fun Card(item: ShelfItem, onClick: () -> Unit) {
    Column(Modifier.width(160.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Cover(item.thumbnailUrl, 152, round = item.browseId?.startsWith("UC") == true)
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The songs of an album, playlist or artist, with a way back. */
@Composable
private fun CollectionPage(item: ShelfItem, onBack: () -> Unit) {
    var songs by remember(item) { mutableStateOf<List<Song>?>(null) }
    var failed by remember(item) { mutableStateOf(false) }
    LaunchedEffect(item) {
        runCatching { Pages.songsOf(item.browseId!!) }.onSuccess { songs = it }.onFailure { failed = true }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Cover(item.thumbnailUrl, 56, round = item.browseId?.startsWith("UC") == true)
            Column(Modifier.padding(start = 14.dp)) {
                Title(item.title)
                Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val found = songs
        when {
            failed -> Message("Couldn't open ${item.title}. Check your connection.")
            found == null -> Loading()
            found.isEmpty() -> Message("No songs here.")
            else -> SongList(found) { s, i -> Player.playAll(s, i) }
        }
    }
}

@Composable
private fun NowPlayingBar() {
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val playing by Player.playing.collectAsState()
    val loading by Player.loading.collectAsState()
    val error by Player.error.collectAsState()
    val volume by Player.volume.collectAsState()
    val liked by Library.liked.collectAsState()
    val song = queue.getOrNull(index)
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(song?.videoId) {
        while (true) {
            position = Player.positionMs()
            duration = Player.durationMs()
            delay(250)
        }
    }
    Row(
        Modifier.fillMaxWidth().height(84.dp).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (song != null) {
                Cover(song.thumbnailUrl, 56)
                Column(Modifier.padding(horizontal = 12.dp).weight(1f, fill = false)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(error ?: song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val isLiked = liked.any { it.videoId == song.videoId }
                IconButton(onClick = { Library.setLiked(song, !isLiked) }) {
                    Icon(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Unlike" else "Like")
                }
            } else {
                Text("Pick a song to play", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = Player::previous, enabled = song != null) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                FilledIconButton(onClick = Player::toggle, enabled = song != null, modifier = Modifier.size(44.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play")
                }
                IconButton(onClick = Player::next, enabled = song != null) { Icon(Icons.Rounded.SkipNext, "Next") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Time.format(position), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = dragging ?: if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { Player.seekTo((it * duration).toLong()) }; dragging = null },
                    enabled = duration > 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(24.dp),
                )
                Text(Time.format(duration), style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            var level by remember { mutableFloatStateOf(volume.toFloat()) }
            Slider(value = level, onValueChange = { level = it; Player.setVolume(it.toDouble()) }, modifier = Modifier.width(140.dp).padding(start = 8.dp))
        }
    }
}

@Composable
private fun Cover(url: String?, size: Int, round: Boolean = false) {
    val shape = if (round) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape((size / 8).coerceAtLeast(6).dp)
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun Message(text: String, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { (label, run) ->
            androidx.compose.material3.TextButton(onClick = run) { Text(label) }
        }
    }
}

/** Minutes and seconds, as the bar shows them. */
internal object Time {
    fun format(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
