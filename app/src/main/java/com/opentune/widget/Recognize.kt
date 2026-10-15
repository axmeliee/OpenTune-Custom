package com.opentune.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.opentune.MainActivity
import com.opentune.R

/**
 * Ways to name a song from outside the app: a home-screen widget and a
 * Quick Settings tile. Both open OpenTune listening at once; Android only
 * lets an app on screen use the microphone, so it can't listen hidden.
 */
object RecognizeLaunch {
    const val ACTION = "com.opentune.action.RECOGNIZE"

    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun pending(context: Context): PendingIntent =
        PendingIntent.getActivity(context, ACTION.hashCode(), intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** "Name that song" on the home screen: a tap opens the app listening. */
class RecognizeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_recognize).apply {
            setOnClickPendingIntent(R.id.recognize_root, RecognizeLaunch.pending(context))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}

/** "Name that song" in Quick Settings. */
class RecognizeTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(RecognizeLaunch.pending(this))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(RecognizeLaunch.intent(this))
        }
    }
}
