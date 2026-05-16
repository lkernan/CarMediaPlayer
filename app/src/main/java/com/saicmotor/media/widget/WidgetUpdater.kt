package com.saicmotor.media.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.saicmotor.media.R
import com.saicmotor.media.ui.activity.MainActivity

/**
 * Builds RemoteViews for each widget size and pushes updates to AppWidgetManager.
 * Call [pushAll] whenever playback state or metadata changes.
 */
object WidgetUpdater {

    fun pushAll(context: Context, title: String, artist: String, isPlaying: Boolean) {
        val manager = AppWidgetManager.getInstance(context)

        update1x1(context, manager, title)
        update2x1(context, manager, title, artist, isPlaying)
        update2x2(context, manager, title, artist, isPlaying)
    }

    private fun update1x1(context: Context, manager: AppWidgetManager, title: String) {
        val views = RemoteViews(context.packageName, R.layout.widget_1x1)
        views.setTextViewText(R.id.widget_title, title.ifEmpty { context.getString(R.string.app_name) })
        views.setOnClickPendingIntent(R.id.widget_root, pendingLaunch(context))

        manager.updateAppWidget(
            ComponentName(context, MediaWidgetProvider_1x1::class.java), views
        )
    }

    private fun update2x1(
        context: Context, manager: AppWidgetManager,
        title: String, artist: String, isPlaying: Boolean
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_2x1)
        views.setTextViewText(R.id.widget_title,  title)
        views.setTextViewText(R.id.widget_artist, artist)
        views.setImageViewResource(
            R.id.btn_play_pause,
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        views.setOnClickPendingIntent(R.id.widget_root,    pendingLaunch(context))
        views.setOnClickPendingIntent(R.id.btn_previous,   pendingWidget(context, "com.saicmotor.media.widget.previous"))
        views.setOnClickPendingIntent(R.id.btn_play_pause, pendingWidget(context, "com.saicmotor.media.widget.play_pause"))
        views.setOnClickPendingIntent(R.id.btn_next,       pendingWidget(context, "com.saicmotor.media.widget.next"))

        manager.updateAppWidget(
            ComponentName(context, MediaWidgetProvider_2x1::class.java), views
        )
    }

    private fun update2x2(
        context: Context, manager: AppWidgetManager,
        title: String, artist: String, isPlaying: Boolean
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_2x2)
        views.setTextViewText(R.id.widget_title,  title)
        views.setTextViewText(R.id.widget_artist, artist)
        views.setImageViewResource(
            R.id.btn_play_pause,
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        views.setOnClickPendingIntent(R.id.widget_root,     pendingLaunch(context))
        views.setOnClickPendingIntent(R.id.btn_previous,    pendingWidget(context, "com.saicmotor.media.widget.previous"))
        views.setOnClickPendingIntent(R.id.btn_play_pause,  pendingWidget(context, "com.saicmotor.media.widget.play_pause"))
        views.setOnClickPendingIntent(R.id.btn_next,        pendingWidget(context, "com.saicmotor.media.widget.next"))
        views.setOnClickPendingIntent(R.id.btn_source_prev, pendingWidget(context, "com.saicmotor.media.widget.source_prev"))
        views.setOnClickPendingIntent(R.id.btn_source_next, pendingWidget(context, "com.saicmotor.media.widget.source_next"))

        manager.updateAppWidget(
            ComponentName(context, MediaWidgetProvider_2x2::class.java), views
        )
    }

    private fun pendingLaunch(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun pendingWidget(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
