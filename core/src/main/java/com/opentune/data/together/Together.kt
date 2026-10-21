package com.opentune.data.together

import android.content.Context
import androidx.core.content.edit
import com.opentune.data.model.Song
import com.opentune.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Listening together: one phone hosts a room and the others play what it
 * plays, in step. Rooms run over public Nostr relays. Each message is
 * encrypted with a key that comes from the room code, signed with a key
 * made fresh for the session, and sent as an ephemeral event, which relays
 * pass to whoever is listening and don't keep.
 *
 * Every phone fetches its own audio; only "what, where, playing or not"
 * travels. Guests follow the host's state, correcting when they drift more
 * than a second, and can add songs to the host's queue. When the host lets
 * them, they can also play, pause, seek and skip for everyone.
 */
object Together {
    const val KIND = 21420
    val RELAYS = listOf(
        "wss://relay.primal.net",
        "wss://relay.snort.social",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://nostr.mom",
        "wss://relay.nostr.net",
        "wss://nostr-pub.wellorder.net",
        "wss://offchain.pub",
    )

    enum class Phase { Connecting, FindingHost, Waiting, Live, LostHost, Ended }

    data class ChatLine(
        val name: String,
        val text: String,
        val mine: Boolean = false,
        val system: Boolean = false,
        val at: Long = System.currentTimeMillis(),
        val fromId: String? = null,
        /** A voice note or a piece of talk, instead of text. */
        val voice: VoiceClip? = null,
    )

    /** A voice clip as it arrives: AAC frames with the codec's setup, see [VoiceFrames]. */
    class VoiceClip(val id: String, val cfg: ByteArray?, val frames: List<ByteArray>, val ms: Long, val complete: Boolean, val live: Boolean)

    data class Room(
        val code: RoomCode,
        val hosting: Boolean,
        val myName: String,
        val phase: Phase = Phase.Connecting,
        val relays: Int = 0,
        val members: List<Member> = emptyList(),
        val hostName: String? = null,
        val open: Boolean = false,
        val chat: List<ChatLine> = emptyList(),
        val track: Track? = null,
        val hostPrivate: Boolean = false,
        /** A guest who paused for themselves; the room plays on without them. */
        val holding: Boolean = false,
        val endedReason: String? = null,
        /** This phone's id in [members]. */
        val myId: String? = null,
        /** For the host: people who knocked, waiting to be let in or turned away. */
        val requests: List<Member> = emptyList(),
        /** For the host: knocking needs approval; off lets anyone with the code straight in. */
        val askToJoin: Boolean = true,
        /** Member ids of the people talking right now (others; this phone's own talk is [Together.speaking]). */
        val talking: List<String> = emptyList(),
    ) {
        val following get() = !hosting && phase == Phase.Live
    }

    /** A playback control pressed on a guest's phone. */
    sealed interface Control {
        data object PlayPause : Control
        data object Next : Control
        data object Previous : Control
        data class Seek(val positionMs: Long) : Control
    }

    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room.asStateFlow()

    /** Short messages for the UI to show, like "Only the host can skip". */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val main by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var player: RoomPlayer? = null
    private var session: RoomSession? = null
    private var prefs: android.content.SharedPreferences? = null

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("together", Context.MODE_PRIVATE)
        _picksPlayNow.value = prefs?.getBoolean("picks_play_now", false) ?: false
        _talkMuted.value = prefs?.getBoolean("talk_muted", false) ?: false
    }

    /** Whether people who knock wait for approval in rooms this phone hosts. */
    val askToJoin: Boolean
        get() = prefs?.getBoolean("ask_to_join", true) ?: true

    fun approve(id: String) = session?.approve(id)
    fun reject(id: String) = session?.reject(id)
    fun remove(id: String) = session?.remove(id)
    fun setAskToJoin(on: Boolean) {
        prefs?.edit { putBoolean("ask_to_join", on) }
        session?.setAskToJoin(on)
    }

    // ---- voice ----

    private val voicePlayer by lazy { appContext?.let { VoicePlayer(it).apply { onPlaying = { id -> _playingClip.value = id } } } }
    private val _playingClip = MutableStateFlow<String?>(null)
    /** The voice clip being heard now, by its chat id. */
    val playingClip: StateFlow<String?> = _playingClip.asStateFlow()

    private val _talkMuted = MutableStateFlow(false)
    /** Talk from others isn't played as it arrives (it still shows in the chat). */
    val talkMuted: StateFlow<Boolean> = _talkMuted.asStateFlow()

    fun setTalkMuted(on: Boolean) {
        _talkMuted.value = on
        prefs?.edit { putBoolean("talk_muted", on) }
        if (on) voicePlayer?.stop()
    }

    private val _recording = MutableStateFlow<Boolean?>(null)
    /** Recording now: true for talk, false for a voice note, null when not. */
    val recording: StateFlow<Boolean?> = _recording.asStateFlow()
    private var recorder: VoiceRecorder? = null

    /**
     * Starts recording: [live] talk, heard by everyone as it's said, or a
     * voice note sent when it's done. The caller has the microphone permission.
     */
    fun startVoice(live: Boolean): Boolean {
        val s = session ?: return false
        val context = appContext ?: return false
        if (recorder?.isRecording == true) return false
        val id = java.util.UUID.randomUUID().toString().take(12)
        var seq = 0
        val all = mutableListOf<ByteArray>()
        var cfg: ByteArray? = null
        val r = VoiceRecorder(context, if (live) 30_000 else 60_000) { pieceCfg, frames, ms, last ->
            main.launch {
                if (pieceCfg != null) cfg = pieceCfg
                // Talk is heard and gone; only a note keeps every frame, to show in the chat.
                if (!live) all += frames
                s.sendVoice(id, seq++, pieceCfg, frames, ms, last, live, all.toList(), cfg)
                if (last) { _recording.value = null; if (live) _speaking.value = false }
            }
        }
        if (!r.start()) { notice("Couldn't use the microphone"); return false }
        recorder = r
        _recording.value = live
        if (live) _speaking.value = true
        return true
    }

    fun stopVoice() {
        if (_inCall.value) return
        recorder?.stop()
        recorder = null
    }

    // ---- call: an open mic that sends only while you speak ----

    private val _inCall = MutableStateFlow(false)
    /** This phone is in the room's call: its mic is open and speech goes out as it's said. */
    val inCall: StateFlow<Boolean> = _inCall.asStateFlow()

    private val _callMuted = MutableStateFlow(false)
    val callMuted: StateFlow<Boolean> = _callMuted.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    /** This phone's own voice is going out right now (talk held, or speech in a call). */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /**
     * Joins the call. Each stretch of speech goes out as live talk, so
     * everyone with Hear talk on hears it as it's said; silence sends
     * nothing. The caller has the microphone permission.
     */
    fun startCall(): Boolean {
        val s = session ?: return false
        val context = appContext ?: return false
        if (recorder?.isRecording == true) return false
        var id = newClipId()
        var seq = 0
        _callMuted.value = false
        val r = VoiceRecorder(
            context, maxMs = 6L * 60 * 60 * 1000, gate = true, duck = false,
            onSpeaking = { on -> main.launch { _speaking.value = on } },
        ) { cfg, frames, ms, last ->
            val clip = id
            val n = seq++
            if (last) { id = newClipId(); seq = 0 }
            main.launch { s.sendVoice(clip, n, cfg, frames, ms, last, live = true, allFrames = emptyList(), allCfg = null) }
        }
        if (!r.start()) { notice("Couldn't use the microphone"); return false }
        recorder = r
        _inCall.value = true
        return true
    }

    fun setCallMuted(on: Boolean) {
        _callMuted.value = on
        recorder?.muted = on
    }

    fun endCall() {
        if (!_inCall.value) return
        _inCall.value = false
        _speaking.value = false
        recorder?.stop()
        recorder = null
    }

    private fun newClipId() = java.util.UUID.randomUUID().toString().take(12)

    /** Plays a clip from the chat, or stops it if it's the one playing. */
    fun playClip(clip: VoiceClip) {
        val cfg = clip.cfg ?: return
        voicePlayer?.play(clip.id, cfg, clip.frames)
    }

    private val _picksPlayNow = MutableStateFlow(false)
    /** As a guest, a song picked starts for everyone (when the host allows it) rather than joining the queue. */
    val picksPlayNow: StateFlow<Boolean> = _picksPlayNow.asStateFlow()

    fun setPicksPlayNow(on: Boolean) {
        _picksPlayNow.value = on
        prefs?.edit { putBoolean("picks_play_now", on) }
    }

    fun attach(connection: PlayerConnection) {
        player = ConnectionPlayer(connection)
    }

    /** Leaves any room and lets go of the player, when the screen that owns it goes away. */
    fun detach() {
        leave()
        player = null
    }

    /** The name last used in a room, to fill in next time. */
    var savedName: String
        get() = prefs?.getString("name", null).orEmpty()
        private set(value) { prefs?.edit { putString("name", value) } }

    fun create(name: String): RoomCode {
        leave()
        val code = RoomCode.generate()
        start(code, name, hosting = true)
        return code
    }

    fun join(code: RoomCode, name: String) {
        if (session?.code == code && _room.value?.phase != Phase.Ended) return
        leave()
        start(code, name, hosting = false)
    }

    private fun start(code: RoomCode, name: String, hosting: Boolean) {
        val clean = name.trim().take(30).ifBlank { "Someone" }
        savedName = clean
        _room.value = Room(code, hosting, clean)
        val p = player ?: return
        val filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(KIND))))
            put("#t", JsonArray(listOf(JsonPrimitive(code.tag))))
            put("since", System.currentTimeMillis() / 1000 - 10)
        }
        val talk = TalkSink { id, _, cfg, frames, done -> if (!_talkMuted.value) voicePlayer?.talk(id, cfg, frames, done) }
        session = RoomSession(code, clean, hosting, p, RelayPool(RELAYS, filter), _room, ::notice, main, askToJoin, talk).also { it.start() }
    }

    fun leave() {
        endCall()
        stopVoice()
        voicePlayer?.stop()
        session?.stop(saying = true)
        session = null
        _room.value = null
    }

    fun setOpen(open: Boolean) = session?.setOpen(open)

    fun say(text: String) = session?.say(text)

    /**
     * Adds [song] to the room's queue: straight in for the host, as a request
     * for a guest; or, for a guest who chose [picksPlayNow], plays it for everyone.
     */
    fun suggest(song: Song): Boolean = session?.suggest(song, now = _picksPlayNow.value) ?: false

    /**
     * For a guest following a room, sends [control] to the host instead of
     * acting on it here. True when the room took it, so the caller does
     * nothing locally.
     */
    fun intercept(control: Control): Boolean = session?.intercept(control) ?: false

    private fun notice(text: String) {
        _notices.tryEmit(text)
    }


}
