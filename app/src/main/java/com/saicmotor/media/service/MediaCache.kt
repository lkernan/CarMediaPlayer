package com.saicmotor.media.service

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import com.saicmotor.media.data.OfflinePlaylistStore
import com.saicmotor.media.data.Storage
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.subsonic.SubsonicClient
import java.io.File
import java.util.TreeSet

/**
 * A disk cache of songs, shared by the player and the pre-cacher.
 *
 * Songs are cached under their Subsonic id, not their URL: the URL carries a
 * login token that's different every time, so caching by URL would never hit.
 *
 * Upcoming songs are downloaded ahead of time ([precache]), so the next song
 * (or a skip) starts straight away even on a slow mobile connection, and the
 * song after a tunnel is already there.  Old songs are dropped once the cache
 * is full.
 */
@OptIn(UnstableApi::class)
object MediaCache {

    private const val MAX_BYTES  = 400L * 1024 * 1024
    /**
     * Songs are written to the cache in pieces this big, each saved as it
     * fills.  Small, because the car just loses power: a piece still being
     * written is lost, and the song that was playing should be on disk, up
     * to the point that was playing, when the car starts again.
     */
    private const val PIECE_BYTES = 512L * 1024

    @Volatile private var cache: SimpleCache? = null
    private val pinLock = Any()
    @Volatile private var pinnedSongIds: Set<String> = emptySet()
    private val temporaryPins = mutableSetOf<String>()

    /**
     * The cache key for a song (see the class comment).  "v2": 2.0.2–2.0.4
     * cached the original FLAC files by mistake (see [dataSourceFactory]);
     * a new key leaves those to be cleared out first as the cache fills.
     */
    fun keyFor(mediaId: String): String = "song-v2:$mediaId"

    /** In device-protected storage, so songs can play the moment the head unit boots (see Storage). */
    private fun cache(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: run {
                val storage = Storage.context(context.applicationContext)
                pinnedSongIds = OfflinePlaylistStore.pinnedSongIds(storage)
                SimpleCache(
                    File(storage.cacheDir, "songs"),
                    PinnedCacheEvictor(MAX_BYTES, ::isPinnedKey),
                    StandaloneDatabaseProvider(storage)
                )
            }.also { cache = it }
        }

    /** Where the player reads songs from: the cache, falling back to the server. */
    fun dataSourceFactory(context: Context): CacheDataSource.Factory {
        val cache = cache(context)
        val app = context.applicationContext
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(PIECE_BYTES))
            .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(
                // An error message in place of the song is never saved as the song (see SongReplies).
                SongReplies.Factory(
                    // No User-Agent of our own: Navidrome treats each client name +
                    // User-Agent as a separate "player", and its transcoding
                    // (e.g. Opus 128k for the car) is set per player.  Songs must
                    // be fetched with the same User-Agent as the app's other
                    // requests (Android's default), or Navidrome registers a new
                    // player with no transcoding and sends the original files.
                    DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        // Short: while the connection is coming up, a hung attempt
                        // shouldn't hold up the next one (see WaitForNetworkPolicy).
                        .setConnectTimeoutMs(6_000)
                        .setReadTimeoutMs(15_000)
                )
            ) { dataSpec -> dataSpec.withUri(withCurrentLogin(app, dataSpec.uri)) })
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * A song's address with the server and login from Settings as they are
     * now.  The address of every song in the queue carries the login as it
     * was when the queue was made, so after the password (or the server's
     * address) was changed, the queue kept failing until something else was
     * played.
     */
    private fun withCurrentLogin(context: Context, uri: Uri): Uri {
        if (uri.path?.endsWith("/rest/stream") != true) return uri
        val id = uri.getQueryParameter("id") ?: return uri
        val cfg = SubsonicSettings.load(context).takeIf { it.isValid } ?: return uri
        return Uri.parse(SubsonicClient.streamUrl(cfg, id))
    }

    /** How much of the song is on disk (0–1), or null if it's never been fetched. */
    fun cachedFraction(context: Context, mediaId: String): Float? {
        val c = cache(context)
        val key = keyFor(mediaId)
        val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(c.getContentMetadata(key))
        if (length <= 0) return null
        return c.getCachedBytes(key, 0, length).toFloat() / length
    }

    /** Bytes of the song on disk from its start (a transcoded song's full length isn't known until it's all in). */
    fun cachedBytesFromStart(context: Context, mediaId: String): Long =
        cache(context).getCachedLength(keyFor(mediaId), 0, Long.MAX_VALUE).coerceAtLeast(0)

    /** True if the whole song is already on disk. */
    fun isCached(context: Context, mediaId: String): Boolean {
        val c = cache(context)
        val key = keyFor(mediaId)
        val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(c.getContentMetadata(key))
        return length > 0 && c.isCached(key, 0, length)
    }

    /** Protects a playlist while its explicit offline download is in progress. */
    fun beginOfflineDownload(songIds: Collection<String>) = synchronized(pinLock) {
        temporaryPins.addAll(songIds)
    }

    fun endOfflineDownload(songIds: Collection<String>) = synchronized(pinLock) {
        temporaryPins.removeAll(songIds.toSet())
    }

    /** Refresh after a saved playlist has been added or removed. */
    fun refreshPinned(context: Context) {
        pinnedSongIds = OfflinePlaylistStore.pinnedSongIds(context)
    }

    private fun isPinnedKey(key: String): Boolean {
        val id = key.removePrefix("song-v2:")
        if (id == key) return false
        if (id.removePrefix("sub:") in pinnedSongIds) return true
        return synchronized(pinLock) { id in temporaryPins }
    }

    /**
     * Downloads a song into the cache.  Blocking: run it off the main thread.
     * Returns a [CacheWriter] handle via [onStart] so it can be cancelled.
     */
    fun precache(context: Context, mediaId: String, uri: Uri, onStart: (CacheWriter) -> Unit) {
        val dataSpec = DataSpec.Builder().setUri(uri).setKey(keyFor(mediaId)).build()
        val writer = CacheWriter(dataSourceFactory(context).createDataSource(), dataSpec, null, null)
        onStart(writer)
        writer.cache()
    }

    /** Keeps explicitly downloaded playlists while allowing ordinary cache entries to age out. */
    private class PinnedCacheEvictor(
        private val maxBytes: Long,
        private val isPinned: (String) -> Boolean
    ) : CacheEvictor {
        private val spans = TreeSet(compareBy<CacheSpan>({ it.lastTouchTimestamp }, { it.key }, { it.position }))
        private var totalBytes = 0L

        override fun onCacheInitialized() = Unit

        override fun onStartFile(cache: Cache, key: String, position: Long, maxLength: Long) {
            val reserve = if (maxLength == Long.MAX_VALUE) 0 else maxLength.coerceAtLeast(0)
            evict(cache, (maxBytes - reserve).coerceAtLeast(0))
        }

        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            if (spans.add(span)) totalBytes += span.length
            evict(cache, maxBytes)
        }

        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            if (spans.remove(span)) totalBytes -= span.length
        }

        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            spans.remove(oldSpan)
            spans.add(newSpan)
        }

        override fun requiresCacheSpanTouches() = true

        private fun evict(cache: Cache, targetBytes: Long) {
            if (totalBytes <= targetBytes) return
            val iterator = spans.iterator()
            while (iterator.hasNext() && totalBytes > targetBytes) {
                val span = iterator.next()
                if (isPinned(span.key)) continue
                iterator.remove()
                totalBytes -= span.length
                try { cache.removeSpan(span) } catch (_: Exception) { /* cache is already closing */ }
            }
        }
    }
}
