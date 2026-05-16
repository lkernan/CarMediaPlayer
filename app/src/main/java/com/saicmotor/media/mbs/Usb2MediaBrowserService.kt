package com.saicmotor.media.mbs

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import androidx.media.MediaBrowserServiceCompat
import com.saicmotor.media.service.MediaService

/**
 * External-facing browse endpoint for USB 2.
 * Internally we treat both USB ports identically — this service proxies the same
 * MediaSession token and browse tree as Usb1MediaBrowserService.
 * The SAIC launcher selects this service when the drive is on the second USB port.
 */
class Usb2MediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaBrowser: MediaBrowserCompat
    private var pendingResult: Result<MutableList<MediaBrowserCompat.MediaItem>>? = null

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            setSessionToken(mediaBrowser.sessionToken)
            pendingResult?.let {
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
