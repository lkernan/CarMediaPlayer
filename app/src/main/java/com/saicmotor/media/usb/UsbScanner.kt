package com.saicmotor.media.usb

import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/**
 * Scans a USB drive's filesystem directly for audio files, bypassing
 * Android's MediaStore (which is unreliable on custom AOSP car head units).
 *
 * Results are cached in memory after the first scan of each root path.
 * Call [invalidate] when a drive is unmounted so the next [scan] call
 * reads fresh data from the new drive.
 *
 * Scanning is synchronous and potentially slow on first call — always
 * invoke from a background thread (e.g. Dispatchers.IO).
 */
object UsbScanner {

    data class Track(
        val path:        String,
        val title:       String,
        val artist:      String,
        val album:       String,
        val trackNumber: Int,
        val durationMs:  Long,
        val artUri:      Uri?   // file:// URI to folder art, or null
    )

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "opus",
        "wav", "wma", "ape", "alac", "aiff", "aif"
    )

    // Cover-art filenames to search for in each album directory, in priority order
    private val ART_FILENAMES = listOf(
        "cover.jpg",  "cover.jpeg",  "cover.png",
        "folder.jpg", "folder.jpeg", "folder.png",
        "album.jpg",  "album.jpeg",  "album.png",
        "front.jpg",  "front.jpeg",  "front.png"
    )

    @Volatile private var cachedRootPath: String?     = null
    @Volatile private var cachedTracks:   List<Track> = emptyList()

    /**
     * Returns all audio tracks found under [rootPath], reading tags from each file.
     * Results are cached — repeated calls with the same [rootPath] are free.
     */
    @Synchronized
    fun scan(rootPath: String): List<Track> {
        if (cachedRootPath == rootPath) return cachedTracks

        val tracks = mutableListOf<Track>()
        File(rootPath).walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .forEach { file -> readTrack(file)?.let { tracks += it } }

        cachedTracks   = tracks
        cachedRootPath = rootPath
        return tracks
    }

    /** Clear the cache. Call this when a USB drive is unmounted or ejected. */
    @Synchronized
    fun invalidate() {
        cachedRootPath = null
        cachedTracks   = emptyList()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun readTrack(file: File): Track? = try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(file.absolutePath)

        val title  = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                         ?.takeIf { it.isNotBlank() }
                     ?: file.nameWithoutExtension
        val artist = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                         ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                         ?.takeIf { it.isNotBlank() }
                     ?: "Unknown Artist"
        val album  = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                         ?.takeIf { it.isNotBlank() }
                     ?: "Unknown Album"
        val dur    = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                         ?.toLongOrNull() ?: 0L
        val track  = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                         ?.substringBefore("/")?.toIntOrNull() ?: 0

        mmr.release()

        Track(
            path        = file.absolutePath,
            title       = title,
            artist      = artist,
            album       = album,
            trackNumber = track,
            durationMs  = dur,
            artUri      = folderArt(file.parentFile)
        )
    } catch (_: Exception) { null }

    /**
     * Looks for a recognisable cover-art file in [dir].
     * Returns a [Uri] pointing to the first match, or null if none found.
     */
    private fun folderArt(dir: File?): Uri? =
        dir?.let { d ->
            ART_FILENAMES
                .map { File(d, it) }
                .firstOrNull { it.exists() }
                ?.let { Uri.fromFile(it) }
        }
}
