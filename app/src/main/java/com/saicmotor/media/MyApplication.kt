package com.saicmotor.media

import android.app.Application
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(currentNightMode())
    }

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
