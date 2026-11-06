package com.saicmotor.media.data

import android.content.Context
import android.os.SystemClock
import com.saicmotor.media.subsonic.SubsonicAlbum
import com.saicmotor.media.subsonic.SubsonicAlbumDetail
import com.saicmotor.media.subsonic.SubsonicArtist
import com.saicmotor.media.subsonic.SubsonicArtistDetail
import com.saicmotor.media.subsonic.SubsonicClient
import com.saicmotor.media.subsonic.SubsonicGenre
import com.saicmotor.media.subsonic.SubsonicPlaylist
import com.saicmotor.media.subsonic.SubsonicPlaylistDetail
import com.saicmotor.media.subsonic.SubsonicResults
import com.saicmotor.media.subsonic.SubsonicServerInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the screens get library data from.  The app uses Navidrome; tests use sample data. */
interface LibrarySource {
    val isConfigured: Boolean
    /** Host name shown in messages ("Can't reach music.example.com"). */
    val serverName: String?
    fun coverUrl(coverArt: String?, sizePx: Int): String?

    fun albumList(type: String, size: Int): List<SubsonicAlbum>
    fun allAlbums(): List<SubsonicAlbum>
    fun artists(): List<SubsonicArtist>
    fun artist(id: String): SubsonicArtistDetail
    fun album(id: String): SubsonicAlbumDetail
    fun genres(): List<SubsonicGenre>
    fun genreAlbums(genre: String): List<SubsonicAlbum>
    fun playlists(): List<SubsonicPlaylist> = emptyList()
    fun playlist(id: String): SubsonicPlaylistDetail =
        throw com.saicmotor.media.subsonic.SubsonicException(
            com.saicmotor.media.subsonic.SubsonicException.Kind.SERVER, "Playlist not found")
    fun search(query: String): SubsonicResults
    fun ping(): SubsonicServerInfo
}

/** The real thing: the Navidrome server saved in [SubsonicSettings]. */
class SubsonicLibrarySource(context: Context) : LibrarySource {
    private val app = context.applicationContext
    private val cfg get() = SubsonicSettings.load(app)

    override val isConfigured get() = cfg.isValid
    override val serverName get() = cfg.url.takeIf { it.isNotBlank() }
        ?.let { android.net.Uri.parse(it).host ?: it }

    override fun coverUrl(coverArt: String?, sizePx: Int): String? {
        val c = cfg
        return if (coverArt == null || !c.isValid) null else SubsonicClient.coverArtUrl(c, coverArt, sizePx)
    }

    override fun albumList(type: String, size: Int) = SubsonicClient.getAlbumList(cfg, type, size)
    override fun allAlbums() = SubsonicClient.getAllAlbums(cfg)
    override fun artists() = SubsonicClient.getArtists(cfg)
    override fun artist(id: String) = SubsonicClient.getArtist(cfg, id)
    override fun album(id: String) = SubsonicClient.getAlbum(cfg, id)
    override fun genres() = SubsonicClient.getGenres(cfg)
    override fun genreAlbums(genre: String) = SubsonicClient.getAllAlbums(cfg, "byGenre", genre)
    override fun playlists() = SubsonicClient.getPlaylists(cfg)
    override fun playlist(id: String) = SubsonicClient.getPlaylist(cfg, id)
    override fun search(query: String) = SubsonicClient.search(cfg, query)
    override fun ping() = SubsonicClient.ping(cfg)
}

/**
 * The screens' way into the library.  Calls run off the main thread and
 * throw [com.saicmotor.media.subsonic.SubsonicException] on failure.
 * Lists are cached briefly so flicking between tabs doesn't refetch.
 */
object Library {
    lateinit var source: LibrarySource
    var io: CoroutineDispatcher = Dispatchers.IO

    private const val TTL_MS = 3 * 60_000L
    private val cache = HashMap<String, Pair<Long, Any>>()

    fun init(context: Context) {
        if (!::source.isInitialized) source = SubsonicLibrarySource(context)
    }

    val isConfigured get() = source.isConfigured
    val serverName get() = source.serverName
    fun coverUrl(coverArt: String?, sizePx: Int = 300): String? = source.coverUrl(coverArt, sizePx)

    /** Runs [block] on the IO dispatcher; with [cacheKey], reuses a recent result. */
    suspend fun <T : Any> load(cacheKey: String? = null, block: LibrarySource.() -> T): T {
        if (cacheKey != null) {
            synchronized(cache) {
                cache[cacheKey]?.let { (at, value) ->
                    @Suppress("UNCHECKED_CAST")
                    if (SystemClock.elapsedRealtime() - at < TTL_MS) return value as T
                }
            }
        }
        val value = withContext(io) { source.block() }
        if (cacheKey != null) synchronized(cache) { cache[cacheKey] = SystemClock.elapsedRealtime() to value }
        return value
    }

    /** Forget cached lists (after changing servers, or starring something). */
    fun clearCache() = synchronized(cache) { cache.clear() }
}
