package com.saicmotor.media.mbs

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import androidx.media.MediaBrowserServiceCompat
import com.saicmotor.media.service.MediaService

/** Stub — online source not yet implemented. Proxies session token from MediaService. */
class OnlineMediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaBrowser: MediaBrowserCompat

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            // Guard: setSessionToken() may only be called once per service instance.
            if (sessionToken == null) setSessionToken(mediaBrowser.sessionToken)
        }
        override fun onConnectionSuspended() {
            // MediaService died — its session token is now stale.  Self-terminate
            // so the launcher's next bind respawns this proxy with the new token.
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, MediaService::class.java),
            connectionCallback,
            null
        )
        mediaBrowser.connect()
    }

    override fun onDestroy() {
        if (mediaBrowser.isConnected) mediaBrowser.disconnect()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(MediaService.ONLINE_ROOT, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }
}
