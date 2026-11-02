package com.saicmotor.media.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * How the player reacts when a song can't be fetched.
 *
 * ExoPlayer's default gives up after a few quick retries and reports an
 * error, and the app then waited a few more seconds before trying again — so
 * when the car starts and the mobile connection comes up, the
 * song could start many seconds after the server was reachable.
 *
 * Here, when the problem is the connection itself, the player just keeps
 * trying every [NETWORK_RETRY_MS] while showing "buffering", so the song
 * starts within a second or two of the server being reachable.  A server
 * error (5xx: restarting) is waited out too.  A song the server says doesn't
 * exist is reported straight away, and the app skips it.  Anything else — an
 * error message instead of the song, or a song that can't be read — is
 * reported after a few tries, and the app retries or skips it.
 */
@OptIn(UnstableApi::class)
class WaitForNetworkPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = info.exception
        val httpCode = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return when {
            // The server says the song doesn't exist: the app skips it (see MediaService).
            e is SongRefused && e.notFound -> C.TIME_UNSET
            httpCode != null && httpCode >= 500 ->
                if (info.errorCount > MAX_WAIT_RETRIES) C.TIME_UNSET else SERVER_RETRY_MS
            httpCode != null -> C.TIME_UNSET   // 4xx: missing / not allowed — no point waiting
            isConnectionProblem(e) ->
                if (info.errorCount > MAX_WAIT_RETRIES) C.TIME_UNSET else NETWORK_RETRY_MS
            info.errorCount > OTHER_RETRIES -> C.TIME_UNSET
            else -> super.getRetryDelayMsFor(info)
        }
    }

    /** Only give up when [getRetryDelayMsFor] says so, not after a fixed count. */
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE

    companion object {
        const val NETWORK_RETRY_MS = 1_000L
        private const val SERVER_RETRY_MS = 3_000L
        /** About 15 minutes of no network before it's reported as an error. */
        private const val MAX_WAIT_RETRIES = 600
        private const val OTHER_RETRIES = 3

        /** The server couldn't be reached at all (or the connection dropped). */
        fun isConnectionProblem(e: Throwable): Boolean {
            if (e is HttpDataSource.InvalidResponseCodeException) return false
            if (e is DataSourceException && (
                    e.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    e.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)) return true
            var cause: Throwable? = e
            var depth = 0
            while (cause != null && depth++ < 8) {
                if (cause is UnknownHostException || cause is ConnectException ||
                    cause is NoRouteToHostException || cause is SocketTimeoutException ||
                    cause is SocketException) return true
                cause = cause.cause
            }
            return false
        }
    }
}
