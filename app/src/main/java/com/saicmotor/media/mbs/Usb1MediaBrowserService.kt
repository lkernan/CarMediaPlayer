package com.saicmotor.media.mbs

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import androidx.media.MediaBrowserServiceCompat
import com.saicmotor.media.service.MediaService

/**
 * External-facing browse endpoint for USB 1.
 * Runs in its own process (:Usb1MediaBrowserService) as the original app did.
 * Proxies content and the MediaSession token from MediaService so external
 * clients (BT, voice, launcher) see the same session regardless of which
 * component they bind to.
 */
class Usb1MediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaBrowser: MediaBrowserCompat
    private var pendingResult: Result<MutableList<MediaBrowserCompat.MediaItem>>? = null

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            setSessionToken(mediaBrowser.sessionToken)
            pendingResult?.let { result ->
                mediaBrowser.subscribe(MediaService.USB1_ROOT, subscriptionCallback)
                pendingResult = null
            }
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
            mediaBrowser.unsubscribe(MediaService.USB1_ROOT)
            mediaBrowser.disconnect()
        }
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(MediaService.USB1_ROOT, null)

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
        mediaBrowser.subscribe(MediaService.USB1_ROOT, subscriptionCallback)
    }
}
