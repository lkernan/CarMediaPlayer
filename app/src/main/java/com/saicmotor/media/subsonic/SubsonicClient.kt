package com.saicmotor.media.subsonic

import com.saicmotor.media.data.SubsonicSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

// ── Data models ───────────────────────────────────────────────────────────────

data class SubsonicArtist(
    val id:       String,
    val name:     String,
    val coverArt: String?
)

data class SubsonicAlbum(
    val id:       String,
    val name:     String,
    val artist:   String?,
    val coverArt: String?
)

data class SubsonicSong(
    val id:         String,
    val title:      String,
    val artist:     String?,
    val album:      String?,
    val durationMs: Long,       // milliseconds
    val coverArt:   String?
)

// ── Client ────────────────────────────────────────────────────────────────────

object SubsonicClient {

    private const val API_VERSION = "1.16.1"
    private const val CLIENT_NAME = "CarMediaPlayer"

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

    fun getArtists(cfg: SubsonicSettings.Config): List<SubsonicArtist> {
        val resp = fetch("${cfg.url}/rest/getArtists?${authParams(cfg)}") ?: return emptyList()
        val indexes = resp.optJSONObject("artists")?.optJSONArray("index") ?: return emptyList()
        val result = mutableListOf<SubsonicArtist>()
        for (i in 0 until indexes.length()) {
            val artists = indexes.getJSONObject(i).optJSONArray("artist") ?: continue
            for (j in 0 until artists.length()) {
                val a = artists.getJSONObject(j)
                result += SubsonicArtist(
                    id       = a.getString("id"),
                    name     = a.optString("name", "?"),
                    coverArt = a.optString("coverArt").takeIf { it.isNotBlank() }
                )
            }
        }
        return result
    }

    /** Returns the albums for a specific artist. */
    fun getArtist(cfg: SubsonicSettings.Config, artistId: String): List<SubsonicAlbum> {
        val resp = fetch("${cfg.url}/rest/getArtist?id=${enc(artistId)}&${authParams(cfg)}")
            ?: return emptyList()
        val albumArr = resp.optJSONObject("artist")?.optJSONArray("album") ?: return emptyList()
        return (0 until albumArr.length()).map { i ->
            val a = albumArr.getJSONObject(i)
            SubsonicAlbum(
                id       = a.getString("id"),
                name     = a.optString("name", "?"),
                artist   = a.optString("artist").takeIf { it.isNotBlank() },
                coverArt = a.optString("coverArt").takeIf { it.isNotBlank() }
            )
        }
    }

    /** Returns albums from the server (alphabetical by name, up to [size]). */
    fun getAlbumList(
        cfg:  SubsonicSettings.Config,
        type: String = "alphabeticalByName",
        size: Int    = 500
    ): List<SubsonicAlbum> {
        val resp = fetch(
            "${cfg.url}/rest/getAlbumList2?type=$type&size=$size&${authParams(cfg)}"
        ) ?: return emptyList()
        val list = resp.optJSONObject("albumList2")?.optJSONArray("album") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val a = list.getJSONObject(i)
            SubsonicAlbum(
                id       = a.getString("id"),
                name     = a.optString("name", "?"),
                artist   = a.optString("artist").takeIf { it.isNotBlank() },
                coverArt = a.optString("coverArt").takeIf { it.isNotBlank() }
            )
        }
    }

    /** Returns the tracks in a specific album. */
    fun getAlbum(cfg: SubsonicSettings.Config, albumId: String): List<SubsonicSong> {
        val resp = fetch("${cfg.url}/rest/getAlbum?id=${enc(albumId)}&${authParams(cfg)}")
            ?: return emptyList()
        val songArr = resp.optJSONObject("album")?.optJSONArray("song") ?: return emptyList()
        return (0 until songArr.length()).map { i ->
            val s = songArr.getJSONObject(i)
            SubsonicSong(
                id         = s.getString("id"),
                title      = s.optString("title", "?"),
                artist     = s.optString("artist").takeIf { it.isNotBlank() },
                album      = s.optString("album").takeIf  { it.isNotBlank() },
                durationMs = s.optLong("duration", 0L) * 1_000L,
                coverArt   = s.optString("coverArt").takeIf { it.isNotBlank() }
            )
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Makes a GET request and returns the inner `subsonic-response` object
     * if status == "ok", or null on error / non-ok status.
     */
    private fun fetch(url: String): JSONObject? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout    = 15_000
        conn.requestMethod  = "GET"
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val root = JSONObject(body).getJSONObject("subsonic-response")
        if (root.optString("status") == "ok") root else null
    } catch (_: Exception) {
        null
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
