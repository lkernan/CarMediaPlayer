package com.saicmotor.media.ui.detail

import android.os.Bundle
import com.saicmotor.media.R
import com.saicmotor.media.data.Library
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.subsonic.SubsonicAlbumDetail
import com.saicmotor.media.ui.common.Format
import com.saicmotor.media.ui.common.PageButton
import com.saicmotor.media.ui.common.Playback
import com.saicmotor.media.ui.common.Row
import com.saicmotor.media.ui.common.RowsPageFragment

/** An album: cover, Play / Shuffle, and its songs in order. */
class AlbumFragment : RowsPageFragment() {

    override val followPlaying = true

    private val albumId get() = requireArguments().getString(ARG_ID)!!
    private val parentId get() = MediaService.albumNodeId(albumId)

    override suspend fun load(): List<Row> {
        val d = Library.load { album(albumId) }
        return rows(d)
    }

    private fun rows(d: SubsonicAlbumDetail): List<Row> = buildList {
        val album = d.album
        val ctx = requireContext()
        val meta = listOfNotNull(
            Format.songs(ctx, d.songs.size),
            Format.length(ctx, album.durationSec.takeIf { it > 0 } ?: (d.songs.sumOf { it.durationMs } / 1000).toInt()),
            album.year.takeIf { it > 0 }?.toString()
        ).joinToString(", ")
        add(Row.Header(
            title = album.name, subtitle = album.artist, subtitleArtistId = album.artistId, meta = meta,
            coverUrl = Library.coverUrl(album.coverArt, 600), showCover = true, round = false,
            primary   = PageButton(PLAY, getString(R.string.play), R.drawable.ic_play),
            secondary = PageButton(SHUFFLE, getString(R.string.shuffle), R.drawable.ic_shuffle)
        ))
        val discs = d.songs.map { it.disc }.distinct()
        d.songs.forEachIndexed { i, song ->
            if (discs.size > 1 && (i == 0 || d.songs[i - 1].disc != song.disc)) {
                add(Row.Heading(getString(R.string.disc_number, song.disc)))
            }
            add(Row.Track(
                song, parentId,
                number = song.track.takeIf { it > 0 } ?: (i + 1),
                showCover = false,
                // Compilations: show who's singing each song.
                showArtist = song.artist != null && song.artist != album.artist,
                index = i
            ))
        }
    }

    override fun onButton(id: String) {
        when (id) {
            PLAY    -> Playback.playAll(parentId, shuffle = false)
            SHUFFLE -> Playback.playAll(parentId, shuffle = true)
        }
    }

    companion object {
        private const val ARG_ID  = "album_id"
        private const val PLAY    = "play"
        private const val SHUFFLE = "shuffle"

        fun newInstance(albumId: String) = AlbumFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, albumId) }
        }
    }
}
