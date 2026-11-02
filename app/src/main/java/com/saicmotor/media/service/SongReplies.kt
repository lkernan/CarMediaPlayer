package com.saicmotor.media.service

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.json.JSONObject
import java.io.IOException

/**
 * The server answered a request for song [songId] with a message instead of
 * the song.  [code] is the Subsonic error code, or -1 if there wasn't one.
 */
class SongRefused(val songId: String?, val code: Int, message: String) : IOException(message) {

    /** The server says there's no such song (Subsonic error 70): asking again won't help. */
    val notFound: Boolean get() = code == NOT_FOUND

    companion object {
        private const val NOT_FOUND = 70

        fun from(songId: String?, body: String): SongRefused = try {
            val error = JSONObject(body).getJSONObject("subsonic-response").optJSONObject("error")
            val code = error?.optInt("code", -1) ?: -1
            SongRefused(songId, code, "error $code: ${error?.optString("message").orEmpty()}")
        } catch (_: Exception) {
            SongRefused(songId, -1, "the reply wasn't a song")
        }
    }
}

/**
 * Song requests to the server, checked before anything is saved.
 *
 * Navidrome answers a song it can't send with an error message (as every
 * Subsonic error comes: HTTP 200, a JSON body) — "song not found", "error
 * transcoding", a login problem.  That message used to go into the song cache
 * as if it were the song, so the song then failed every time it came up (each
 * time after half a minute of silence), even once the server was fine again.
 * Now a reply that isn't audio becomes a [SongRefused] error, and nothing of
 * it is kept.
 */
@OptIn(UnstableApi::class)
internal class SongReplies(private val http: HttpDataSource) : DataSource {

    class Factory(private val http: HttpDataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = SongReplies(http.createDataSource())
    }

    override fun addTransferListener(transferListener: TransferListener) = http.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val length = http.open(dataSpec)
        val type = http.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
        if (isMessage(type)) {
            val body = try { readSome() } finally { http.close() }
            throw SongRefused.from(songId(dataSpec.uri), body)
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = http.read(buffer, offset, length)

    override fun getUri(): Uri? = http.uri

    override fun getResponseHeaders(): Map<String, List<String>> = http.responseHeaders

    override fun close() = http.close()

    private fun readSome(): String {
        val buffer = ByteArray(MAX_MESSAGE)
        var size = 0
        while (size < buffer.size) {
            val n = http.read(buffer, size, buffer.size - size)
            if (n == C.RESULT_END_OF_INPUT) break
            size += n
        }
        return String(buffer, 0, size, Charsets.UTF_8)
    }

    private companion object {
        const val MAX_MESSAGE = 16 * 1024

        fun songId(uri: Uri): String? = try { uri.getQueryParameter("id") } catch (_: Exception) { null }

        /** JSON, XML, HTML or plain text: a message, not audio. */
        fun isMessage(contentType: String?): Boolean {
            val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
            return type == "application/json" || type == "application/xml" || type.startsWith("text/")
        }
    }
}
