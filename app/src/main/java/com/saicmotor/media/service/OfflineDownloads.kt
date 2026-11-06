package com.saicmotor.media.service

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.saicmotor.media.data.OfflinePlaylistStore
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.subsonic.SubsonicClient
import com.saicmotor.media.subsonic.SubsonicPlaylistDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Downloads every track in a Subsonic playlist into the protected player cache. */
object OfflineDownloads {
    suspend fun savePlaylist(
        context: Context,
        detail: SubsonicPlaylistDetail,
        onProgress: suspend (done: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val cfg = SubsonicSettings.load(app).takeIf { it.isValid }
            ?: error("The Subsonic server is not configured")
        val ids = detail.songs.map { MediaService.songMediaId(it.id) }.toSet()
        val cacheDirectory = com.saicmotor.media.data.Storage.context(app).cacheDir
        MediaCache.beginOfflineDownload(ids)
        try {
            detail.songs.forEachIndexed { index, song ->
                currentCoroutineContext().ensureActive()
                val mediaId = MediaService.songMediaId(song.id)
                if (!MediaCache.isCached(app, mediaId)) {
                    val available = StatFs(cacheDirectory.absolutePath).availableBytes
                    val reserve = 128L * 1024 * 1024
                    val needed = if (song.sizeBytes > 0) song.sizeBytes else 64L * 1024 * 1024
                    if (available < needed + reserve) {
                        error("Not enough free space to save this playlist safely")
                    }
                    MediaCache.precache(app, mediaId, Uri.parse(SubsonicClient.streamUrl(cfg, song.id))) { }
                }
                onProgress(index + 1, detail.songs.size)
            }
            if (detail.songs.any { !MediaCache.isCached(app, MediaService.songMediaId(it.id)) }) {
                error("The download could not be completed with the available cache space")
            }
            OfflinePlaylistStore.save(app, detail)
            MediaCache.refreshPinned(app)
        } finally {
            MediaCache.endOfflineDownload(ids)
        }
    }

    fun removePlaylist(context: Context, playlistId: String) {
        OfflinePlaylistStore.remove(context.applicationContext, playlistId)
        MediaCache.refreshPinned(context.applicationContext)
    }
}
