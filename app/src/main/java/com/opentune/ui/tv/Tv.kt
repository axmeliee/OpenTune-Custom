package com.opentune.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

/** Whether the app is on a TV, driven by a remote instead of a finger. */
val LocalIsTv = staticCompositionLocalOf { false }

object Tv {
    fun isTv(context: Context): Boolean {
        val tv = Configuration.UI_MODE_TYPE_TELEVISION
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == tv ||
            context.getSystemService(UiModeManager::class.java)?.currentModeType == tv ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}
