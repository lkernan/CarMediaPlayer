package com.saicmotor.media.ui.library

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.saicmotor.media.R
import com.saicmotor.media.data.Library
import com.saicmotor.media.ui.common.Row
import com.saicmotor.media.ui.common.RowsPageFragment

/** Everything, by kind: Albums, Artists, Genres. */
class LibraryFragment : RowsPageFragment() {

    enum class Segment(val viewId: Int) {
        ALBUMS(R.id.seg_albums), ARTISTS(R.id.seg_artists), GENRES(R.id.seg_genres)
    }

    override val layoutId = R.layout.fragment_library

    private var segment = lastSegment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        savedInstanceState?.getString(KEY_SEGMENT)?.let { name ->
            Segment.values().firstOrNull { it.name == name }?.let { segment = it }
        }
        Segment.values().forEach { seg ->
            view.findViewById<TextView>(seg.viewId).setOnClickListener { select(seg) }
        }
        highlight(view)
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SEGMENT, segment.name)
    }

    private fun select(seg: Segment) {
        if (seg == segment) return
        segment = seg
        lastSegment = seg
        highlight(requireView())
        adapter.submitList(emptyList())
        list.scrollToPosition(0)
        reload()
    }

    private fun highlight(view: View) {
        Segment.values().forEach { view.findViewById<TextView>(it.viewId).isSelected = it == segment }
    }

    override suspend fun load(): List<Row> = when (segment) {
        Segment.ALBUMS  -> Library.load("lib:albums")  { allAlbums() }.map { Row.Album(it) }
        Segment.ARTISTS -> Library.load("lib:artists") { artists() }.map { Row.Artist(it) }
        Segment.GENRES  -> Library.load("lib:genres")  { genres() }
            .filter { it.albumCount > 0 }
            .sortedBy { it.name.lowercase() }
            .map { Row.Genre(it) }
    }

    companion object {
        private const val KEY_SEGMENT = "segment"

        /** Come back to the same segment when returning to Library. */
        private var lastSegment = Segment.ALBUMS
    }
}
