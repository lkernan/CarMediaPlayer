package com.saicmotor.media.data

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Navidrome turns cover requests away ("429 Too Many Requests") when too many
 * arrive at once: it only draws a couple of covers at a time, and the album
 * grids ask for dozens.  Asks again a moment later instead of leaving the
 * cover blank (and, for the covers fetched ahead, not saved for later).
 */
class RetryWhenBusy(private val waitsMs: List<Long> = listOf(500L, 1_500L)) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())
        for (wait in waitsMs) {
            if (response.code != TOO_MANY_REQUESTS || chain.call().isCanceled()) return response
            val asked = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1_000)
            response.close()
            Thread.sleep((asked ?: wait).coerceIn(0L, MAX_WAIT_MS))
            if (chain.call().isCanceled()) throw IOException("Canceled")
            response = chain.proceed(chain.request())
        }
        return response
    }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        const val MAX_WAIT_MS = 3_000L
    }
}
