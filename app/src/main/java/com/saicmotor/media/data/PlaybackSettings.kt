package com.saicmotor.media.data

import android.content.Context

/** App-wide playback preferences (the server settings live in [SubsonicSettings]). */
object PlaybackSettings {

    /** What to do when the head unit boots. */
    enum class StartupMode {
        /** Carry on with the queue and position from when the car was switched off. */
        RESUME,
        /** Start a fresh shuffle of the whole library. */
        SHUFFLE_LIBRARY,
        /** Stay quiet until something is pressed. */
        NOTHING
    }

    private const val PREFS            = "playback_settings"
    private const val KEY_STARTUP_MODE = "startup_mode"

    fun startupMode(ctx: Context): StartupMode {
        val raw = prefs(ctx).getString(KEY_STARTUP_MODE, null)
        return StartupMode.values().firstOrNull { it.name == raw } ?: StartupMode.RESUME
    }

    fun setStartupMode(ctx: Context, mode: StartupMode) {
        prefs(ctx).edit().putString(KEY_STARTUP_MODE, mode.name).apply()
    }

    private fun prefs(ctx: Context) = Storage.prefs(ctx, PREFS)
}
