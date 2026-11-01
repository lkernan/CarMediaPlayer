package com.saicmotor.media.ui.common

import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.service.MediaService.Companion.SHUFFLE_ALL_ID
import kotlinx.coroutines.flow.MutableStateFlow

/** The song the player has loaded. */
data class NowPlaying(
    val mediaId:    String,
    val title:      String,
    val artist:     String,
    val album:      String,
    val artUri:     String?,
    val durationMs: Long,
    val albumId:    String?,
    val artistId:   String?
) {
    val songId: String get() = mediaId.removePrefix(MediaService.SUBSONIC_SONG_PREFIX)

    companion object {
        fun from(m: MediaMetadataCompat?): NowPlaying? {
            if (m == null) return null
            val title = m.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty()
            val id    = m.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID).orEmpty()
            if (title.isEmpty() && id.isEmpty()) return null
            return NowPlaying(
                mediaId    = id,
                title      = title,
                artist     = m.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty(),
                album      = m.getString(MediaMetadataCompat.METADATA_KEY_ALBUM).orEmpty(),
                // DISPLAY_ICON_URI: the service leaves ALBUM_ART_URI out (see MediaService.buildMetadata).
                artUri     = (m.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI)
                              ?: m.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI))?.takeIf { it.isNotBlank() },
                durationMs = m.getLong(MediaMetadataCompat.METADATA_KEY_DURATION),
                albumId    = m.getString(MediaService.METADATA_ALBUM_ID)?.takeIf { it.isNotBlank() },
                artistId   = m.getString(MediaService.METADATA_ARTIST_ID)?.takeIf { it.isNotBlank() }
            )
        }
    }
}

/** Play/pause state and position. */
data class Transport(
    val state:      Int     = PlaybackStateCompat.STATE_NONE,
    val positionMs: Long    = 0L,
    /** elapsedRealtime when [positionMs] was measured. */
    val updatedAt:  Long    = 0L,
    val speed:      Float   = 0f,
    val activeQueueId: Long = MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong(),
    val error:      String? = null
) {
    val isPlaying get() = state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING

    /** Where playback is now, extrapolated from the last update. */
    fun currentPosition(now: Long = SystemClock.elapsedRealtime()): Long =
        if (state == PlaybackStateCompat.STATE_PLAYING && updatedAt > 0)
            positionMs + ((now - updatedAt) * speed).toLong()
        else positionMs
}

data class QueueEntry(
    val id:         Long,
    val mediaId:    String,
    val title:      String,
    val artist:     String,
    val artUri:     String?,
    val durationMs: Long
)

/** What the screens show about playback, fed from the MediaSession by MainActivity. */
object PlayerState {
    val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val transport  = MutableStateFlow(Transport())
    val queue      = MutableStateFlow<List<QueueEntry>>(emptyList())

    fun updateFrom(state: PlaybackStateCompat?) {
        transport.value = Transport(
            state         = state?.state ?: PlaybackStateCompat.STATE_NONE,
            positionMs    = state?.position ?: 0L,
            updatedAt     = state?.lastPositionUpdateTime ?: 0L,
            speed         = state?.playbackSpeed ?: 0f,
            activeQueueId = state?.activeQueueItemId ?: MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong(),
            error         = state?.errorMessage?.toString()?.takeIf { state.state == PlaybackStateCompat.STATE_ERROR }
        )
    }

    fun updateQueue(items: List<MediaSessionCompat.QueueItem>?) {
        queue.value = items.orEmpty().map { q ->
            val d = q.description
            QueueEntry(
                id         = q.queueId,
                mediaId    = d.mediaId.orEmpty(),
                title      = d.title?.toString().orEmpty(),
                artist     = d.subtitle?.toString().orEmpty(),
                artUri     = d.iconUri?.toString(),
                durationMs = d.extras?.getLong("duration", -1L) ?: -1L
            )
        }
    }
}

/** Commands to the player, sent through the MediaSession. */
object Playback {
    var controller: MediaControllerCompat? = null
    private val controls get() = controller?.transportControls

    fun playPause() {
        if (PlayerState.transport.value.isPlaying) controls?.pause() else controls?.play()
    }
    fun next()             { controls?.skipToNext() }
    fun previous()         { controls?.skipToPrevious() }
    fun seekTo(ms: Long)   { controls?.seekTo(ms) }
    fun skipTo(queueId: Long) { controls?.skipToQueueItem(queueId) }

    fun shuffleLibrary() { controls?.playFromMediaId(SHUFFLE_ALL_ID, Bundle()) }

    /** Play everything in [parentId] in order (shuffle off) or shuffled (shuffle on). */
    fun playAll(parentId: String, shuffle: Boolean) {
        controls?.sendCustomAction(MediaService.ACTION_PLAY_ALL, Bundle().apply {
            putString(MediaService.EXTRA_PARENT_ID, parentId)
            putBoolean(MediaService.EXTRA_SHUFFLE, shuffle)
        })
    }

    /** Play [parentId] in order, starting from one song. */
    fun playFrom(parentId: String, songId: String) {
        controls?.sendCustomAction(MediaService.ACTION_PLAY_ALL, Bundle().apply {
            putString(MediaService.EXTRA_PARENT_ID, parentId)
            putString(MediaService.EXTRA_START_ID, MediaService.songMediaId(songId))
            putBoolean(MediaService.EXTRA_SHUFFLE, false)
        })
    }
}
