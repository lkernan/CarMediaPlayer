package com.saicmotor.media.mbs

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.saicmotor.media.service.MediaService

/**
 * Browse endpoint for the SAIC launcher.
 *
 * The launcher binds to the stock media app's per-source services by name
 * (USB 1, USB 2, Online, Bluetooth), each in its own process.  This app only
 * plays Navidrome, so every one of those names is kept but serves the same
 * thing: the Navidrome library, and MediaService's session token, so the
 * launcher's tile and buttons drive the one player whichever it binds to.
 */
abstract class LauncherProxyService : MediaBrowserServiceCompat() {

    private companion object { const val TAG = "CarMedia.Launcher" }

    private lateinit var mediaBrowser: MediaBrowserCompat
    /** Requests that arrived before we'd connected to MediaService. */
    private val pending = mutableListOf<Pair<String, Result<MutableList<MediaBrowserCompat.MediaItem>>>>()

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            // setSessionToken() may only be called once per service instance;
            // MediaBrowserCompat reconnects after transient disconnects.
            if (sessionToken == null) setSessionToken(mediaBrowser.sessionToken)
            Log.i(TAG, "${javaClass.simpleName}: connected to the player; user=${android.os.Process.myUserHandle()}")
            pending.forEach { (parentId, result) -> forward(parentId, result) }
            pending.clear()
        }
        override fun onConnectionFailed() {
            Log.w(TAG, "${javaClass.simpleName}: couldn't connect to the player")
        }
        override fun onConnectionSuspended() {
            Log.w(TAG, "${javaClass.simpleName}: player went away; restarting")
            // MediaService died — its session token is now stale.  Self-terminate
            // so the launcher's next bind respawns this proxy with the new token.
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaBrowser = MediaBrowserCompat(
            this, ComponentName(this, MediaService::class.java), connectionCallback, null
        )
        mediaBrowser.connect()
    }

    override fun onDestroy() {
        if (mediaBrowser.isConnected) mediaBrowser.disconnect()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        Log.i(TAG, "${javaClass.simpleName}: browse connection from $clientPackageName (uid $clientUid), " +
                   "session ready=${sessionToken != null}")
        return BrowserRoot(MediaService.ONLINE_ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        if (mediaBrowser.isConnected) forward(parentId, result) else pending += parentId to result
    }

    /** Asks MediaService for [parentId]'s children and hands back the first answer. */
    private fun forward(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        mediaBrowser.subscribe(parentId, object : MediaBrowserCompat.SubscriptionCallback() {
            private var answered = false
            override fun onChildrenLoaded(id: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
                if (answered) return
                answered = true
                result.sendResult(children)
                mediaBrowser.unsubscribe(id, this)
            }
            override fun onError(id: String) {
                if (answered) return
                answered = true
                result.sendResult(mutableListOf())
                mediaBrowser.unsubscribe(id, this)
            }
        })
    }
}
