package com.saicmotor.media

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.saicmotor.media.data.Storage
import com.saicmotor.media.data.Library

class MyApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Settings and caches live in device-protected storage (see Storage);
        // move them there once, from the main process only.
        if (isMainProcess() && !Storage.migrate(this)) {
            // Started before the storage unlocked (just after boot): move
            // them as soon as it does.
            registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Storage.migrate(this@MyApplication)
                    unregisterReceiver(this)
                }
            }, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        AppCompatDelegate.setDefaultNightMode(currentNightMode())
        Library.init(this)
    }

    private fun isMainProcess(): Boolean =
        if (Build.VERSION.SDK_INT >= 28) getProcessName() == packageName else true

    /**
     * Covers are kept on disk whatever the server's cache headers say, so
     * the car doesn't redownload them over mobile data every drive.  The
     * cache is in device-protected storage, so the car's menus get the cover
     * even right after the head unit boots.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .respectCacheHeaders(false)
            .diskCache {
                DiskCache.Builder()
                    .directory(Storage.context(this).cacheDir.resolve("image_cache"))
                    .build()
            }
            .build()

    companion object {
        /**
         * SAIC skin config: 1 = day/light, 0 = night/dark.
         * Only used when the setting is explicitly present on the device.
         */
        fun nightModeFromSkinConfig(skinConfig: Int) =
            if (skinConfig == 1) AppCompatDelegate.MODE_NIGHT_NO
            else                 AppCompatDelegate.MODE_NIGHT_YES
    }

    fun currentNightMode(): Int {
        // SKIN_THEME_CONFIG lives in Settings.System (not Secure) on SAIC head units.
        // Default 1 = day/light when the setting hasn't been written yet.
        val skinConfig = Settings.System.getInt(contentResolver, "SKIN_THEME_CONFIG", 1)
        return nightModeFromSkinConfig(skinConfig)
    }
}
