package com.saicmotor.media.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.ui.activity.MainActivity
import com.saicmotor.media.update.Updater

/**
 * Starts MediaService when the head unit boots, and asks it to run the
 * "when the car starts" action (resume / shuffle library / nothing).
 *
 * QUICKBOOT_POWERON is what some head units send instead of BOOT_COMPLETED
 * when they wake from a fast-boot sleep; it's harmless where unused.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // LOCKED_BOOT_COMPLETED comes ~3 s sooner (before the user storage
            // unlocks; the service only needs device-protected storage).
            // BOOT_COMPLETED follows; the service ignores the repeat.
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT_POWERON -> context.startForegroundService(
                Intent(context, MediaService::class.java)
                    .setAction(MediaService.ACTION_BOOT_START)
            )
            // Replaced by a new version.  If "Check for updates" did it, the
            // app was open and maybe playing: put both back.
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val after = Updater.afterUpdate(context) ?: return
                if (after.wasPlaying) context.startForegroundService(
                    Intent(context, MediaService::class.java)
                        .setAction(MediaService.ACTION_RESUME_AFTER_UPDATE)
                )
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    companion object {
        private const val ACTION_QUICKBOOT_POWERON     = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
