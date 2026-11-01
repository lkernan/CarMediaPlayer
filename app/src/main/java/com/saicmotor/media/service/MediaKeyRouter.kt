package com.saicmotor.media.service

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Keeps track of the other media apps on the head unit (YouTube, the SAIC
 * radio, the phone's Bluetooth session, …) so steering-wheel keys can be
 * handed to them when they, not us, are the app the driver is listening to.
 *
 * Why this is needed: MediaService marks its session with the hidden
 * FLAG_EXCLUSIVE_GLOBAL_PRIORITY.  That is what makes the steering wheel
 * reach us at all (otherwise the SAIC radio's global-priority session
 * swallows the keys), but it also means *every* media key comes to us, even
 * while YouTube is playing.  The stock radio does exactly that, which is why
 * the wheel can't skip YouTube videos while it's installed.  We take the keys
 * and pass them on to whichever app should really have them.
 *
 * Listing other apps' sessions needs MEDIA_CONTENT_CONTROL, which we hold as
 * a system-UID app.  If that is ever refused, [available] is false and every
 * key is simply handled by us, as before.
 */
class MediaKeyRouter(private val context: Context) {

    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val handler        = Handler(Looper.getMainLooper())

    private val tracked = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    /** False if the system refused to list other apps' media sessions. */
    var available = false
        private set

    /** Package of the other app that most recently started playing, and when. */
    var lastPlayingPackage: String? = null
        private set
    private var lastPlayingAt = 0L

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            track(controllers ?: emptyList())
        }

    fun start() {
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, null, handler)
            track(sessionManager.getActiveSessions(null))
            available = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't see other media sessions; steering-wheel keys won't be forwarded", e)
            available = false
        }
    }

    fun stop() {
        try { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        tracked.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        tracked.clear()
    }

    /** Another app's session that is playing right now, if any. */
    fun playingElsewhere(): MediaController? =
        externalControllers().firstOrNull { it.isPlaying() }

    /**
     * Where a key should go once another app has taken the speakers from us:
     * whatever is playing now, otherwise the app that played most recently
     * (so "play" on the wheel resumes the YouTube video you just paused,
     * matching what Android does without a global-priority session).
     *
     * [yieldedAt] is when we lost the speakers; an app that last played
     * before that isn't the one that took over, so it's ignored.
     */
    fun targetAfterWeYielded(yieldedAt: Long): MediaController? {
        val others = externalControllers()
        others.firstOrNull { it.isPlaying() }?.let { return it }
        val last = lastPlayingPackage ?: return null
        if (lastPlayingAt < yieldedAt - YIELD_SLACK_MS) return null
        return others.firstOrNull { it.packageName == last }
    }

    private fun externalControllers(): List<MediaController> {
        if (!available) return emptyList()
        return try {
            sessionManager.getActiveSessions(null).filter { it.packageName != context.packageName }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun track(controllers: List<MediaController>) {
        val external = controllers.filter { it.packageName != context.packageName }
        val live     = external.map { it.sessionToken }.toSet()

        // Forget sessions that went away.
        tracked.keys.filter { it !in live }.forEach { token ->
            tracked.remove(token)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
        }

        // Watch new ones so we know which app played last.
        for (controller in external) {
            if (controller.sessionToken in tracked) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state?.state == PlaybackState.STATE_PLAYING) notePlaying(controller)
                }
            }
            controller.registerCallback(callback, handler)
            tracked[controller.sessionToken] = controller to callback
            if (controller.isPlaying()) notePlaying(controller)
        }
    }

    private fun notePlaying(controller: MediaController) {
        lastPlayingPackage = controller.packageName
        lastPlayingAt      = SystemClock.elapsedRealtime()
    }

    private fun MediaController.isPlaying(): Boolean {
        val state = playbackState?.state
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    companion object {
        private const val TAG = "CarMedia.Keys"
        /** The other app usually reports PLAYING just after it takes focus from us. */
        private const val YIELD_SLACK_MS = 5_000L
    }
}
