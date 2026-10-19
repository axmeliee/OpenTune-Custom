package com.opentune.wear

import android.content.Intent
import android.media.AudioManager
import android.provider.Settings

/** The crown as a volume knob, and where to pair headphones. */
object WatchVolume {
    /** How far the crown turns for one volume step, in the pixels it reports. */
    private const val PIXELS_PER_STEP = 60f

    /** A volume step for one rotary event: up turning forward, down turning back, none for a nudge. */
    fun step(pixels: Float): Int? = when {
        pixels >= PIXELS_PER_STEP / 4 -> AudioManager.ADJUST_RAISE
        pixels <= -PIXELS_PER_STEP / 4 -> AudioManager.ADJUST_LOWER
        else -> null
    }

    /**
     * Wear OS's Bluetooth picker, showing only headphones and closing once
     * one connects. Watches play music only through Bluetooth audio.
     */
    fun headphonesIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        .putExtra("EXTRA_CONNECTION_ONLY", true)
        .putExtra("EXTRA_CLOSE_ON_CONNECT", true)
        .putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", 1)
}
