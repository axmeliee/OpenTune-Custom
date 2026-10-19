package com.opentune.data

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player

/**
 * What the shared code needs from whichever app it runs in (phone and TV, or
 * watch) without knowing that app's classes: how to open it, and what else
 * shows the playing song.
 */
object AppHooks {
    /** Opens the app at its main screen, as its launcher icon does. */
    fun openApp(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.setPackage(context.packageName)
            ?.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Called as the player's song or state changes, for widgets showing it. */
    @Volatile var onPlayerChanged: (Context, Player?) -> Unit = { _, _ -> }

    /** Called when the player stops for good. */
    @Volatile var onPlayerStopped: (Context) -> Unit = { }
}
