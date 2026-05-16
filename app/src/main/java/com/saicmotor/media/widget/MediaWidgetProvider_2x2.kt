package com.saicmotor.media.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.ui.activity.MainActivity

class MediaWidgetProvider_2x2 : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.saicmotor.media.widget.start_activity" -> {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            "com.saicmotor.media.widget.play_pause" ->
                context.startService(
                    Intent(context, MediaService::class.java)
                        .setAction(MediaService.ACTION_PLAY_PAUSE)
                )
            "com.saicmotor.media.widget.previous" ->
                context.startService(
                    Intent(context, MediaService::class.java)
                        .setAction(MediaService.ACTION_PREVIOUS)
                )
            "com.saicmotor.media.widget.next" ->
                context.startService(
                    Intent(context, MediaService::class.java)
                        .setAction(MediaService.ACTION_NEXT)
                )
            // Source switching — TODO: implement source cycling in MediaService
            "com.saicmotor.media.widget.source_prev",
            "com.saicmotor.media.widget.source_next" -> { /* no-op for now */ }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetUpdater.pushAll(context, "", "", false)
    }
}
