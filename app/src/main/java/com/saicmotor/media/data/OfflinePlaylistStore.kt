package com.saicmotor.media.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.saicmotor.media.subsonic.SubsonicPlaylist
import com.saicmotor.media.subsonic.SubsonicPlaylistDetail
import com.saicmotor.media.subsonic.SubsonicSong

/** Metadata for playlists whose complete audio has been downloaded to the car. */
object OfflinePlaylistStore {
    private const val PREFS = "offline_playlists"
    private const val KEY = "saved"
    private val lock = Any()

    fun all(context: Context): List<SubsonicPlaylist> = synchronized(lock) {
        read(context).mapNotNull { parsePlaylist(it.optJSONObject("playlist")) }
    }

    fun get(context: Context, id: String): SubsonicPlaylistDetail? = synchronized(lock) {
        read(context).firstOrNull { it.optJSONObject("playlist")?.optString("id") == id }
            ?.let(::parseDetail)
    }

    fun isSaved(context: Context, id: String): Boolean = get(context, id) != null

    fun save(context: Context, detail: SubsonicPlaylistDetail) = synchronized(lock) {
        val values = read(context).filterNot { it.optJSONObject("playlist")?.optString("id") == detail.playlist.id }
            .toMutableList()
        values += encode(detail)
        write(context, values)
    }

    fun remove(context: Context, id: String) = synchronized(lock) {
        write(context, read(context).filterNot { it.optJSONObject("playlist")?.optString("id") == id })
    }

    fun pinnedSongIds(context: Context): Set<String> = synchronized(lock) {
        read(context).flatMap { item ->
            item.optJSONArray("songs").objects().mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
        }.toSet()
    }

    private fun read(context: Context): List<JSONObject> = try {
        JSONArray(Storage.prefs(context, PREFS).getString(KEY, "[]")).objects()
    } catch (_: Exception) { emptyList() }

    private fun write(context: Context, values: List<JSONObject>) {
        Storage.prefs(context, PREFS).edit().putString(KEY, JSONArray(values).toString()).apply()
    }

    private fun encode(detail: SubsonicPlaylistDetail) = JSONObject().apply {
        put("playlist", JSONObject().apply {
            put("id", detail.playlist.id); put("name", detail.playlist.name)
            putNullable("owner", detail.playlist.owner); putNullable("comment", detail.playlist.comment)
            put("songCount", detail.playlist.songCount); put("durationSec", detail.playlist.durationSec)
            putNullable("changed", detail.playlist.changed); putNullable("coverArt", detail.playlist.coverArt)
        })
        put("songs", JSONArray().apply { detail.songs.forEach { s -> put(JSONObject().apply {
            put("id", s.id); put("title", s.title); putNullable("artist", s.artist); putNullable("album", s.album)
            put("durationMs", s.durationMs); putNullable("coverArt", s.coverArt)
            putNullable("albumId", s.albumId); putNullable("artistId", s.artistId)
            put("track", s.track); put("disc", s.disc); putNullable("suffix", s.suffix); put("sizeBytes", s.sizeBytes)
        }) } })
    }

    private fun JSONObject.putNullable(key: String, value: String?) { if (value == null) put(key, JSONObject.NULL) else put(key, value) }

    private fun parseDetail(value: JSONObject): SubsonicPlaylistDetail {
        val playlist = parsePlaylist(value.optJSONObject("playlist")) ?: error("Missing playlist")
        val songs = value.optJSONArray("songs").objects().mapNotNull { s ->
            s.optString("id").takeIf(String::isNotBlank)?.let { id -> SubsonicSong(
                id = id, title = s.optString("title", "?"), artist = s.stringOrNull("artist"), album = s.stringOrNull("album"),
                durationMs = s.optLong("durationMs"), coverArt = s.stringOrNull("coverArt"),
                albumId = s.stringOrNull("albumId"), artistId = s.stringOrNull("artistId"),
                track = s.optInt("track"), disc = s.optInt("disc"),
                suffix = s.stringOrNull("suffix"), sizeBytes = s.optLong("sizeBytes")
            ) }
        }
        return SubsonicPlaylistDetail(playlist, songs)
    }

    private fun parsePlaylist(p: JSONObject?): SubsonicPlaylist? = p?.optString("id")
        ?.takeIf(String::isNotBlank)?.let { id -> SubsonicPlaylist(
            id = id, name = p.optString("name", "?"), owner = p.stringOrNull("owner"),
            comment = p.stringOrNull("comment"), songCount = p.optInt("songCount"),
            durationSec = p.optInt("durationSec"), changed = p.stringOrNull("changed"),
            coverArt = p.stringOrNull("coverArt")
        ) }

    private fun JSONObject.stringOrNull(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
        (0 until length()).mapNotNull { optJSONObject(it) }
}
