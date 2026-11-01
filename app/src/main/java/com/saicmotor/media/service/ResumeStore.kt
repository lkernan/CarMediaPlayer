package com.saicmotor.media.service

import android.content.Context
import com.saicmotor.media.data.Storage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists just enough about the play queue to rebuild it after the head
 * unit reboots, without storing the queue itself:
 *
 *  - a queue started from a browse list (album, playlist, artist's tracks…)
 *    is saved as the browse node it came from, and rebuilt by loading that
 *    node again;
 *  - a whole-library shuffle is saved as "library shuffle" and rebuilt with a
 *    fresh shuffle, starting with the track that was playing;
 *  - plus the current track and position.
 *
 * The upcoming songs themselves are also saved ([saveSnapshot]), so the
 * queue can be put back the moment the car starts — before the mobile
 * connection is up — and playback starts as soon as the song can be fetched
 * (straight away, if it's in the song cache).
 *
 * Car shutdown is just power loss, so there is no "on shutdown" hook: the
 * position is written every few seconds while playing.
 */
class ResumeStore(private val context: Context) {

    enum class QueueKind { BROWSE, LIBRARY, SINGLE }

    data class SavedItem(
        val mediaId:    String,
        val title:      String,
        val artist:     String,
        val album:      String,
        val artUri:     String?,
        val albumId:    String? = null,
        val artistId:   String? = null,
        val durationMs: Long    = -1L
    )

    data class State(
        /** MediaService.ONLINE_ROOT (older versions also saved USB sources). */
        val source:       String,
        val kind:         QueueKind,
        /** Browse node the queue was built from (BROWSE only). */
        val parentId:     String?,
        val item:         SavedItem?,
        val positionMs:   Long
    )

    // Device-protected storage, so it can be read the moment the head unit boots (see Storage).
    // Looked up each time: after an update the file can be moved in under us (Storage.migrate).
    private val prefs get() = Storage.prefs(context, PREFS)
    private val snapshotFile = File(Storage.context(context).filesDir, SNAPSHOT_FILE)

    fun load(): State? {
        val source = prefs.getString(K_SOURCE, null) ?: return null
        val kind   = QueueKind.values().firstOrNull { it.name == prefs.getString(K_KIND, null) }
            ?: QueueKind.SINGLE
        return State(
            source           = source,
            kind             = kind,
            parentId         = prefs.getString(K_PARENT, null),
            item             = prefs.getString(K_ITEM, null)?.let(::parseItem),
            positionMs       = prefs.getLong(K_POSITION, 0L)
        )
    }

    fun saveQueue(source: String, kind: QueueKind, parentId: String?) {
        prefs.edit()
            .putString(K_SOURCE, source)
            .putString(K_KIND, kind.name)
            .putString(K_PARENT, parentId)
            .apply()
    }

    fun saveItem(item: SavedItem, positionMs: Long) {
        prefs.edit()
            .putString(K_ITEM, itemToJson(item))
            .putLong(K_POSITION, positionMs)
            .apply()
    }

    fun savePosition(positionMs: Long) {
        prefs.edit().putLong(K_POSITION, positionMs).apply()
    }

    /**
     * Saves the queue in play order (from a little before the current song).
     * Blocking file write: call it off the main thread.
     */
    fun saveSnapshot(items: List<SavedItem>) {
        try {
            val array = JSONArray()
            items.forEach { array.put(itemToObject(it)) }
            val tmp = File(snapshotFile.path + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(snapshotFile)
        } catch (_: Exception) {
            // Best effort: without it, start-up just waits for the server instead.
        }
    }

    /** The queue saved by [saveSnapshot], in play order (empty if none).  Blocking file read. */
    fun loadSnapshot(): List<SavedItem> = try {
        val array = JSONArray(snapshotFile.readText())
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::objectToItem) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun itemToJson(i: SavedItem): String = itemToObject(i).toString()

    private fun itemToObject(i: SavedItem): JSONObject = JSONObject().apply {
        put("mediaId",    i.mediaId)
        put("title",      i.title)
        put("artist",     i.artist)
        put("album",      i.album)
        put("artUri",     i.artUri ?: "")
        put("albumId",    i.albumId ?: "")
        put("artistId",   i.artistId ?: "")
        put("durationMs", i.durationMs)
    }

    private fun parseItem(json: String): SavedItem? = try {
        objectToItem(JSONObject(json))
    } catch (_: Exception) {
        null
    }

    private fun objectToItem(o: JSONObject): SavedItem? = try {
        SavedItem(
            mediaId    = o.getString("mediaId"),
            title      = o.optString("title"),
            artist     = o.optString("artist"),
            album      = o.optString("album"),
            artUri     = o.optString("artUri").takeIf { it.isNotBlank() },
            albumId    = o.optString("albumId").takeIf { it.isNotBlank() },
            artistId   = o.optString("artistId").takeIf { it.isNotBlank() },
            durationMs = o.optLong("durationMs", -1L)
        )
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val PREFS           = "resume_state"
        private const val SNAPSHOT_FILE   = "queue_snapshot.json"
        private const val K_SOURCE        = "source"
        private const val K_KIND          = "kind"
        private const val K_PARENT        = "parent_id"
        private const val K_ITEM          = "item"
        private const val K_POSITION      = "position_ms"
    }
}
