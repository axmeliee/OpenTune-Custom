package com.opentune.data.together

import com.opentune.data.model.Song
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A host and a guest session run against each other through an in-memory
 * relay, each with a pretend player, in real time. This is the room's
 * behaviour end to end, minus the network and ExoPlayer.
 */
class RoomSessionTest {
    private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)

    @After fun tearDown() {
        scope.cancel()
        thread.close()
    }

    private fun song(id: String, title: String) = Song(id, title, "Artist", null, "3:30")
    private val a = song("aaaaaaaaaaa", "Song A")
    private val b = song("bbbbbbbbbbb", "Song B")
    private val c = song("ccccccccccc", "Song C")

    /** Everyone hears everything, the way a relay subscription on one tag does. */
    private class Hub {
        val flow = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256)
        fun transport() = object : RoomTransport {
            override val events: SharedFlow<NostrEvent> = flow
            override val connected: StateFlow<Int> = MutableStateFlow(1)
            override fun start() = Unit
            override fun publish(event: NostrEvent) = flow.tryEmit(event)
            override fun close() = Unit
        }
    }

    /** A player whose position runs on the wall clock while it plays. */
    private class FakePlayer : RoomPlayer {
        private val _queue = MutableStateFlow<List<Song>>(emptyList())
        private val _index = MutableStateFlow(0)
        private val _song = MutableStateFlow<Song?>(null)
        private val _upNext = MutableStateFlow<List<Int>>(emptyList())
        private val _pwr = MutableStateFlow(false)
        override val connected = MutableStateFlow(true).asStateFlow()
        override val currentSong = _song.asStateFlow()
        override val queue = _queue.asStateFlow()
        override val upNext = _upNext.asStateFlow()
        override val currentIndex = _index.asStateFlow()
        override val playWhenReady = _pwr.asStateFlow()
        override val isBuffering = MutableStateFlow(false).asStateFlow()
        private val _seeks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override val seeks: SharedFlow<Unit> = _seeks
        private var base = 0L
        private var baseAt = 0L
        var skips = 0
        var seekCount = 0
        var rate = 1f
            private set

        override fun currentPositionMs() = if (_pwr.value) base + ((System.currentTimeMillis() - baseAt) * rate).toLong() else base
        private fun rebase(pos: Long) { base = pos; baseAt = System.currentTimeMillis() }
        override fun setRate(rate: Float) { rebase(currentPositionMs()); this.rate = rate }
        private fun refresh() {
            _song.value = _queue.value.getOrNull(_index.value)
            _upNext.value = ((_index.value + 1) until _queue.value.size).toList()
        }
        override fun play() { rebase(currentPositionMs()); _pwr.value = true }
        override fun pause() { rebase(currentPositionMs()); _pwr.value = false }
        override fun seekTo(positionMs: Long) { seekCount++; rebase(positionMs); _seeks.tryEmit(Unit) }
        override fun seekToItem(index: Int, positionMs: Long) { _index.value = index; rebase(positionMs); refresh() }
        override fun playAll(songs: List<Song>, startIndex: Int, shuffle: Boolean, source: String?, startPositionMs: Long) {
            _queue.value = songs; _index.value = startIndex; rebase(startPositionMs); _pwr.value = true; refresh()
        }
        override fun replaceUpcoming(songs: List<Song>) { _queue.value = _queue.value.take(_index.value + 1) + songs; refresh() }
        override fun addToQueue(song: Song) { _queue.value = _queue.value + song; refresh() }
        override fun playNow(song: Song) {
            _queue.value = _queue.value.take(_index.value + 1) + song + _queue.value.drop(_index.value + 1)
            seekToItem(_index.value + 1, 0)
        }
        override fun skipNext() { skips++; if (_index.value + 1 < _queue.value.size) seekToItem(_index.value + 1, 0) }
        override fun skipPrevious() = seekTo(0)
    }

    private suspend fun <T> onThread(f: () -> T): T = withContext(thread) { f() }

    private suspend fun waitFor(what: String, timeoutMs: Long = 8_000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (onThread(check)) return
            delay(50)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test fun aGuestFollowsTheHost() = runBlocking {
        val hub = Hub()
        val code = RoomCode.generate()
        val hostPlayer = FakePlayer()
        val guestPlayer = FakePlayer()
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(code, true, "Asha"))
        val guestState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Ravi"))
        val notices = mutableListOf<String>()
        lateinit var host: RoomSession
        lateinit var guest: RoomSession
        onThread {
            hostPlayer.playAll(listOf(a, b), 0, false, null, 30_000)
            host = RoomSession(code, "Asha", true, hostPlayer, hub.transport(), hostState, {}, scope, askToJoin = false).also { it.start() }
            guest = RoomSession(code, "Ravi", false, guestPlayer, hub.transport(), guestState, { notices += it }, scope).also { it.start() }
        }

        // Joining: the guest plays the host's song from where the host is.
        waitFor("guest playing song A in step") {
            guestPlayer.currentSong.value?.videoId == a.videoId && guestPlayer.playWhenReady.value &&
                abs(guestPlayer.currentPositionMs() - hostPlayer.currentPositionMs()) < DRIFT_MS
        }
        waitFor("both see two people") { hostState.value!!.members.size == 2 && guestState.value!!.members.size == 2 }
        assertEquals(Together.Phase.Live, guestState.value!!.phase)
        assertEquals("Asha", guestState.value!!.hostName)
        assertEquals(listOf(b.videoId), onThread { guestPlayer.queue.value.drop(1).map { it.videoId } })

        // Pause, then seek and play again.
        onThread { hostPlayer.pause() }
        waitFor("guest paused") { !guestPlayer.playWhenReady.value }
        onThread { hostPlayer.seekTo(100_000); hostPlayer.play() }
        waitFor("guest at the new spot") {
            guestPlayer.playWhenReady.value && abs(guestPlayer.currentPositionMs() - hostPlayer.currentPositionMs()) < DRIFT_MS &&
                guestPlayer.currentPositionMs() > 99_000
        }

        // Next song.
        onThread { hostPlayer.skipNext() }
        waitFor("guest on song B") { guestPlayer.currentSong.value?.videoId == b.videoId }

        // A guest's pick goes into the host's queue, and chat goes both ways.
        onThread { assertTrue(guest.suggest(c)) }
        waitFor("song C queued on the host") { hostPlayer.queue.value.any { it.videoId == c.videoId } }
        waitFor("song C after B on the guest too") { guestPlayer.queue.value.any { it.videoId == c.videoId } }
        onThread { guest.say("hi from Ravi") }
        waitFor("host sees the message") { hostState.value!!.chat.any { it.name == "Ravi" && it.text == "hi from Ravi" } }
        onThread { host.say("hello Ravi") }
        waitFor("guest sees the reply") { guestState.value!!.chat.any { it.name == "Asha" && it.text == "hello Ravi" } }

        // Without control, a guest can't skip for everyone, and pausing is just for them.
        onThread { assertTrue(guest.intercept(Together.Control.Next)) }
        delay(800)
        assertEquals(1, onThread { hostPlayer.skips })
        assertTrue(notices.any { it.startsWith("Only Asha") })
        onThread { guest.intercept(Together.Control.PlayPause) }
        waitFor("guest holding") { guestState.value!!.holding && !guestPlayer.playWhenReady.value }
        delay(1_500)
        assertFalse("the room shouldn't restart a held guest", onThread { guestPlayer.playWhenReady.value })
        assertTrue(onThread { hostPlayer.playWhenReady.value })
        onThread { guest.intercept(Together.Control.PlayPause) }
        waitFor("guest caught up") { guestPlayer.playWhenReady.value && abs(guestPlayer.currentPositionMs() - hostPlayer.currentPositionMs()) < DRIFT_MS }

        // With control, a guest's skip happens for everyone.
        onThread { host.setOpen(true) }
        waitFor("guest knows it may control") { guestState.value!!.open }
        onThread { guest.intercept(Together.Control.Next) }
        waitFor("host skipped") { hostPlayer.currentSong.value?.videoId == c.videoId }
        waitFor("guest followed") { guestPlayer.currentSong.value?.videoId == c.videoId }

        // Ending the room tells the guest.
        onThread { host.stop(saying = true) }
        waitFor("guest sees the end") { guestState.value!!.phase == Together.Phase.Ended }
        assertTrue(guestState.value!!.endedReason!!.contains("Asha"))
    }

    /** Two phones in one room, the guest already in step. */
    private suspend fun joined(): Triple<FakePlayer, FakePlayer, RoomSession> {
        val hub = Hub()
        val code = RoomCode.generate()
        val hostPlayer = FakePlayer()
        val guestPlayer = FakePlayer()
        lateinit var host: RoomSession
        lateinit var guest: RoomSession
        onThread {
            hostPlayer.playAll(listOf(a, b, c), 0, false, null, 30_000)
            host = RoomSession(code, "Asha", true, hostPlayer, hub.transport(), MutableStateFlow(Together.Room(code, true, "Asha")), {}, scope, askToJoin = false).also { it.start() }
            guest = RoomSession(code, "Ravi", false, guestPlayer, hub.transport(), MutableStateFlow(Together.Room(code, false, "Ravi")), {}, scope).also { it.start() }
        }
        waitFor("in step") {
            guestPlayer.currentSong.value?.videoId == a.videoId && abs(guestPlayer.currentPositionMs() - hostPlayer.currentPositionMs()) < IN_STEP_MS * 2
        }
        onThread { host.setOpen(true) }
        return Triple(hostPlayer, guestPlayer, guest)
    }

    @Test fun aSmallDriftClosesWithoutASeek() = runBlocking {
        val (hostPlayer, guestPlayer, _) = joined()
        // Knock the guest 250 ms behind, as a slow phone or a hiccup would.
        val seeksBefore = onThread {
            guestPlayer.seekTo(guestPlayer.currentPositionMs() - 250)
            guestPlayer.seekCount
        }
        waitFor("drift closed", 10_000) { abs(guestPlayer.currentPositionMs() - hostPlayer.currentPositionMs()) <= IN_STEP_MS }
        assertEquals("closed by speed, not by seeking", seeksBefore, onThread { guestPlayer.seekCount })
        waitFor("back to normal speed") { guestPlayer.rate == 1f }
    }

    @Test fun aGuestsButtonsActAtOnce() = runBlocking {
        val (hostPlayer, guestPlayer, guest) = joined()
        delay(500) // the guest learns it may control
        onThread {
            assertTrue(guest.intercept(Together.Control.PlayPause))
            // Paused here straight away, before any message has gone anywhere.
            assertFalse(guestPlayer.playWhenReady.value)
        }
        waitFor("host paused too") { !hostPlayer.playWhenReady.value }
        delay(600)
        assertFalse("an older state mustn't start it again", onThread { guestPlayer.playWhenReady.value })
        onThread {
            guest.intercept(Together.Control.Next)
            assertEquals(b.videoId, guestPlayer.currentSong.value?.videoId)
        }
        waitFor("host on song B") { hostPlayer.currentSong.value?.videoId == b.videoId }
    }

    @Test fun aGuestLeavingIsNoticed() = runBlocking {
        val hub = Hub()
        val code = RoomCode.generate()
        val hostPlayer = FakePlayer()
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(code, true, "Asha"))
        val guestState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Ravi"))
        lateinit var guest: RoomSession
        onThread {
            hostPlayer.playAll(listOf(a), 0, false, null, 0)
            RoomSession(code, "Asha", true, hostPlayer, hub.transport(), hostState, {}, scope, askToJoin = false).start()
            guest = RoomSession(code, "Ravi", false, FakePlayer(), hub.transport(), guestState, {}, scope).also { it.start() }
        }
        waitFor("joined") { hostState.value!!.members.size == 2 }
        onThread { guest.stop(saying = true) }
        waitFor("left") { hostState.value!!.members.size == 1 && hostState.value!!.chat.any { it.text == "Ravi left" } }
    }

    @Test fun anotherRoomsMessagesAreIgnored() = runBlocking {
        val hub = Hub()
        val mine = RoomCode.generate()
        val other = RoomCode.generate()
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(mine, true, "Asha"))
        val strangerState = MutableStateFlow<Together.Room?>(Together.Room(other, false, "Mallory"))
        onThread {
            RoomSession(mine, "Asha", true, FakePlayer(), hub.transport(), hostState, {}, scope).start()
            RoomSession(other, "Mallory", false, FakePlayer(), hub.transport(), strangerState, {}, scope).start()
        }
        delay(3_000)
        assertEquals(1, hostState.value!!.members.size)
        assertEquals(Together.Phase.FindingHost, strangerState.value!!.phase)
    }

    /** The host's states can reach a guest before the host has heard its hello; the guest still ends up listed. */
    @Test fun aGuestWhoseHelloIsLostStillJoinsTheList() = runBlocking {
        val hub = Hub()
        val code = RoomCode.generate()
        val hostPlayer = FakePlayer()
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(code, true, "Asha"))
        val guestState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Ravi"))
        // The guest's first hello goes nowhere.
        var dropped = false
        val lossy = object : RoomTransport by hub.transport() {
            override fun publish(event: NostrEvent): Boolean {
                if (!dropped) { dropped = true; return true }
                return hub.flow.tryEmit(event)
            }
        }
        onThread {
            hostPlayer.playAll(listOf(a), 0, false, null, 0)
            RoomSession(code, "Asha", true, hostPlayer, hub.transport(), hostState, {}, scope, askToJoin = false).start()
            RoomSession(code, "Ravi", false, FakePlayer(), lossy, guestState, {}, scope).start()
        }
        // On the guest's own phone it's listed as soon as it hears the host.
        waitFor("guest sees itself") { guestState.value!!.phase == Together.Phase.Live && guestState.value!!.members.any { it.id == guestState.value!!.myId } }
        // And the host hears a later hello well before the 20 s check-in.
        waitFor("host lists the guest", 6_000) { hostState.value!!.members.size == 2 }
        waitFor("guest sees both") { guestState.value!!.members.size == 2 }
    }

    /**
     * The host stops hearing a guest for a while (its messages lost on the
     * way) and counts it gone. Once the guest gets through again it's back
     * in the host's list on its own, without knocking and waiting again.
     */
    @Test fun aGuestTheHostStoppedHearingComesBack() = runBlocking {
        val hub = Hub()
        val code = RoomCode.generate()
        val hostPlayer = FakePlayer()
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(code, true, "Asha"))
        val guestState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Ravi"))
        val muted = java.util.concurrent.atomic.AtomicBoolean(false)
        val lossy = object : RoomTransport by hub.transport() {
            override fun publish(event: NostrEvent): Boolean = if (muted.get()) true else hub.flow.tryEmit(event)
        }
        lateinit var host: RoomSession
        onThread {
            hostPlayer.playAll(listOf(a), 0, false, null, 0)
            host = RoomSession(code, "Asha", true, hostPlayer, hub.transport(), hostState, {}, scope, askToJoin = true, memberTimeoutMs = 1_500).also { it.start() }
            RoomSession(code, "Ravi", false, FakePlayer(), lossy, guestState, {}, scope, hereMs = 300).start()
        }
        waitFor("knocked") { hostState.value!!.requests.size == 1 }
        onThread { host.approve(hostState.value!!.requests.first().id) }
        waitFor("in") { hostState.value!!.members.size == 2 && guestState.value!!.phase == Together.Phase.Live }
        // Check-ins every 300 ms keep the guest in, well past the 1.5 s timeout.
        delay(2_500)
        assertEquals(2, hostState.value!!.members.size)

        muted.set(true)
        waitFor("dropped") { hostState.value!!.members.size == 1 && hostState.value!!.chat.any { it.text == "Ravi left" } }
        muted.set(false)
        waitFor("back") { hostState.value!!.members.size == 2 && hostState.value!!.chat.any { it.text == "Ravi is back" } }
        assertTrue("no second approval", hostState.value!!.requests.isEmpty())
        waitFor("guest listed by the host again") { guestState.value!!.members.size == 2 }
    }

    @Test fun aGuestsPickPlaysNowWhenAllowed() = runBlocking {
        val (hostPlayer, guestPlayer, guest) = joined()
        delay(500) // the guest learns it may control
        onThread { assertTrue(guest.suggest(c, now = true)) }
        waitFor("host playing the pick") { hostPlayer.currentSong.value?.videoId == c.videoId }
        waitFor("guest followed") { guestPlayer.currentSong.value?.videoId == c.videoId }
        // What was next before is still after it.
        assertTrue(onThread { hostPlayer.queue.value.any { it.videoId == b.videoId } })
    }

    @Test fun theHostLetsAGuestInOrTurnsThemAway() = runBlocking {
        val hub = Hub()
        val code = RoomCode.generate()
        val hostPlayer = FakePlayer()
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(code, true, "Asha"))
        val raviState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Ravi"))
        val malState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Mal"))
        val raviPlayer = FakePlayer()
        val malPlayer = FakePlayer()
        lateinit var host: RoomSession
        onThread {
            hostPlayer.playAll(listOf(a, b), 0, false, null, 10_000)
            host = RoomSession(code, "Asha", true, hostPlayer, hub.transport(), hostState, {}, scope).also { it.start() }
            RoomSession(code, "Ravi", false, raviPlayer, hub.transport(), raviState, {}, scope).start()
            RoomSession(code, "Mal", false, malPlayer, hub.transport(), malState, {}, scope).start()
        }
        // Both knock and wait; neither plays along yet.
        waitFor("both asking") { hostState.value!!.requests.map { it.name }.toSet() == setOf("Ravi", "Mal") }
        waitFor("both waiting") { raviState.value!!.phase == Together.Phase.Waiting && malState.value!!.phase == Together.Phase.Waiting }
        assertEquals("Asha", raviState.value!!.hostName)
        assertTrue(onThread { raviPlayer.currentSong.value == null && malPlayer.currentSong.value == null })

        val ravi = hostState.value!!.requests.first { it.name == "Ravi" }.id
        val mal = hostState.value!!.requests.first { it.name == "Mal" }.id
        onThread { host.approve(ravi); host.reject(mal) }
        waitFor("Ravi in and playing") { raviState.value!!.phase == Together.Phase.Live && raviPlayer.currentSong.value?.videoId == a.videoId }
        waitFor("Mal turned away") { malState.value!!.phase == Together.Phase.Ended }
        assertTrue(malState.value!!.endedReason!!.contains("didn't let you in"))
        assertEquals(2, hostState.value!!.members.size)
        assertTrue(hostState.value!!.requests.isEmpty())
        delay(1_000)
        assertTrue("turned away, nothing plays", onThread { malPlayer.currentSong.value == null })
    }

    /** Someone with only the code sees knocking in the lobby, never what's playing or what's said. */
    @Test fun theCodeAloneOpensOnlyTheLobby() = runBlocking {
        val hub = Hub()
        val code = RoomCode.generate()
        val seen = java.util.Collections.synchronizedList(mutableListOf<Msg>())
        val spy = scope.launch { hub.flow.collect { e -> code.open(e.content)?.let(Msg::decode)?.let(seen::add) } }
        val hostPlayer = FakePlayer()
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(code, true, "Asha"))
        val guestState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Ravi"))
        lateinit var host: RoomSession
        lateinit var guest: RoomSession
        onThread {
            hostPlayer.playAll(listOf(a), 0, false, null, 0)
            host = RoomSession(code, "Asha", true, hostPlayer, hub.transport(), hostState, {}, scope).also { it.start() }
            guest = RoomSession(code, "Ravi", false, FakePlayer(), hub.transport(), guestState, {}, scope).also { it.start() }
        }
        waitFor("asking") { hostState.value!!.requests.isNotEmpty() }
        onThread { host.approve(hostState.value!!.requests.first().id) }
        waitFor("in") { guestState.value!!.phase == Together.Phase.Live }
        onThread { guest.say("only for the room") }
        waitFor("host got it") { hostState.value!!.chat.any { it.text == "only for the room" } }
        delay(2_500) // a heartbeat or two of state
        spy.cancel()
        assertTrue(seen.isNotEmpty())
        assertTrue("only lobby messages open with the code: ${seen.map { it::class.simpleName }}", seen.all(Msg::inLobby))
        assertFalse(seen.any { it is Msg.State || it is Msg.Chat })
    }

    @Test fun aRemovedGuestIsLockedOutAndTheRestPlayOn() = runBlocking {
        val hub = Hub()
        val code = RoomCode.generate()
        val hostPlayer = FakePlayer()
        val raviPlayer = FakePlayer()
        val malPlayer = FakePlayer()
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(code, true, "Asha"))
        val raviState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Ravi"))
        val malState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Mal"))
        lateinit var host: RoomSession
        onThread {
            hostPlayer.playAll(listOf(a, b, c), 0, false, null, 0)
            host = RoomSession(code, "Asha", true, hostPlayer, hub.transport(), hostState, {}, scope, askToJoin = false).also { it.start() }
            RoomSession(code, "Ravi", false, raviPlayer, hub.transport(), raviState, {}, scope).start()
            RoomSession(code, "Mal", false, malPlayer, hub.transport(), malState, {}, scope).start()
        }
        waitFor("three in the room") { hostState.value!!.members.size == 3 && raviState.value!!.phase == Together.Phase.Live && malState.value!!.phase == Together.Phase.Live }
        val mal = hostState.value!!.members.first { it.name == "Mal" }.id
        onThread { host.remove(mal) }
        waitFor("Mal put out") { malState.value!!.phase == Together.Phase.Ended }
        assertTrue(malState.value!!.endedReason!!.contains("removed you"))
        // Under the new key, Ravi still follows the host.
        onThread { hostPlayer.skipNext() }
        waitFor("Ravi on song B") { raviPlayer.currentSong.value?.videoId == b.videoId }
        assertEquals(listOf("Asha", "Ravi"), hostState.value!!.members.map { it.name })
    }

    @Test fun voiceNotesAndTalkReachTheRoom() = runBlocking {
        val hub = Hub()
        val code = RoomCode.generate()
        val heard = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(code, true, "Asha"))
        val guestState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Ravi"))
        lateinit var guest: RoomSession
        val hostPlayer = FakePlayer()
        onThread {
            hostPlayer.playAll(listOf(a), 0, false, null, 0)
            RoomSession(code, "Asha", true, hostPlayer, hub.transport(), hostState, {}, scope, askToJoin = false,
                talkSink = { id, _, _, frames, _ -> heard += id to frames.size }).start()
            guest = RoomSession(code, "Ravi", false, FakePlayer(), hub.transport(), guestState, {}, scope).also { it.start() }
        }
        waitFor("in") { guestState.value!!.phase == Together.Phase.Live }
        val cfg = byteArrayOf(0x14, 0x08)
        val f1 = listOf(ByteArray(40) { 1 }, ByteArray(50) { 2 })
        val f2 = listOf(ByteArray(30) { 3 })
        // A voice note, its two pieces arriving in the wrong order.
        onThread {
            guest.sendVoice("note1", 1, null, f2, 1_000, last = true, live = false, allFrames = f1 + f2, allCfg = cfg)
            guest.sendVoice("note1", 0, cfg, f1, 640, last = false, live = false, allFrames = f1, allCfg = cfg)
        }
        waitFor("host has the note") { hostState.value!!.chat.any { it.voice?.complete == true && !it.voice!!.live } }
        val note = hostState.value!!.chat.first { it.voice != null }.voice!!
        assertEquals(3, note.frames.size)
        assertArrayEquals(f1[0], note.frames[0])
        assertArrayEquals(cfg, note.cfg)
        assertEquals("Ravi", hostState.value!!.chat.first { it.voice != null }.name)
        assertTrue("a note isn't played aloud", heard.isEmpty())
        // Talk is heard as it comes, in order.
        onThread { guest.sendVoice("talk1", 0, cfg, f1, 640, last = false, live = true, allFrames = f1, allCfg = cfg) }
        waitFor("host sees Ravi talking") { hostState.value!!.talking == listOf(guestState.value!!.myId) }
        onThread { guest.sendVoice("talk1", 1, null, f2, 1_000, last = true, live = true, allFrames = f1 + f2, allCfg = cfg) }
        waitFor("talk heard") { heard.size == 2 }
        assertEquals(listOf(2, 1), heard.map { it.second })
        waitFor("talking over") { hostState.value!!.talking.isEmpty() }
        // Talk isn't kept in the chat on either side; only the note is.
        assertEquals(1, hostState.value!!.chat.count { it.voice != null })
        assertEquals(1, guestState.value!!.chat.count { it.voice != null })
        // The guest sees its own note in its chat too.
        assertTrue(guestState.value!!.chat.any { it.mine && it.voice?.id?.endsWith("/note1") == true })
    }
}
