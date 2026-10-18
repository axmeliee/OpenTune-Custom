package com.opentune.playback

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.AudioEffect

/**
 * The phone's own equalizer (Samsung's, Dolby's, the stock one), opened on
 * OpenTune's playback. It shapes the sound only while "System audio effects"
 * lets the phone's effects in.
 */
object PhoneEqualizer {
    /** The player's audio session, kept by [PlaybackService]; unset while nothing has played. */
    @Volatile var session: Int = AudioManager.AUDIO_SESSION_ID_GENERATE

    private fun intent(context: Context) = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        .apply { if (session != AudioManager.AUDIO_SESSION_ID_GENERATE) putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session) }

    /** Whether this phone has an equalizer app to open. */
    fun available(context: Context): Boolean =
        context.packageManager.queryIntentActivities(intent(context), PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()

    /** Opens it; false when there's none or it wouldn't open. */
    fun open(context: Context): Boolean = runCatching {
        context.startActivity(intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
