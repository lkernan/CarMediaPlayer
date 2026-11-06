package com.saicmotor.media.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.database.Cursor
import android.webkit.MimeTypeMap
import com.saicmotor.media.data.Storage
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.subsonic.SubsonicClient
import com.saicmotor.media.subsonic.SubsonicPlaylistDetail
import com.saicmotor.media.subsonic.SubsonicSong
import org.json.JSONArray
import org.json.JSONObject

/** Copies a playlist and its original audio files to a folder on removable storage. */
object PlaylistUsbSync {
    private const val PREFS = "usb_playlist_sync"
    private const val TREE = "tree_uri"
    private const val APP_FOLDER = "CarMediaPlayer"
    private const val STAGING = ".sync-staging"
    private const val MANIFEST = "playlist.json"

    fun savedTree(context: Context): Uri? = Storage.prefs(context, PREFS).getString(TREE, null)?.let(Uri::parse)

    fun rememberTree(context: Context, uri: Uri) {
        Storage.prefs(context, PREFS).edit().putString(TREE, uri.toString()).apply()
    }

    fun hasWriteAccess(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    /** Called off the main thread. Reports completed tracks after each copy. */
    fun sync(context: Context, tree: Uri, detail: SubsonicPlaylistDetail, onProgress: (Int, Int) -> Unit) {
        val app = context.applicationContext
        val resolver = app.contentResolver
        val cfg = SubsonicSettings.load(context).takeIf { it.isValid }
            ?: error("The Subsonic server is not configured")
        require(hasWriteAccess(app, tree)) {
            "USB folder access has expired. Choose the USB folder again."
        }
        val root = documentUri(tree, DocumentsContract.getTreeDocumentId(tree))
        val base = folder(app, tree, root, APP_FOLDER)
        val playlistFolderName = "${safe(detail.playlist.name, 48)}-${safe(detail.playlist.id, 24)}"
        val target = folder(app, tree, base, playlistFolderName)
        val staging = folder(app, tree, target, STAGING)
        children(app, tree, staging).forEach { runCatching { DocumentsContract.deleteDocument(resolver, it.uri) } }
        val oldManifest = readJson(app, tree, target, MANIFEST)
        val oldNames = oldManifest?.optJSONArray("tracks").objects().associate { row ->
            row.optString("id") to row.optString("file")
        }
        val ready = LinkedHashMap<String, String>()
        val needPublish = mutableSetOf<String>()

        detail.songs.forEachIndexed { index, song ->
            val name = trackName(song, index)
            val existing = find(app, tree, target, name)
            if (song.sizeBytes > 0 && existing?.size == song.sizeBytes && oldNames[song.id] == name) {
                ready[song.id] = name
            } else {
                val stagedName = "${safe(song.id, 80)}.${safeExtension(song.suffix)}.part"
                find(app, tree, staging, stagedName)?.let { DocumentsContract.deleteDocument(resolver, it.uri) }
                val staged = DocumentsContract.createDocument(resolver, staging, audioMime(song.suffix), stagedName)
                    ?: error("Could not create a file on the USB drive")
                try {
                    val output = resolver.openOutputStream(staged, "w") ?: error("Could not write to the USB drive")
                    output.use { SubsonicClient.download(cfg, song.id, it) }
                } catch (e: Exception) {
                    runCatching { DocumentsContract.deleteDocument(resolver, staged) }
                    throw e
                }
                ready[song.id] = name
                needPublish += song.id
            }
            onProgress(index + 1, detail.songs.size)
        }

        // Publish staged files only after every song was downloaded successfully.
        detail.songs.forEach { song ->
            if (song.id !in needPublish) return@forEach
            val name = ready.getValue(song.id)
            val stagedName = "${safe(song.id, 80)}.${safeExtension(song.suffix)}.part"
            val staged = find(app, tree, staging, stagedName) ?: error("A staged song is missing")
            find(app, tree, target, name)?.let { DocumentsContract.deleteDocument(resolver, it.uri) }
            val renamed = runCatching { DocumentsContract.renameDocument(resolver, staged.uri, name) }.getOrNull()
            if (renamed == null) {
                val final = DocumentsContract.createDocument(resolver, target, audioMime(song.suffix), name)
                    ?: error("Could not publish a song to the USB drive")
                val input = resolver.openInputStream(staged.uri) ?: error("Could not read a staged song")
                val output = resolver.openOutputStream(final, "w") ?: error("Could not publish a song")
                input.use { source -> output.use { source.copyTo(it) } }
                DocumentsContract.deleteDocument(resolver, staged.uri)
            }
        }

        val playlistFile = "playlist.m3u8"
        writeText(app, tree, target, playlistFile, buildM3u(detail, ready))
        val currentNames = ready.values.toSet()
        val manifest = JSONObject().apply {
            put("playlistId", detail.playlist.id)
            put("playlistName", detail.playlist.name)
            put("syncedAt", System.currentTimeMillis())
            put("tracks", JSONArray().apply {
                detail.songs.forEach { song -> put(JSONObject().apply {
                    put("id", song.id); put("file", ready.getValue(song.id)); put("size", song.sizeBytes)
                }) }
            })
        }
        writeText(app, tree, target, MANIFEST, manifest.toString(2))
        oldNames.values.filter { it.isNotBlank() && it !in currentNames && it != playlistFile && it != MANIFEST }
            .distinct().forEach { stale -> find(app, tree, target, stale)?.let { DocumentsContract.deleteDocument(resolver, it.uri) } }
        children(app, tree, staging).forEach { runCatching { DocumentsContract.deleteDocument(resolver, it.uri) } }
    }

    private data class Child(val uri: Uri, val name: String, val size: Long)

    private fun folder(context: Context, tree: Uri, parent: Uri, name: String): Uri {
        find(context, tree, parent, name)?.let { return it.uri }
        return DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("Could not create the folder '$name' on the USB drive")
    }

    private fun children(context: Context, tree: Uri, parent: Uri): List<Child> {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
        return context.contentResolver.query(childrenUri, projection, null, null, null).useCursor { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    add(Child(documentUri(tree, id), cursor.getString(1).orEmpty(), if (cursor.isNull(2)) -1 else cursor.getLong(2)))
                }
            }
        }
    }

    private fun find(context: Context, tree: Uri, parent: Uri, name: String): Child? = children(context, tree, parent).firstOrNull { it.name == name }

    private fun documentUri(tree: Uri, documentId: String) = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    private fun readJson(context: Context, tree: Uri, parent: Uri, name: String): JSONObject? = find(context, tree, parent, name)?.let { child ->
        runCatching { context.contentResolver.openInputStream(child.uri)?.bufferedReader()?.use { JSONObject(it.readText()) } }.getOrNull()
    }

    private fun writeText(context: Context, tree: Uri, parent: Uri, name: String, text: String) {
        val resolver = context.contentResolver
        val existing = find(context, tree, parent, name)
        val uri = existing?.uri ?: DocumentsContract.createDocument(resolver, parent, "text/plain", name)
            ?: error("Could not create '$name' on the USB drive")
        resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
            ?: error("Could not write '$name' to the USB drive")
    }

    private fun buildM3u(detail: SubsonicPlaylistDetail, names: Map<String, String>): String = buildString {
        appendLine("#EXTM3U")
        detail.songs.forEach { song ->
            appendLine("#EXTINF:${(song.durationMs / 1_000).toInt()},${song.artist.orEmpty()} - ${song.title}")
            appendLine(names.getValue(song.id))
        }
    }

    private fun trackName(song: SubsonicSong, index: Int): String {
        val number = (song.track.takeIf { it > 0 } ?: index + 1).toString().padStart(2, '0')
        val artist = song.artist?.let { "${safe(it, 32)} - " }.orEmpty()
        return "$number - $artist${safe(song.title, 68)} [${safe(song.id, 12)}].${safeExtension(song.suffix)}"
    }

    private fun safe(value: String, max: Int): String = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim().trim('.').take(max).ifBlank { "music" }

    private fun safeExtension(value: String?): String = value?.lowercase()?.filter(Char::isLetterOrDigit)
        ?.take(8)?.takeIf { it.isNotBlank() } ?: "audio"

    private fun audioMime(extension: String?): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(safeExtension(extension)) ?: "application/octet-stream"

    private inline fun <T> Cursor?.useCursor(block: (Cursor) -> T): T {
        if (this == null) error("Could not list the USB folder")
        return use(block)
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
        (0 until length()).mapNotNull { optJSONObject(it) }
}
