package com.opentune.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import androidx.media3.common.Player
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.opentune.MainActivity
import com.opentune.R
import com.opentune.data.DebugLog as Log
import com.opentune.data.settings.AppSettings
import com.opentune.playback.LiveUpdateRules.State
import com.opentune.ui.Links
import com.opentune.ui.theme.artworkSeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The playing song as an Android 16 Live Update: a chip in the status bar
 * with the time left, and the song at the top of the shade and on the lock
 * screen (Samsung's Now Bar shows the same).
 *
 * It's a second notification, apart from the media one Media3 posts.
 * Android won't promote that one: a MediaStyle notification can't be a Live
 * Update, and the media notification has to stay MediaStyle to keep its
 * controls, its place in Quick Settings and the session the system, cars and
 * watches use. Swapping its style or replacing Media3's provider would lose
 * all of that for a chip. So this one is small and separate: its own silent,
 * low-importance channel, ongoing, a title, the artist, a [Notification.ProgressStyle]
 * bar, no custom views, not colorized and not in a group, which is what
 * promotion asks for. It has no buttons, so there's only one set of controls.
 *
 * It's posted on play, pause, song change, seek and speed change. In between
 * the chip counts down on its own (a chronometer), and the bar moves on every
 * [LiveUpdateRules.progressStepMs], only while the screen is on. A pause that
 * lasts [LiveUpdateRules.PAUSE_LINGER_MS], a stop or the end of the queue
 * takes it down, as does the switch in Settings. Swiped away, it stays away
 * until playback next stops.
 *
 * If Android wouldn't promote it, it would only be a second copy of the media
 * notification, so it isn't posted, or it's taken down and not posted again;
 * Settings says so. That's Android 16 before its first feature drop, which
 * still has the pre-release rules ([Notification.hasPromotableCharacteristics]
 * says no), and phone makers' builds that take it but don't promote it.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
class LiveUpdate(private val context: Context, private val player: Player, private val scope: CoroutineScope) {

    private val nm = context.getSystemService(NotificationManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    private var job: Job? = null
    private var settingJob: Job? = null
    private var coverJob: Job? = null

    /** What's up now, or null when nothing is. */
    private var shown: LiveUpdateRules.Content? = null
    private var shownCover: Bitmap? = null
    /** Just posted: look shortly whether Android promoted it. */
    private var checkPromotion = false

    /** When the current pause began (elapsed realtime), or -1 when not paused. */
    private var pausedAt = -1L
    private var dismissed = false

    private var coverUrl: String? = null
    private var cover: Bitmap? = null
    private var coverColor: Int? = null

    private val openPlayer = PendingIntent.getActivity(
        context,
        OPEN_REQUEST,
        Intent(context, MainActivity::class.java)
            .setAction(Links.ACTION_OPEN_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val onDismiss = PendingIntent.getBroadcast(
        context,
        DISMISS_REQUEST,
        Intent(ACTION_DISMISSED).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                )
            ) {
                refreshSoon(SETTLE_MS)
            }
        }
    }

    /** The swipe-away (sent as this app, so the receiver needn't be exported) and the screen coming on. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DISMISSED -> {
                    dismissed = true
                    shown = null
                    shownCover = null
                }
                Intent.ACTION_SCREEN_ON -> refreshSoon(0)
            }
        }
    }

    fun start() {
        // Left behind if the process died with it up.
        nm.cancel(NOTIFICATION_ID)
        player.addListener(listener)
        val filter = IntentFilter().apply {
            addAction(ACTION_DISMISSED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        settingJob = scope.launch {
            AppSettings.playback.map { it.liveUpdate }.distinctUntilChanged().drop(1).collect {
                // Turned off and on again: try afresh, even on a phone that declined before.
                LiveUpdateAccess.setDeclined(false)
                refreshSoon(0)
            }
        }
        refreshSoon(0)
    }

    fun release() {
        job?.cancel()
        settingJob?.cancel()
        coverJob?.cancel()
        player.removeListener(listener)
        runCatching { context.unregisterReceiver(receiver) }
        hide()
    }

    private fun refreshSoon(delayMs: Long) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            refresh()
        }
    }

    private fun refresh() {
        val snapshot = snapshot()
        val content = if (AppSettings.playback.value.liveUpdate) {
            LiveUpdateRules.content(snapshot, System.currentTimeMillis(), coverColor)
        } else null
        if (content == null) {
            // Down for its own reasons, so an earlier swipe-away has had its effect.
            dismissed = false
            hide()
            return
        }
        if (checkPromotion && shown != null) {
            checkPromotion = false
            if (!promoted()) {
                Log.w(TAG, "Posted, but Android didn't make it a Live Update; not posting it again")
                LiveUpdateAccess.setDeclined(true)
            }
        }
        if (dismissed || LiveUpdateAccess.declined.value || !nm.areNotificationsEnabled() || !nm.canPostPromotedNotifications()) {
            hide()
            return
        }
        loadCover()
        if (!LiveUpdateRules.samePost(shown, content) || shownCover !== cover) post(content)
        val next = LiveUpdateRules.nextRefreshMs(snapshot, content, power?.isInteractive != false)
        val wait = if (checkPromotion) minOf(next ?: Long.MAX_VALUE, PROMOTION_CHECK_MS) else next
        if (wait != null) refreshSoon(wait)
    }

    private fun snapshot(): LiveUpdateRules.Snapshot {
        val state = when {
            player.currentMediaItem == null ||
                player.playbackState == Player.STATE_IDLE ||
                player.playbackState == Player.STATE_ENDED -> State.STOPPED
            player.isPlaying -> State.PLAYING
            player.playWhenReady && player.playbackState == Player.STATE_BUFFERING -> State.LOADING
            else -> State.PAUSED
        }
        val now = SystemClock.elapsedRealtime()
        pausedAt = if (state == State.PAUSED) pausedAt.takeIf { it >= 0 } ?: now else -1L
        val metadata = player.mediaMetadata
        return LiveUpdateRules.Snapshot(
            state = state,
            title = metadata.title?.toString(),
            artist = metadata.artist?.toString(),
            durationMs = if (player.isCurrentMediaItemLive) 0L else player.duration,
            positionMs = player.currentPosition,
            speed = player.playbackParameters.speed,
            pausedForMs = if (pausedAt >= 0) now - pausedAt else 0L,
        )
    }

    /** The cover for the large icon and the bar's colour, the same colour the player takes. */
    private fun loadCover() {
        val url = player.currentMediaItem?.toSong()?.thumbnailUrl
        if (url == coverUrl) return
        coverUrl = url
        cover = null
        coverColor = null
        coverJob?.cancel()
        if (url == null) return
        coverJob = scope.launch {
            val color = artworkSeed(context, url)
            // Coil's mapper (OpenTuneApp) turns a Subsonic cover reference into its URL.
            val request = ImageRequest.Builder(context).data(url).size(COVER_PX).allowHardware(false).build()
            val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
            if (coverUrl != url) return@launch
            cover = bitmap
            coverColor = color
            refreshSoon(0)
        }
    }

    private fun post(content: LiveUpdateRules.Content) {
        ensureChannel(nm)
        val notification = notification(context, content, cover, openPlayer, onDismiss)
        if (!notification.hasPromotableCharacteristics()) {
            Log.w(TAG, "This Android wouldn't make it a Live Update; not posting it")
            LiveUpdateAccess.setDeclined(true)
            return hide()
        }
        val first = shown == null
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to notify", e)
            return
        }
        shown = content
        shownCover = cover
        if (first) checkPromotion = true
    }

    private fun promoted(): Boolean =
        nm.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }
            ?.let { it.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0 }
            // Already gone (swiped away, or notifications turned off): nothing to judge.
            ?: true

    private fun hide() {
        checkPromotion = false
        if (shown == null) return
        shown = null
        shownCover = null
        nm.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "LiveUpdate"
        const val CHANNEL = "live_update"
        /** Clear of Media3's 1001 and the download and update notifications. */
        const val NOTIFICATION_ID = 7_160
        private const val OPEN_REQUEST = 7_161
        private const val DISMISS_REQUEST = 7_162
        private const val ACTION_DISMISSED = "com.opentune.action.LIVE_UPDATE_DISMISSED"
        /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING, public from Android 17's SDK. */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        /** Events come in bursts (a song change is four or five); one post covers them. */
        private const val SETTLE_MS = 300L
        private const val PROMOTION_CHECK_MS = 1_500L
        private const val COVER_PX = 160

        /**
         * The notification itself, kept apart from the player so a test can
         * check Android would promote it.
         */
        internal fun notification(
            context: Context,
            c: LiveUpdateRules.Content,
            cover: Bitmap?,
            open: PendingIntent?,
            dismissed: PendingIntent?,
        ): Notification {
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_opentune)
                .setContentTitle(c.title)
                .setContentText(c.text.ifEmpty { null })
                .setLargeIcon(cover)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .setDeleteIntent(dismissed)
                // Notification.Builder.setRequestPromotedOngoing is only in Android 17's SDK;
                // Android 16 reads the same extra.
                .addExtras(bundleOf(EXTRA_REQUEST_PROMOTED_ONGOING to true))
            c.color?.let(b::setColor)
            when (val chip = c.chip) {
                is LiveUpdateRules.Chip.CountDown -> b.setWhen(chip.endsAtMs)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                is LiveUpdateRules.Chip.Text -> b.setShowWhen(false).setShortCriticalText(chip.text)
            }
            if (c.progress != null) {
                // One segment for the whole song in the cover's colour, played part
                // full and the rest faded. Several segments would draw with gaps,
                // which reads as chapters a song doesn't have.
                val segment = Notification.ProgressStyle.Segment(LiveUpdateRules.PROGRESS_MAX)
                c.color?.let(segment::setColor)
                b.setStyle(
                    Notification.ProgressStyle()
                        .setStyledByProgress(true)
                        .setProgressSegments(listOf(segment))
                        .setProgress(c.progress),
                )
            }
            return b.build()
        }

        fun ensureChannel(nm: NotificationManager) {
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Now playing in the status bar", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The playing song as a Live Update, with the time left"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }
}

/**
 * For the Settings row: whether Android lets the Live Update show, and the
 * system page that fixes it when not. Safe to call on any Android version.
 */
object LiveUpdateAccess {
    private val _declined = MutableStateFlow(false)

    /** Android took the Live Update but didn't promote it, this run of the app. */
    val declined: StateFlow<Boolean> = _declined.asStateFlow()

    internal fun setDeclined(value: Boolean) {
        _declined.value = value
    }

    fun state(context: Context): LiveUpdateRules.Access {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return LiveUpdateRules.Access.READY
        val nm = context.getSystemService(NotificationManager::class.java) ?: return LiveUpdateRules.Access.READY
        // Made here too, so its own settings page can be opened before anything has played.
        LiveUpdate.ensureChannel(nm)
        // A minimised channel can't be promoted either.
        val channelOn = (nm.getNotificationChannel(LiveUpdate.CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_LOW) >= NotificationManager.IMPORTANCE_LOW
        return LiveUpdateRules.access(nm.areNotificationsEnabled(), channelOn, nm.canPostPromotedNotifications(), declined.value)
    }

    /** The system page that fixes [access], or null when nothing needs fixing. */
    fun fixIntent(context: Context, access: LiveUpdateRules.Access): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        val action = when (access) {
            LiveUpdateRules.Access.READY -> return null
            LiveUpdateRules.Access.NOTIFICATIONS_OFF -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            LiveUpdateRules.Access.CHANNEL_OFF -> Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
            LiveUpdateRules.Access.LIVE_UPDATES_OFF, LiveUpdateRules.Access.NOT_SHOWN -> Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS
        }
        return Intent(action)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, LiveUpdate.CHANNEL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
