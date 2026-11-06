package com.saicmotor.media.subsonic

import android.util.Log
import com.saicmotor.media.data.SubsonicSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

// ── Data models ───────────────────────────────────────────────────────────────

data class SubsonicArtist(
    val id:         String,
    val name:       String,
    val coverArt:   String?,
    val albumCount: Int     = 0
)

data class SubsonicAlbum(
    val id:          String,
    val name:        String,
    val artist:      String?,
    val coverArt:    String?,
    val artistId:    String? = null,
    val year:        Int     = 0,
    val songCount:   Int     = 0,
    val durationSec: Int     = 0
)

data class SubsonicSong(
    val id:         String,
    val title:      String,
    val artist:     String?,
    val album:      String?,
    val durationMs: Long,       // milliseconds
    val coverArt:   String?,
    val albumId:    String? = null,
    val artistId:   String? = null,
    val track:      Int     = 0,
    val disc:       Int     = 0,
    val suffix:     String? = null,
    val sizeBytes:  Long    = 0L
)

data class SubsonicGenre(
    val name:       String,
    val albumCount: Int,
    val songCount:  Int
)

data class SubsonicPlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val comment: String?,
    val songCount: Int,
    val durationSec: Int,
    val changed: String?,
    val coverArt: String?
)

data class SubsonicPlaylistDetail(val playlist: SubsonicPlaylist, val songs: List<SubsonicSong>)

/** Results of `search3`: all three kinds. */
data class SubsonicResults(
    val artists: List<SubsonicArtist>,
    val albums:  List<SubsonicAlbum>,
    val songs:   List<SubsonicSong>
)

data class SubsonicAlbumDetail(val album: SubsonicAlbum, val songs: List<SubsonicSong>)
data class SubsonicArtistDetail(val artist: SubsonicArtist, val albums: List<SubsonicAlbum>)

/** What the server said about itself (from `ping`). */
data class SubsonicServerInfo(val type: String?, val version: String?)

/**
 * A request that didn't work.  [kind] says why, in a way the UI can explain:
 * the server couldn't be reached, it refused the login, or it returned an error.
 */
class SubsonicException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind { NOT_CONFIGURED, NETWORK, AUTH, SERVER }
}

// ── Client ────────────────────────────────────────────────────────────────────

/**
 * Minimal Subsonic API client (Navidrome speaks this).
 *
 * Every API call is blocking (run it on Dispatchers.IO) and throws
 * [SubsonicException] when it fails, so callers can tell "nothing there"
 * apart from "couldn't ask".
 */
object SubsonicClient {

    private const val TAG         = "CarMedia.Subsonic"
    private const val API_VERSION = "1.16.1"
    private const val CLIENT_NAME = "CarMediaPlayer"

    /** getAlbumList2 returns at most 500 per call; we page past that. */
    private const val ALBUM_PAGE_SIZE = 500
    /** Safety stop for paging (500 × 40 = 20,000 albums). */
    private const val MAX_ALBUM_PAGES = 40

    /**
     * Auth params are cached for the current config so that cover-art URLs
     * stay stable across Coil requests (same URL → memory-cached bitmap).
     * Invalidated by [invalidateCache] when settings change.
     */
    @Volatile private var cachedConfig:     SubsonicSettings.Config? = null
    @Volatile private var cachedAuthParams: String                   = ""

    @Synchronized
    fun authParams(cfg: SubsonicSettings.Config): String {
        if (cfg == cachedConfig) return cachedAuthParams
        val salt  = UUID.randomUUID().toString().replace("-", "").take(8)
        val token = md5(cfg.password + salt)
        val auth  = "u=${enc(cfg.username)}&t=$token&s=$salt" +
                    "&v=$API_VERSION&c=$CLIENT_NAME&f=json"
        cachedConfig     = cfg
        cachedAuthParams = auth
        return auth
    }

    /** Call after saving new settings so the next request regenerates auth. */
    fun invalidateCache() {
        cachedConfig     = null
        cachedAuthParams = ""
    }

    // ── URL builders ─────────────────────────────────────────────────────────

    fun streamUrl(cfg: SubsonicSettings.Config, songId: String): String =
        "${cfg.url}/rest/stream?id=${enc(songId)}&${authParams(cfg)}"

    fun coverArtUrl(cfg: SubsonicSettings.Config, artId: String, size: Int = 300): String =
        "${cfg.url}/rest/getCoverArt?id=${enc(artId)}&size=$size&${authParams(cfg)}"

    // ── API calls (blocking — run on Dispatchers.IO) ─────────────────────────

    fun ping(cfg: SubsonicSettings.Config): SubsonicServerInfo {
        val resp = fetch(cfg, "ping")
        return SubsonicServerInfo(
            type    = resp.optString("type").takeIf { it.isNotBlank() },
            version = resp.optString("serverVersion").takeIf { it.isNotBlank() }
        )
    }

    /** True if the server answers (used to wait for the network at startup). */
    fun isReachable(cfg: SubsonicSettings.Config): Boolean =
        try { ping(cfg); true } catch (_: SubsonicException) { false }

    fun getArtists(cfg: SubsonicSettings.Config): List<SubsonicArtist> {
        val indexes = fetch(cfg, "getArtists").optJSONObject("artists")?.optJSONArray("index")
            ?: return emptyList()
        val result = mutableListOf<SubsonicArtist>()
        for (i in 0 until indexes.length()) {
            result += indexes.optJSONObject(i)?.optJSONArray("artist").mapObjects(::parseArtist)
        }
        return result
    }

    fun getArtist(cfg: SubsonicSettings.Config, artistId: String): SubsonicArtistDetail {
        val a = fetch(cfg, "getArtist", "id" to artistId).optJSONObject("artist")
            ?: throw SubsonicException(SubsonicException.Kind.SERVER, "Artist not found")
        return SubsonicArtistDetail(parseArtist(a), a.optJSONArray("album").mapObjects(::parseAlbum))
    }

    /**
     * One page of `getAlbumList2`.  [type] is any Subsonic list type —
     * alphabeticalByName, newest, recent, frequent, random, starred, byGenre …
     * [genre] is required when type is byGenre.
     */
    fun getAlbumList(
        cfg:    SubsonicSettings.Config,
        type:   String  = "alphabeticalByName",
        size:   Int     = ALBUM_PAGE_SIZE,
        offset: Int     = 0,
        genre:  String? = null
    ): List<SubsonicAlbum> {
        val params = mutableListOf("type" to type, "size" to size.toString(), "offset" to offset.toString())
        if (genre != null) params += "genre" to genre
        return fetch(cfg, "getAlbumList2", *params.toTypedArray())
            .optJSONObject("albumList2")?.optJSONArray("album").mapObjects(::parseAlbum)
    }

    /**
     * Every album of the given list type, paging through `getAlbumList2`
     * 500 at a time (the API's per-call maximum), so large libraries aren't
     * cut off at 500 albums.
     */
    fun getAllAlbums(
        cfg:   SubsonicSettings.Config,
        type:  String  = "alphabeticalByName",
        genre: String? = null
    ): List<SubsonicAlbum> {
        val all = mutableListOf<SubsonicAlbum>()
        for (page in 0 until MAX_ALBUM_PAGES) {
            val batch = getAlbumList(cfg, type, ALBUM_PAGE_SIZE, page * ALBUM_PAGE_SIZE, genre)
            all += batch
            if (batch.size < ALBUM_PAGE_SIZE) break
        }
        return all
    }

    fun getAlbum(cfg: SubsonicSettings.Config, albumId: String): SubsonicAlbumDetail {
        val a = fetch(cfg, "getAlbum", "id" to albumId).optJSONObject("album")
            ?: throw SubsonicException(SubsonicException.Kind.SERVER, "Album not found")
        return SubsonicAlbumDetail(parseAlbum(a), a.optJSONArray("song").mapObjects(::parseSong))
    }

    fun getSong(cfg: SubsonicSettings.Config, songId: String): SubsonicSong {
        val s = fetch(cfg, "getSong", "id" to songId).optJSONObject("song")
            ?: throw SubsonicException(SubsonicException.Kind.SERVER, "Song not found")
        return parseSong(s)
    }

    fun getGenres(cfg: SubsonicSettings.Config): List<SubsonicGenre> =
        fetch(cfg, "getGenres").optJSONObject("genres")?.optJSONArray("genre").mapObjects { g ->
            SubsonicGenre(
                name       = g.optString("value", ""),
                albumCount = g.optInt("albumCount", 0),
                songCount  = g.optInt("songCount", 0)
            )
        }.filter { it.name.isNotBlank() }

    fun getPlaylists(cfg: SubsonicSettings.Config): List<SubsonicPlaylist> =
        fetch(cfg, "getPlaylists").optJSONObject("playlists")?.optJSONArray("playlist")
            .mapObjects(::parsePlaylist)

    fun getPlaylist(cfg: SubsonicSettings.Config, playlistId: String): SubsonicPlaylistDetail {
        val p = fetch(cfg, "getPlaylist", "id" to playlistId).optJSONObject("playlist")
            ?: throw SubsonicException(SubsonicException.Kind.SERVER, "Playlist not found")
        val playlist = parsePlaylist(p)
        return SubsonicPlaylistDetail(playlist, p.optJSONArray("entry").mapObjects(::parseSong))
    }

    /** Streams the original song file to [output] for a removable-drive copy. */
    fun download(cfg: SubsonicSettings.Config, songId: String, output: OutputStream): Long {
        if (!cfg.isValid) throw SubsonicException(SubsonicException.Kind.NOT_CONFIGURED, "Server not set up")
        val url = "${cfg.url}/rest/download?${authParams(cfg)}&id=${enc(songId)}"
        val connection = try { URL(url).openConnection() as HttpURLConnection }
        catch (_: Exception) { throw SubsonicException(SubsonicException.Kind.NETWORK, "Couldn't reach the server") }
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        connection.requestMethod = "GET"
        try {
            if (connection.responseCode == 401 || connection.responseCode == 403) {
                throw SubsonicException(SubsonicException.Kind.AUTH, "The server refused the username or password")
            }
            val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val count = connection.inputStream.use { input ->
                if (type == "application/json" || type == "application/xml" || type.startsWith("text/")) {
                    val body = input.bufferedReader().readText()
                    val root = runCatching { JSONObject(body).getJSONObject("subsonic-response") }.getOrNull()
                    val error = root?.optJSONObject("error")
                    if (error != null) throwSubsonicError(error)
                    throw SubsonicException(SubsonicException.Kind.SERVER, "The server returned an invalid audio file")
                }
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    total += n
                }
                total
            }
            if (count == 0L) throw SubsonicException(SubsonicException.Kind.SERVER, "The server returned an empty audio file")
            output.flush()
            return count
        } catch (e: SubsonicException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.javaClass.simpleName}")
            throw SubsonicException(SubsonicException.Kind.NETWORK, "Couldn't download the song")
        } finally {
            connection.disconnect()
        }
    }

    /** Random songs from the whole library, or one genre (Navidrome caps [size] at 500). */
    fun getRandomSongs(cfg: SubsonicSettings.Config, size: Int, genre: String? = null): List<SubsonicSong> {
        val params = mutableListOf("size" to size.toString())
        if (genre != null) params += "genre" to genre
        return fetch(cfg, "getRandomSongs", *params.toTypedArray())
            .optJSONObject("randomSongs")?.optJSONArray("song").mapObjects(::parseSong)
    }

    fun search(cfg: SubsonicSettings.Config, query: String): SubsonicResults =
        parseResults(
            fetch(cfg, "search3",
                "query" to query, "artistCount" to "12", "albumCount" to "24", "songCount" to "50"
            ).optJSONObject("searchResult3")
        )

    /**
     * Tells the server a song is playing ([submission] = false) or was played
     * ([submission] = true, which updates play counts and "recently played").
     */
    fun scrobble(cfg: SubsonicSettings.Config, songId: String, submission: Boolean, timeMs: Long) {
        fetch(cfg, "scrobble",
            "id" to songId, "submission" to submission.toString(), "time" to timeMs.toString())
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun JSONObject.str(key: String): String? = optString(key).takeIf { it.isNotBlank() }

    private fun parseArtist(a: JSONObject) = SubsonicArtist(
        id         = a.getString("id"),
        name       = a.optString("name", "?"),
        coverArt   = a.str("coverArt"),
        albumCount = a.optInt("albumCount", 0)
    )

    private fun parseAlbum(a: JSONObject) = SubsonicAlbum(
        id          = a.getString("id"),
        name        = a.str("name") ?: a.str("title") ?: "?",
        artist      = a.str("artist"),
        coverArt    = a.str("coverArt"),
        artistId    = a.str("artistId"),
        year        = a.optInt("year", 0),
        songCount   = a.optInt("songCount", 0),
        durationSec = a.optInt("duration", 0)
    )

    private fun parsePlaylist(p: JSONObject) = SubsonicPlaylist(
        id = p.getString("id"),
        name = p.str("name") ?: "?",
        owner = p.str("owner"),
        comment = p.str("comment"),
        songCount = p.optInt("songCount", 0),
        durationSec = p.optInt("duration", 0),
        changed = p.str("changed"),
        coverArt = p.str("coverArt")
    )

    private fun parseSong(s: JSONObject) = SubsonicSong(
        id         = s.getString("id"),
        title      = s.optString("title", "?"),
        artist     = s.str("artist"),
        album      = s.str("album"),
        durationMs = s.optLong("duration", 0L) * 1_000L,
        coverArt   = s.str("coverArt"),
        albumId    = s.str("albumId"),
        artistId   = s.str("artistId"),
        track      = s.optInt("track", 0),
        disc       = s.optInt("discNumber", 0),
        suffix     = s.str("suffix"),
        sizeBytes  = s.optLong("size", 0L)
    )

    private fun parseResults(o: JSONObject?) = SubsonicResults(
        artists = o?.optJSONArray("artist").mapObjects(::parseArtist),
        albums  = o?.optJSONArray("album").mapObjects(::parseAlbum),
        songs   = o?.optJSONArray("song").mapObjects(::parseSong)
    )

    /** Maps each object in a (possibly missing) JSON array, skipping malformed entries. */
    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            try { out += transform(o) } catch (_: Exception) { /* missing id etc. */ }
        }
        return out
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    /**
     * Calls [endpoint] and returns the inner `subsonic-response` object.
     * Throws [SubsonicException] if the server can't be reached, rejects the
     * login, or returns an error.
     */
    private fun fetch(
        cfg:      SubsonicSettings.Config,
        endpoint: String,
        vararg params: Pair<String, String>
    ): JSONObject {
        if (!cfg.isValid) throw SubsonicException(SubsonicException.Kind.NOT_CONFIGURED, "Server not set up")
        val query = params.joinToString("") { (k, v) -> "&$k=${enc(v)}" }
        val url   = "${cfg.url}/rest/$endpoint?${authParams(cfg)}$query"

        val body = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout    = 20_000
            conn.requestMethod  = "GET"
            try {
                if (conn.responseCode == 401 || conn.responseCode == 403) {
                    throw SubsonicException(SubsonicException.Kind.AUTH, "The server refused the username or password")
                }
                // Closed, not just read: the reply is gzipped, and an unclosed
                // stream keeps its decompressor until the garbage collector
                // gets to it ("A resource failed to call end" in the log).
                conn.inputStream.use { it.bufferedReader().readText() }
            } finally {
                conn.disconnect()
            }
        } catch (e: SubsonicException) {
            throw e
        } catch (e: Exception) {
            // Exception messages can contain the full URL (with the auth
            // token), so only the exception type is logged.
            Log.w(TAG, "$endpoint failed: ${e.javaClass.simpleName}")
            throw SubsonicException(SubsonicException.Kind.NETWORK, "Couldn't reach the server")
        }

        val root = try {
            JSONObject(body).getJSONObject("subsonic-response")
        } catch (_: Exception) {
            throw SubsonicException(SubsonicException.Kind.SERVER, "That address isn't a Subsonic or Navidrome server")
        }
        if (root.optString("status") == "ok") return root

        val error   = root.optJSONObject("error")
        val code    = error?.optInt("code", 0) ?: 0
        val message = error?.optString("message").orEmpty()
        Log.w(TAG, "$endpoint: error $code $message")
        // 40 = wrong username or password, 41 = token auth not supported, 50 = not authorised
        val kind = if (code == 40 || code == 41 || code == 50) SubsonicException.Kind.AUTH
                   else SubsonicException.Kind.SERVER
        throw SubsonicException(kind, message.ifBlank { "The server returned error $code" })
    }

    private fun throwSubsonicError(error: JSONObject): Nothing {
        val code = error.optInt("code", 0)
        val message = error.optString("message").orEmpty()
        val kind = if (code == 40 || code == 41 || code == 50) SubsonicException.Kind.AUTH
                   else SubsonicException.Kind.SERVER
        throw SubsonicException(kind, message.ifBlank { "The server returned error $code" })
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
