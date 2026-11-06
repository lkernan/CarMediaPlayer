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

/** A genre: shuffle songs from it, or open one of its albums. */
class GenreFragment : RowsPageFragment() {

    private val genre get() = requireArguments().getString(ARG_NAME)!!

    override suspend fun load(): List<Row> {
        val albums = Library.load("genre:$genre") { genreAlbums(genre) }
        return buildList {
            add(Row.Header(
                title = genre, subtitle = null, subtitleArtistId = null,
                meta = Format.albums(requireContext(), albums.size),
                coverUrl = null, showCover = false, round = false,
                primary = PageButton(SHUFFLE, getString(R.string.shuffle_genre, genre), R.drawable.ic_shuffle),
                secondary = null
            ))
            albums.forEach { add(Row.Album(it)) }
        }
    }

    override fun onButton(id: String) {
        if (id == SHUFFLE) Playback.playAll(MediaService.genreSongsNodeId(genre), shuffle = true)
    }

    companion object {
        private const val ARG_NAME = "genre"
        private const val SHUFFLE  = "shuffle"

        fun newInstance(genre: String) = GenreFragment().apply {
            arguments = Bundle().apply { putString(ARG_NAME, genre) }
        }
    }
}
