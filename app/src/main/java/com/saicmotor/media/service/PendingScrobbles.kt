package com.saicmotor.media.service

import android.content.Context
import com.saicmotor.media.data.Storage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plays that couldn't be reported to Navidrome (no signal at the time), kept
 * — across restarts — until the server can be reached, then sent with the
 * time they were played.
 */
class PendingScrobbles(private val context: Context) {

    data class Play(val songId: String, val timeMs: Long)

    private val prefs get() = Storage.prefs(context, PREFS)

    @Synchronized
    fun add(play: Play) {
        val list = all().toMutableList()
        if (play in list) return
        list += play
        save(list.takeLast(MAX))
    }

    @Synchronized
    fun all(): List<Play> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            array.getJSONObject(i).let { Play(it.getString("id"), it.getLong("t")) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun remove(play: Play) = save(all() - play)

    private fun save(list: List<Play>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.songId).put("t", it.timeMs)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "pending_scrobbles"
        const val KEY   = "plays"
        /** A long drive with no signal at all. */
        const val MAX   = 300
    }
}
