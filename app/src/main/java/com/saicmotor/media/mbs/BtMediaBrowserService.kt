package com.saicmotor.media.mbs

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import androidx.media.MediaBrowserServiceCompat
import com.saicmotor.media.service.MediaService

/**
 * External-facing browse endpoint for Bluetooth A2DP.
 *
 * Bluetooth has no browse tree — the SAIC launcher connects to this service
 * solely to receive the MediaSession token so it can display AVRCP now-playing
 * metadata and reach our transport controls (which proxy to the phone via AVRCP
 * PassThrough commands).
 *
 * All actual state lives in MediaService; this service only proxies its token.
 */
class BtMediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaBrowser: MediaBrowserCompat
    private var pendingResult: Result<MutableList<MediaBrowserCompat.MediaItem>>? = null

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            // Guard: setSessionToken() may only be called once per service instance.
            // The reconnect triggered from onConnectionSuspended() below would
            // otherwise crash this proxy on its second onConnected callback.
            if (sessionToken == null) setSessionToken(mediaBrowser.sessionToken)
            // No children to deliver — resolve any pending result with an empty list
            pendingResult?.sendResult(mutableListOf())
            pendingResult = null
        }
        override fun onConnectionSuspended() {
            // MediaService died; reconnect so the token stays fresh
            if (!mediaBrowser.isConnected) mediaBrowser.connect()
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
    ): BrowserRoot = BrowserRoot(MediaService.BT_ROOT, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        // Bluetooth source has no browsable content
        if (!mediaBrowser.isConnected) {
            result.detach()
            pendingResult = result
        } else {
            result.sendResult(mutableListOf())
        }
    }
}
