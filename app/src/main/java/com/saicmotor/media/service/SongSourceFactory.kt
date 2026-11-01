package com.saicmotor.media.service

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap

/**
 * Builds the player's source for each song, telling it how long the song is.
 *
 * A song that isn't downloaded yet streams from Navidrome transcoded, with no
 * length, and the Ogg reader can't work out its duration either.  ExoPlayer
 * takes a stream like that for live radio: if the connection drops (Wi-Fi to
 * mobile data as the car leaves home, a dead spot), it starts loading it again
 * from the start and the song restarts from the beginning.  With the
 * duration known (the server says how long every song is), it carries on
 * from where the connection dropped instead.
 */
@OptIn(UnstableApi::class)
internal class SongSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    /** The song's length in milliseconds, or null if unknown. */
    private val durationMsOf: (MediaItem) -> Long?
) : MediaSource.Factory {

    private var drm: DrmSessionManagerProvider = DefaultDrmSessionManagerProvider()
    private var loadErrors: LoadErrorHandlingPolicy = WaitForNetworkPolicy()

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { drm = provider }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
        apply { loadErrors = policy }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val durationUs = durationMsOf(mediaItem)?.takeIf { it > 0 }?.times(1_000) ?: C.TIME_UNSET
        return ProgressiveMediaSource.Factory(dataSourceFactory, KnownDurationExtractors(durationUs))
            .setDrmSessionManagerProvider(drm)
            .setLoadErrorHandlingPolicy(loadErrors)
            .createMediaSource(mediaItem)
    }
}

/** The usual extractors, but a stream whose duration they can't tell gets [durationUs]. */
@OptIn(UnstableApi::class)
internal class KnownDurationExtractors(private val durationUs: Long) : ExtractorsFactory {
    private val all = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = wrap(all.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(all.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        if (durationUs == C.TIME_UNSET) extractors
        else Array(extractors.size) { KnownDurationExtractor(extractors[it], durationUs) }
}

@OptIn(UnstableApi::class)
internal class KnownDurationExtractor(
    private val extractor: Extractor,
    private val knownDurationUs: Long
) : Extractor {

    override fun sniff(input: ExtractorInput): Boolean = extractor.sniff(input)

    override fun init(output: ExtractorOutput) {
        extractor.init(object : ExtractorOutput by output {
            override fun seekMap(seekMap: SeekMap) {
                output.seekMap(
                    if (seekMap.durationUs != C.TIME_UNSET) seekMap
                    else object : SeekMap by seekMap {
                        override fun getDurationUs(): Long = knownDurationUs
                    }
                )
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        extractor.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = extractor.seek(position, timeUs)

    override fun release() = extractor.release()

    override fun getUnderlyingImplementation(): Extractor = extractor.underlyingImplementation
}
