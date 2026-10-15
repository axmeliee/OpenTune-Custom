package com.opentune.ui.together

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.together.Member
import com.opentune.data.together.RoomCode
import com.opentune.data.together.Together
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SectionHeader

/**
 * Listen together: start a room or join one by code, then see who's in,
 * what's playing, and chat. [code] fills in the join box, from an invite link.
 */
@Composable
fun TogetherScreen(contentPadding: PaddingValues, code: String?, onBack: () -> Unit) {
    val room by Together.room.collectAsState()
    val r = room
    var handled by rememberSaveable(code) { mutableStateOf(false) }
    var switchRoom by rememberSaveable(code) { mutableStateOf(false) }
    val invite = RoomCode.parse(code)
    val inviteName = Together.savedName.ifBlank { com.opentune.data.account.AccountStore.account.value?.name.orEmpty() }
    LaunchedEffect(code) {
        if (!handled && invite != null) {
            handled = true
            if (r != null && r.code != invite) switchRoom = true
        }
    }
    if (switchRoom && invite != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { switchRoom = false },
            title = { Text("Join the invited room?") },
            text = { Text("This leaves your current room.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                switchRoom = false
                Together.join(invite, inviteName.ifBlank { "Listener" })
            }) { Text("Join room") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { switchRoom = false }) { Text("Stay here") } },
        )
    }
    // The start page scrolls behind the dock and stops short of it; the room
    // view keeps its talk bar above it.
    if (r == null) StartRoom(code, onBack, contentPadding)
    else Box(Modifier.fillMaxSize().padding(contentPadding)) { RoomView(r, onBack) }
}

@Composable
internal fun StartRoom(code: String?, onBack: () -> Unit, contentPadding: PaddingValues = PaddingValues()) {
    val invite = remember(code) { RoomCode.parse(code) }
    var name by rememberSaveable {
        mutableStateOf(Together.savedName.ifBlank {
            com.opentune.data.account.AccountStore.account.value?.name.orEmpty()
        }.ifBlank { if (invite != null) "Listener" else "" })
    }
    var typed by rememberSaveable(code) { mutableStateOf(RoomCode.parse(code)?.pretty ?: "") }
    val parsed = RoomCode.parse(typed)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { PageHeader(if (invite != null) "You’re invited" else "Listen together", onBack = onBack) }
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Box(
                    Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Groups, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Spacer(Modifier.height(16.dp))
                Text(
                    if (invite != null) "Listen with your friends in OpenTune. Tap Join when you’re ready."
                    else "Start a room and friends hear what you play, in step, on their own phones. Or join a friend's room with their code.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    name, { name = it.take(30) },
                    label = { Text("Your name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                if (invite != null) {
                    Text("Room", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(invite.pretty, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    Button(
                        onClick = { Together.create(name) },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Start a room") }

                    Row(Modifier.padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f))
                        Text("or join one", Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(Modifier.weight(1f))
                    }
                    OutlinedTextField(
                        typed, { typed = RoomCode.parse(it)?.pretty ?: it.take(256) },
                        label = { Text("Room code") },
                        placeholder = { Text("K7QX-M2PA-9DTE") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { parsed?.let { if (name.isNotBlank()) Together.join(it, name) } }),
                        isError = typed.length >= 12 && parsed == null,
                        supportingText = { if (typed.length >= 12 && parsed == null) Text("That isn't a room code") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { parsed?.let { Together.join(it, name) } },
                    enabled = parsed != null && name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Join") }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Rooms run over public Nostr relays, which only pass messages along and don't keep them. " +
                            "If the host has approval enabled, you’ll wait for them to let you in. Music, chat and voice messages are encrypted. " +
                            "Each phone plays the music itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
internal fun RoomView(r: Together.Room, onBack: () -> Unit) {
    val context = LocalContext.current
    var message by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(r.chat.size) { if (r.chat.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }
    val share = {
        val text = "Listen with me on OpenTune. Room code: ${r.code.pretty}\n${r.code.link}"
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite to your room"))
    }
    val copy = {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("OpenTune room", r.code.pretty))
        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), state = list) {
            item { PageHeader(if (r.hosting) "Your room" else "${r.hostName ?: "Friend"}'s room", onBack = onBack) }
            item { CodeCard(r, onShare = share, onCopy = copy) }
            item { NowInRoom(r) }
            if (r.holding) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(18.dp)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Paused for you. The room keeps playing.", Modifier.weight(1f), color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Button(onClick = { Together.intercept(Together.Control.PlayPause) }) { Text("Catch up") }
                    }
                }
            }
            if (r.hosting) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Everyone can control playback", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (r.open) "Guests can play, pause, seek and skip" else "Guests can add songs; only you play, pause and skip",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(r.open, { Together.setOpen(it) })
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ask before letting people in", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (r.askToJoin) "People with the code wait until you let them in" else "Anyone with the code comes straight in",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(r.askToJoin, { Together.setAskToJoin(it) })
                    }
                }
                if (r.requests.isNotEmpty()) {
                    item { SectionHeader("Asking to join", subtitle = "${r.requests.size} waiting") }
                    items(r.requests.size, key = { "req:" + r.requests[it].id }) { i -> RequestRow(r.requests[i]) }
                }
            } else {
                item {
                    val playNow by Together.picksPlayNow.collectAsState()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Picking a song plays it now", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    !playNow -> "Off: songs you pick go into the room's queue"
                                    r.open -> "On: a song you pick starts for everyone"
                                    else -> "On, once ${r.hostName ?: "the host"} lets guests control playback. Until then songs go into the queue"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(playNow, { Together.setPicksPlayNow(it) })
                    }
                }
            }
            item { SectionHeader("Listening", subtitle = "${r.members.size} in the room") }
            item {
                val meSpeaking by Together.speaking.collectAsState()
                val speaking = r.talking.toSet() + listOfNotNull(r.myId.takeIf { meSpeaking })
                Members(r.members, r.myId, canRemove = r.hosting, speaking = speaking)
            }
            if (r.phase == Together.Phase.Live) item { TalkPanel() }
            item { SectionHeader("Chat") }
            if (r.chat.isEmpty()) {
                item { Text("Say hi.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(r.chat.size) { i -> ChatBubble(r.chat[i]) }
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = { Together.leave() }) { Text(if (r.hosting) "End room" else "Leave room") }
                }
            }
        }
        // Talk and the call sit here, outside the list, so holding Talk can't
        // scroll the page and nothing above moves while someone speaks.
        if (r.phase == Together.Phase.Live) TalkBar()
        if (r.phase != Together.Phase.Ended) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    message, { message = it.take(300) },
                    placeholder = { Text("Message the room") },
                    singleLine = true,
                    shape = RoundedCornerShape(26.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { Together.say(message); message = "" }),
                    modifier = Modifier.weight(1f),
                )
                if (message.isNotBlank()) {
                    IconButton(onClick = { Together.say(message); message = "" }) {
                        Icon(Icons.AutoMirrored.Rounded.Send, "Send")
                    }
                } else if (r.phase == Together.Phase.Live) {
                    HoldToRecord(live = false, Modifier.size(48.dp)) { recording ->
                        Icon(
                            Icons.Rounded.Mic, "Hold to record a voice note",
                            Modifier.size(26.dp),
                            tint = if (recording) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeCard(r: Together.Room, onShare: () -> Unit, onCopy: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ROOM CODE", style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(r.code.pretty, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Status(r)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onShare) {
                Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Invite")
            }
            OutlinedButton(onClick = onCopy) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy code")
            }
        }
    }
}

@Composable
private fun Status(r: Together.Room) {
    val (text, busy) = when (r.phase) {
        Together.Phase.Connecting -> "Connecting…" to true
        Together.Phase.FindingHost -> "Looking for the host…" to true
        Together.Phase.Waiting -> "Waiting for ${r.hostName ?: "the host"} to let you in…" to true
        Together.Phase.Live -> (if (r.hosting) "Live · ${r.members.size - 1} joined" else "In step with ${r.hostName ?: "the host"}") to false
        Together.Phase.LostHost -> "Lost touch with the host. Still trying…" to true
        Together.Phase.Ended -> (r.endedReason ?: "The room has ended") to false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        else Box(Modifier.size(8.dp).background(if (r.phase == Together.Phase.Live) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.relays > 0) {
            Text(" · ${r.relays} relays", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NowInRoom(r: Together.Room) {
    val t = r.track
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(t?.thumb, Modifier.size(56.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Playing in the room", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when {
                    t != null -> t.title
                    r.hostPrivate -> "A song only on the host's phone"
                    else -> "Nothing yet"
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (t != null) Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    AnimatedVisibility(r.hosting && t == null && !r.hostPrivate) {
        Text(
            "Play anything and the room will hear it.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!r.hosting) {
        Text(
            "Add songs from any song's menu with Add to queue: they go to the room.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Members(members: List<Member>, myId: String?, canRemove: Boolean, speaking: Set<String> = emptySet()) {
    var removing by remember { mutableStateOf<Member?>(null) }
    // Room around each avatar for its speaking ring.
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(members, key = { it.id }) { m ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 76.dp).then(
                    if (canRemove && !m.host) Modifier.clickable { removing = m } else Modifier,
                ),
            ) {
                Avatar(m.name, 56.dp, speaking = m.id in speaking)
                Spacer(Modifier.height(8.dp))
                Text(m.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                when {
                    m.host && m.id == myId -> Text("Host · You", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    m.host -> Text("Host", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    m.id == myId -> Text("You", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    removing?.let { m ->
        com.opentune.ui.components.FloatingDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${m.name}?") },
            text = { Text("They leave the room, and the room moves to a new key, so their phone can't follow along or read the chat any more.") },
            confirmButton = { com.opentune.ui.components.SheetButton("Remove", tone = com.opentune.ui.components.SheetTone.Danger, onClick = { Together.remove(m.id); removing = null }) },
            dismissButton = { com.opentune.ui.components.SheetButton("Cancel", onClick = { removing = null }, closes = true) },
        )
    }
}

/**
 * A member's initial in their colour. While they're [speaking], a green
 * ring sits around it, and a second one keeps spreading out from it and
 * fading, like sound.
 */
@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp, speaking: Boolean = false) {
    val shown by animateFloatAsState(if (speaking) 1f else 0f, tween(220), label = "speaking")
    val wave = rememberInfiniteTransition(label = "speakingWave")
    val spread by wave.animateFloat(0f, 1f, infiniteRepeatable(tween(1_100), RepeatMode.Restart), label = "spread")
    Box(
        Modifier.size(size)
            .drawBehind {
                if (shown <= 0f) return@drawBehind
                val ring = SPEAKING
                val base = this.size.minDimension / 2 + 4.dp.toPx()
                drawCircle(ring.copy(alpha = shown), radius = base, style = Stroke(3.dp.toPx()))
                drawCircle(ring.copy(alpha = shown * (1f - spread) * 0.7f), radius = base + spread * 9.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
            .background(avatarColor(name), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(name.take(1).uppercase(), fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B0B10)) }
}

private val SPEAKING = Color(0xFF4CD964)

/** Someone who knocked: let them in or turn them away. */
@Composable
private fun RequestRow(m: Member) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(m.name, 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedButton(onClick = { Together.reject(m.id) }) { Text("Reject") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { Together.approve(m.id) }) { Text("Let in") }
    }
}

/** What talk and the call are, and whether you hear them; the buttons are in [TalkBar]. */
@Composable
private fun TalkPanel() {
    val muted by Together.talkMuted.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Talk and call", style = MaterialTheme.typography.titleMedium)
            Text(
                "Hold Talk to say something, or join the call to talk freely. Nothing is saved. In a call, headphones keep the room from hearing itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(!muted, { Together.setTalkMuted(!it) })
            Text("Hear talk", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Hold to talk, or the call: join it and your speech goes out as you say
 * it, with mute and hang up. Fixed height, so it never moves the page.
 */
@Composable
private fun TalkBar() {
    val context = LocalContext.current
    val inCall by Together.inCall.collectAsState()
    val callMuted by Together.callMuted.collectAsState()
    val speaking by Together.speaking.collectAsState()
    val recording by Together.recording.collectAsState()
    val haptics = com.opentune.ui.components.rememberHaptics()
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && Together.startCall()) haptics.press()
    }
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inCall) {
            Row(
                Modifier.weight(1f).height(52.dp).background(scheme.surfaceContainerHigh, RoundedCornerShape(26.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(if (speaking && !callMuted) SPEAKING else scheme.outline, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        callMuted -> "In the call · muted"
                        speaking -> "In the call · you're on"
                        else -> "In the call · just talk"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalIconButton(onClick = { haptics.tick(); Together.setCallMuted(!callMuted) }, Modifier.size(52.dp)) {
                Icon(if (callMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (callMuted) "Unmute" else "Mute")
            }
            FilledIconButton(
                onClick = { haptics.tick(); Together.endCall() },
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.error, contentColor = scheme.onError),
            ) { Icon(Icons.Rounded.CallEnd, "Leave the call") }
        } else {
            HoldToRecord(
                live = true,
                Modifier.weight(1f).height(52.dp).background(if (recording == true) scheme.error else scheme.primary, RoundedCornerShape(26.dp)),
            ) { talking ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Mic, null, tint = if (talking) scheme.onError else scheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (talking) "Talking… let go to stop" else "Hold to talk",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (talking) scheme.onError else scheme.onPrimary,
                        maxLines = 1,
                    )
                }
            }
            FilledTonalIconButton(
                onClick = {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) ask.launch(android.Manifest.permission.RECORD_AUDIO)
                    else if (Together.startCall()) haptics.press()
                },
                modifier = Modifier.size(52.dp),
            ) { Icon(Icons.Rounded.Call, "Join the call") }
        }
    }
}

/**
 * Records while held: [live] talk, or a voice note sent when let go. Asks
 * for the microphone the first time.
 */
@Composable
private fun HoldToRecord(live: Boolean, modifier: Modifier, content: @Composable (recording: Boolean) -> Unit) {
    val context = LocalContext.current
    val recording by Together.recording.collectAsState()
    val haptics = com.opentune.ui.components.rememberHaptics()
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Toast.makeText(context, "Now hold to record", Toast.LENGTH_SHORT).show()
    }
    Box(
        modifier.pointerInput(live) {
            detectTapGestures(onPress = {
                // Only a real hold starts it: a brush, or a touch that turns into a
                // scroll, lets go (or is taken by the scroll) before this runs out.
                val letGo = kotlinx.coroutines.withTimeoutOrNull(if (live) TALK_HOLD_MS else NOTE_HOLD_MS) { tryAwaitRelease() }
                if (letGo != null) {
                    if (letGo) Toast.makeText(context, if (live) "Hold to talk" else "Hold to record a voice note", Toast.LENGTH_SHORT).show()
                    return@detectTapGestures
                }
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) { ask.launch(android.Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                if (!Together.startVoice(live)) return@detectTapGestures
                haptics.press()
                tryAwaitRelease()
                Together.stopVoice()
                haptics.tick()
            })
        },
        contentAlignment = Alignment.Center,
    ) { content(recording == live) }
}

/** How long the Talk button, and the voice note mic, have to be held before they record. */
private const val TALK_HOLD_MS = 450L
private const val NOTE_HOLD_MS = 300L

private val AVATARS = listOf(0xFFFF8A65, 0xFF4FC3F7, 0xFF9CCC65, 0xFFFFD54F, 0xFFBA68C8, 0xFF4DB6AC, 0xFFF06292, 0xFF7986CB)
private fun avatarColor(name: String) = Color(AVATARS[Math.floorMod(name.hashCode(), AVATARS.size)])

@Composable
private fun ChatBubble(line: Together.ChatLine) {
    if (line.system) {
        Text(
            line.text,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        return
    }
    val time = remember(line.at) { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(line.at)) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = if (line.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!line.mine) { Avatar(line.name, 28.dp); Spacer(Modifier.width(8.dp)) }
        Column(
            Modifier.widthIn(max = 290.dp)
                .background(
                    if (line.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(20.dp),
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            if (!line.mine) Text(line.name, style = MaterialTheme.typography.labelMedium, color = avatarColor(line.name))
            val clip = line.voice
            if (clip != null) VoiceBubble(clip) else Text(line.text, style = MaterialTheme.typography.bodyLarge)
            Text(
                time,
                Modifier.align(Alignment.End),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

/** A voice note or a piece of talk: play or stop, and how long it is. */
@Composable
private fun VoiceBubble(clip: Together.VoiceClip) {
    val playing by Together.playingClip.collectAsState()
    val isPlaying = playing == clip.id
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Together.playClip(clip) }, enabled = clip.cfg != null && clip.frames.isNotEmpty()) {
            Icon(if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (isPlaying) "Stop" else "Play")
        }
        Column {
            Text(
                when {
                    clip.live && !clip.complete -> "Talking…"
                    clip.live -> "Talk"
                    else -> "Voice note"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            val sec = clip.ms / 1000
            Text("%d:%02d".format(sec / 60, sec % 60), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
