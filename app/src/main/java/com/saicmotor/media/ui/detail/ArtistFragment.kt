package com.saicmotor.media.ui.detail

import android.os.Bundle
import com.saicmotor.media.R
import com.saicmotor.media.data.Library
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.ui.common.Format
import com.saicmotor.media.ui.common.PageButton
import com.saicmotor.media.ui.common.Playback
import com.saicmotor.media.ui.common.Row
import com.saicmotor.media.ui.common.RowsPageFragment

/** An artist: Shuffle / Play everything by them, then their albums, newest first. */
class ArtistFragment : RowsPageFragment() {

    private val artistId get() = requireArguments().getString(ARG_ID)!!
    private val songsNode get() = MediaService.artistSongsNodeId(artistId)

    override suspend fun load(): List<Row> {
        val d = Library.load { artist(artistId) }
        val albums = d.albums.sortedWith(compareByDescending<com.saicmotor.media.subsonic.SubsonicAlbum> { it.year }
            .thenBy { it.name.lowercase() })
        return buildList {
            add(Row.Header(
                title = d.artist.name, subtitle = null, subtitleArtistId = null,
                meta = Format.albums(requireContext(), albums.size),
                coverUrl = Library.coverUrl(d.artist.coverArt, 600), showCover = true, round = true,
                primary   = PageButton(SHUFFLE, getString(R.string.shuffle), R.drawable.ic_shuffle),
                secondary = PageButton(PLAY, getString(R.string.play), R.drawable.ic_play)
            ))
            albums.forEach { add(Row.Album(it, subtitle = it.year.takeIf { y -> y > 0 }?.toString())) }
        }
    }

    override fun onButton(id: String) {
        when (id) {
            SHUFFLE -> Playback.playAll(songsNode, shuffle = true)
            PLAY    -> Playback.playAll(songsNode, shuffle = false)
        }
    }

    companion object {
        private const val ARG_ID  = "artist_id"
        private const val PLAY    = "play"
        private const val SHUFFLE = "shuffle"

        fun newInstance(artistId: String) = ArtistFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, artistId) }
        }
    }
}
