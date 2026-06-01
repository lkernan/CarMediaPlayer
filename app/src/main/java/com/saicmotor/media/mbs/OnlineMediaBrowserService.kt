package com.saicmotor.media.mbs

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import androidx.media.MediaBrowserServiceCompat
import com.saicmotor.media.service.MediaService

/**
 * External-facing browse endpoint for the Online (Subsonic) source.
 * Runs in its own process (:OnlineMediaBrowserService) so the SAIC launcher
 * can bind to it independently.  Proxies content and the MediaSession token
 * from MediaService so external clients see the same session regardless of
 * which component they bind to.
 */
class OnlineMediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaBrowser: MediaBrowserCompat
    private var pendingResult: Result<MutableList<MediaBrowserCompat.MediaItem>>? = null

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            // Guard: setSessionToken() may only be called once per service instance.
            if (sessionToken == null) setSessionToken(mediaBrowser.sessionToken)
            pendingResult?.let { _ ->
                mediaBrowser.subscribe(MediaService.ONLINE_ROOT, subscriptionCallback)
                pendingResult = null
            }
        }
        override fun onConnectionSuspended() {
            // MediaService died — its session token is now stale.  Self-terminate
            // so the launcher's next bind respawns this proxy with the new token.
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private val subscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(
            parentId: String,
            children: MutableList<MediaBrowserCompat.MediaItem>
        ) {
            pendingResult?.sendResult(children)
            pendingResult = null
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
        if (mediaBrowser.isConnected) {
            mediaBrowser.unsubscribe(MediaService.ONLINE_ROOT)
            mediaBrowser.disconnect()
        }
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
        result.detach()
        if (!mediaBrowser.isConnected) {
            pendingResult = result
            return
        }
        pendingResult = result
        mediaBrowser.subscribe(MediaService.ONLINE_ROOT, subscriptionCallback)
    }
}
