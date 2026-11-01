package com.saicmotor.media.ui.home

import androidx.annotation.StringRes
import com.saicmotor.media.R
import com.saicmotor.media.data.Library
import com.saicmotor.media.subsonic.SubsonicAlbum
import com.saicmotor.media.subsonic.SubsonicException
import com.saicmotor.media.ui.common.PageButton
import com.saicmotor.media.ui.common.Playback
import com.saicmotor.media.ui.common.Row
import com.saicmotor.media.ui.common.RowsPageFragment
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Shuffle everything, then shelves of what you've been playing and adding. */
class HomeFragment : RowsPageFragment() {

    override suspend fun load(): List<Row> = coroutineScope {
        if (!Library.isConfigured) {
            throw SubsonicException(SubsonicException.Kind.NOT_CONFIGURED, "Not set up")
        }
        val recent   = async { Library.load("home:recent")   { albumList("recent", 20) } }
        val newest   = async { Library.load("home:newest")   { albumList("newest", 20) } }
        val frequent = async { Library.load("home:frequent") { albumList("frequent", 20) } }
        val random   = async { Library.load("home:random")   { albumList("random", 20) } }

        buildList {
            add(Row.Hero(PageButton(SHUFFLE_ALL, getString(R.string.shuffle_all), R.drawable.ic_shuffle), null))
            shelf(R.string.shelf_recent, recent.await())
            shelf(R.string.shelf_newest, newest.await())
            shelf(R.string.shelf_frequent, frequent.await())
            shelf(R.string.shelf_random, random.await())
        }
    }

    private fun MutableList<Row>.shelf(@StringRes title: Int, albums: List<SubsonicAlbum>) {
        if (albums.isEmpty()) return
        val text = getString(title)
        add(Row.Heading(text))
        add(Row.Shelf(text, albums.map { Row.Album(it) }))
    }

    override fun onButton(id: String) {
        if (id == SHUFFLE_ALL) Playback.shuffleLibrary()
    }

    private companion object {
        const val SHUFFLE_ALL = "shuffle_all"
    }
}
