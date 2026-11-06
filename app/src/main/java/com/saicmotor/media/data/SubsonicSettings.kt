package com.saicmotor.media.data

import android.content.Context

object SubsonicSettings {

    data class Config(
        val url:      String,
        val username: String,
        val password: String
    ) {
        /** True when all three fields are non-blank. */
        val isValid get() = url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    }

    private const val PREFS    = "subsonic_prefs"
    private const val KEY_URL  = "url"
    private const val KEY_USER = "username"
    private const val KEY_PASS = "password"

    fun load(ctx: Context): Config {
        val p = Storage.prefs(ctx, PREFS)
        return Config(
            url      = p.getString(KEY_URL,  "") ?: "",
            username = p.getString(KEY_USER, "") ?: "",
            password = p.getString(KEY_PASS, "") ?: ""
        )
    }

    fun save(ctx: Context, config: Config) {
        Storage.prefs(ctx, PREFS).edit()
            .putString(KEY_URL,  config.url.trimEnd('/'))
            .putString(KEY_USER, config.username.trim())
            .putString(KEY_PASS, config.password)
            .apply()
    }
}
