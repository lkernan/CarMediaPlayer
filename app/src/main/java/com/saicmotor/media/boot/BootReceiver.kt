package com.saicmotor.media.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saicmotor.media.service.MediaService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, MediaService::class.java))
        }
    }
}
