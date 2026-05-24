package com.saicmotor.media.usb

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Scans a USB drive's filesystem directly for audio files, bypassing
 * Android's MediaStore (which is unreliable on custom AOSP car head units).
 *
 * ## Caching strategy
 *
 * Tag data is stored in a Room database keyed on `(path, lastModified)`.
 * On every scan the filesystem is walked for directory entries only (cheap),
 * then diffed against the DB:
 *
 *  - **Hit** (path in DB, lastModified matches) → use DB row, no file open
 *  - **Miss** (new file, or lastModified changed) → read with [MediaMetadataRetriever],
 *    write to DB
 *  - **Stale** (DB row whose path no longer exists) → deleted from DB
 *
 * Miss reads are dispatched in parallel on [Dispatchers.IO], so a cold first
 * scan of a large library is significantly faster than the old sequential
 * approach.  On subsequent starts with an unchanged drive the entire scan
 * reduces to a single `SELECT *` query.
 *
 * An in-memory cache layer sits in front of the DB so repeated calls within
 * the same process lifetime (e.g. browsing Artists → Albums → back) are free.
 *
 * ## Initialisation
 *
 * Call [init] once before the first [scan], passing any [Context].  The
 * database is opened against the application context, so a Service context
 * is fine.
 *
 * ## Thread safety
 *
 * [scan] is a `suspend fun` and must be called from a coroutine.  It handles
 * its own dispatcher switching internally.  [invalidate] is safe to call from
 * any thread (e.g. a [android.content.BroadcastReceiver]).
 */
object UsbScanner {

    data class Track(
        val path:        String,
        val title:       String,
        val artist:      String,
        val album:       String,
        val trackNumber: Int,
        val durationMs:  Long,
        val artUri:      Uri?
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

    private lateinit var db: TrackCacheDatabase

    /** Must be called once before [scan].  Safe to call multiple times. */
    fun init(context: Context) {
        if (!::db.isInitialized) {
            db = TrackCacheDatabase.getInstance(context)
        }
    }

    /**
     * Returns all audio tracks found under [rootPath].
     *
     * The first call after a new USB drive is mounted opens only the files
     * that are not already in the on-device cache (or whose modification time
     * changed).  Subsequent calls with the same [rootPath] are served from
     * memory with no IO at all.
     *
     * Always call from a coroutine; this function handles dispatcher switching
     * internally so the caller does not need to wrap it in [withContext].
     */
    suspend fun scan(rootPath: String): List<Track> {
        // In-memory fast path — repeated calls within the same session
        if (cachedRootPath == rootPath) return cachedTracks

        val tracks = doScan(rootPath)
        cachedTracks   = tracks
        cachedRootPath = rootPath
        return tracks
    }

    /**
     * Clears the in-memory cache.  Call this when a drive is unmounted so the
     * next [scan] performs a fresh filesystem walk and DB diff.
     */
    @Synchronized
    fun invalidate() {
        cachedRootPath = null
        cachedTracks   = emptyList()
    }

    // ── Core scan logic ───────────────────────────────────────────────────────

    private suspend fun doScan(rootPath: String): List<Track> = coroutineScope {

        // Step 1 — Walk the filesystem collecting paths and modification times.
        //          This only reads directory entries; no file content is opened.
        val fsFiles: Map<String, Long> = withContext(Dispatchers.IO) {
            File(rootPath).walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                .associate { it.absolutePath to it.lastModified() }
        }

        // Step 2 — Load the entire DB cache as a lookup map.
        val cached: Map<String, TrackCacheEntity> = withContext(Dispatchers.IO) {
            db.trackCacheDao().getAll().associateBy { it.path }
        }

        // Step 3 — Remove DB entries for files that are no longer on the drive.
        val stalePaths = cached.keys - fsFiles.keys
        if (stalePaths.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                db.trackCacheDao().deleteByPaths(stalePaths.toList())
            }
        }

        // Step 4 — Identify files that need a fresh MMR read:
        //          anything not in the DB, or whose lastModified timestamp differs.
        val toScan: Map<String, Long> = fsFiles.filter { (path, modified) ->
            cached[path]?.lastModified != modified
        }

        // Step 5 — Read tags for new/changed files in parallel.
        //          Each async block gets its own MediaMetadataRetriever instance,
        //          so there are no thread-safety issues.  Dispatchers.IO provides
        //          up to 64 concurrent threads, giving a large speedup over the
        //          old sequential approach on first scan or after drive changes.
        val newEntities: List<TrackCacheEntity> = toScan.entries
            .map { (path, modified) ->
                async(Dispatchers.IO) { readTrack(File(path), modified) }
            }
            .awaitAll()
            .filterNotNull()

        if (newEntities.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                db.trackCacheDao().upsertAll(newEntities)
            }
        }

        // Step 6 — Merge the surviving cache hits with newly-read entities.
        val cacheHits = cached.filterKeys { it !in stalePaths && it !in toScan }
        (cacheHits.values + newEntities).map { it.toTrack() }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun readTrack(file: File, lastModified: Long): TrackCacheEntity? = try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(file.absolutePath)

        val title  = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                         ?.let(::fixMojibake)
                         ?.takeIf { it.isNotBlank() })
                     ?: file.nameWithoutExtension
        val artist = ((mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                         ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                         ?.let(::fixMojibake)
                         ?.takeIf { it.isNotBlank() })
                     ?: "Unknown Artist"
        val album  = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                         ?.let(::fixMojibake)
                         ?.takeIf { it.isNotBlank() })
                     ?: "Unknown Album"
        val dur    = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                         ?.toLongOrNull() ?: 0L
        val track  = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                         ?.substringBefore("/")?.toIntOrNull() ?: 0

        mmr.release()

        TrackCacheEntity(
            path         = file.absolutePath,
            lastModified = lastModified,
            title        = title,
            artist       = artist,
            album        = album,
            trackNumber  = track,
            durationMs   = dur,
            artUri       = folderArt(file.parentFile)?.toString()
        )
    } catch (_: Exception) { null }

    /**
     * Repairs the very common case of an ID3v2 frame that declares encoding 0
     * (ISO-8859-1) but actually contains UTF-8 bytes.  [MediaMetadataRetriever]
     * trusts the declared encoding and decodes those bytes via Windows-1252
     * (a Latin-1 superset that maps the C1 range 0x80–0x9F to printable
     * characters like `€` and `™`).  A "right single quotation mark"
     * ('’', UTF-8: E2 80 99) consequently comes out as the trigram `â€™`
     * — and the user sees the `€` and `™` glyphs in track titles.
     *
     * We round-trip the string back to its raw CP1252 bytes (lossless because
     * every CP1252 code point fits in one byte) and attempt a strict UTF-8
     * decode.  If the bytes form valid UTF-8 we adopt the re-decoded string;
     * if not, the original was legitimately CP1252 / Latin-1 and we leave it
     * alone.  Strict-mode decoding rejects almost every legitimate Latin-1
     * string because lone high bytes (e.g. 0xE9 for `é`) are not valid UTF-8
     * lead bytes.
     *
     * After repair we also normalise curly typographic punctuation to ASCII
     * equivalents.  The SAIC head unit's bundled font has no glyphs for
     * U+2018/U+2019/U+201C/U+201D etc. and renders them as '?' boxes, so a
     * track titled "Don't Stop Believin'" would otherwise show as "Don?t
     * Stop Believin?".
     */
    private fun fixMojibake(s: String): String {
        // Fast path: ASCII only — nothing to repair or normalise.
        if (s.all { it.code < 0x80 }) return s

        val repaired = try {
            val bytes = s.toByteArray(CP1252)
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Throwable) {
            s
        }

        return normaliseTypography(repaired)
    }

    private val CP1252: Charset = Charset.forName("windows-1252")

    /**
     * Replaces curly Unicode punctuation with ASCII equivalents.  Keeps glyphs
     * the device's font actually has — losing the typographic curl is a fair
     * trade for not seeing question-mark boxes in the title bar.
     */
    private fun normaliseTypography(s: String): String {
        // Quick check — avoid allocating a StringBuilder for the common case.
        if (s.none { it.isCurlyPunctuation() }) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '‘', '’', '‚', '‛' -> sb.append('\'')           // ‘ ’ ‚ ‛
                '“', '”', '„', '‟' -> sb.append('"')            // “ ” „ ‟
                '–', '—', '―'           -> sb.append('-')            // – — ―
                '…'                                -> sb.append("...")         // …
                '«', '»'                     -> sb.append('"')            // « »
                '′'                                -> sb.append('\'')          // ′
                '″'                                -> sb.append('"')           // ″
                else                                    -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun Char.isCurlyPunctuation(): Boolean = when (this) {
        '‘', '’', '‚', '‛',
        '“', '”', '„', '‟',
        '–', '—', '―',
        '…',
        '«', '»',
        '′', '″' -> true
        else                -> false
    }

    private fun folderArt(dir: File?): Uri? =
        dir?.let { d ->
            ART_FILENAMES
                .map { File(d, it) }
                .firstOrNull { it.exists() }
                ?.let { Uri.fromFile(it) }
        }
}

// Extension kept private to this file — converts a DB entity back to the
// public Track type used throughout the rest of the app.
private fun TrackCacheEntity.toTrack() = UsbScanner.Track(
    path        = path,
    title       = title,
    artist      = artist,
    album       = album,
    trackNumber = trackNumber,
    durationMs  = durationMs,
    artUri      = artUri?.let { Uri.parse(it) }
)
