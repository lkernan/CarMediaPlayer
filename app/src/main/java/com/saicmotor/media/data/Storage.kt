package com.saicmotor.media.data

import android.content.Context
import android.os.UserManager
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Where the app keeps its settings, saved queue and caches: Android's
 * device-protected storage.
 *
 * The head unit uses file-based encryption.  For the first ~10 s after it
 * boots, the normal (credential-protected) storage is still locked, and
 * Android hides every app that needs it — the car's launcher was ready to
 * bring the music back 15 s after boot, but couldn't reach this app until
 * the storage unlocked at ~25 s.  (The stock media player and radio wait for
 * it too.)  Device-protected storage is readable straight away, so with the
 * playback parts of the app marked direct-boot aware, the music can come
 * back as soon as the launcher asks.  The car has no lock screen, so the
 * storage is unlocked at every boot either way: this is no less private.
 */
object Storage {

    private const val TAG = "CarMedia"

    /** A context whose files, preferences, databases and cache are in device-protected storage. */
    fun context(ctx: Context): Context =
        if (ctx.isDeviceProtectedStorage) ctx else ctx.createDeviceProtectedStorageContext()

    fun prefs(ctx: Context, name: String) =
        context(ctx).getSharedPreferences(name, Context.MODE_PRIVATE)

    private val PREFS_FILES = listOf(
        "subsonic_prefs", "playback_settings", "resume_state"
    )
    private const val SNAPSHOT_FILE = "queue_snapshot.json"
    private const val FLAGS = "storage"
    private const val KEY_MOVED = "moved_to_device_storage"

    /**
     * Once, after updating from a version that used normal storage: moves the
     * settings and saved queue across, and deletes the old song and cover
     * caches (they can't be moved; they refill as you listen).  Needs the
     * normal storage: returns false if that's still locked (try again once
     * it unlocks).
     */
    fun migrate(app: Context): Boolean {
        val de = context(app)
        val flags = de.getSharedPreferences(FLAGS, Context.MODE_PRIVATE)
        if (flags.getBoolean(KEY_MOVED, false)) return true
        val unlocked = app.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        if (!unlocked) return false
        try {
            for (name in PREFS_FILES) {
                if (!de.moveSharedPreferencesFrom(app, name)) Log.w(TAG, "Couldn't move settings file $name")
            }
            val oldSnapshot = File(app.filesDir, SNAPSHOT_FILE)
            if (oldSnapshot.exists()) {
                oldSnapshot.copyTo(File(de.filesDir, SNAPSHOT_FILE), overwrite = true)
                oldSnapshot.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Moving to device storage failed part-way", e)
        }
        flags.edit().putBoolean(KEY_MOVED, true).apply()
        Log.i(TAG, "Settings moved to device-protected storage")
        thread(name = "CarMedia-old-caches") {
            try {
                File(app.cacheDir, "songs").deleteRecursively()
                File(app.cacheDir, "image_cache").deleteRecursively()
                app.deleteDatabase("exoplayer_internal.db")
            } catch (_: Exception) {
                // Only space: the system clears old cache in time anyway.
            }
        }
        return true
    }
}
