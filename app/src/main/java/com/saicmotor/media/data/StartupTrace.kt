package com.saicmotor.media.data

import android.content.Context

/**
 * How the last start-up went, timed from when the head unit started: when
 * the queue was back, when the car's launcher asked for music, when it was
 * actually playing, and how much of the first song was already downloaded.
 * Shown at the bottom of Settings, so a slow start can be pinned down
 * without a laptop in the car.
 */
object StartupTrace {

    data class Last(
        /** Milliseconds after the head unit started; -1 = didn't happen. */
        val queueMs:    Long,
        val launcherMs: Long,
        val playingMs:  Long,
        /** Percent of the first song already on disk; -1 = unknown; [PARTLY] = some (length not known yet). */
        val onDiskPct:  Int
    )

    private const val PREFS = "startup_trace"
    const val PARTLY = -2

    fun save(ctx: Context, last: Last) {
        prefs(ctx).edit()
            .putLong("queue_ms", last.queueMs)
            .putLong("launcher_ms", last.launcherMs)
            .putLong("playing_ms", last.playingMs)
            .putInt("on_disk_pct", last.onDiskPct)
            .apply()
    }

    fun load(ctx: Context): Last? {
        val p = prefs(ctx)
        if (!p.contains("playing_ms")) return null
        return Last(
            queueMs    = p.getLong("queue_ms", -1),
            launcherMs = p.getLong("launcher_ms", -1),
            playingMs  = p.getLong("playing_ms", -1),
            onDiskPct  = p.getInt("on_disk_pct", -1)
        )
    }

    private fun prefs(ctx: Context) = Storage.prefs(ctx, PREFS)
}
