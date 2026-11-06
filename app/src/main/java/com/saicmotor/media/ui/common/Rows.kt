package com.saicmotor.media.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.shape.ShapeAppearanceModel
import com.saicmotor.media.R
import com.saicmotor.media.data.Library
import com.saicmotor.media.databinding.ItemCardBinding
import com.saicmotor.media.databinding.ItemGenreBinding
import com.saicmotor.media.databinding.ItemHeaderBinding
import com.saicmotor.media.databinding.ItemHeadingBinding
import com.saicmotor.media.databinding.ItemHeroBinding
import com.saicmotor.media.databinding.ItemMessageBinding
import com.saicmotor.media.databinding.ItemShelfBinding
import com.saicmotor.media.databinding.ItemTrackBinding
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.subsonic.SubsonicAlbum
import com.saicmotor.media.subsonic.SubsonicArtist
import com.saicmotor.media.subsonic.SubsonicGenre
import com.saicmotor.media.subsonic.SubsonicSong
import com.saicmotor.media.subsonic.SubsonicPlaylist

/** A button on a page header or the Home hero row. */
data class PageButton(val id: String, val label: String, @DrawableRes val icon: Int)

/** Everything a page can show, one entry per row (or grid cell). */
sealed class Row {
    abstract val key: String

    data class Heading(val text: String) : Row() { override val key = "heading:$text" }
    data class Message(val text: String) : Row() { override val key = "message:$text" }
    data class Hero(val primary: PageButton, val secondary: PageButton?) : Row() { override val key = "hero" }

    data class Header(
        val title:    String,
        val subtitle: String?,
        /** Tapping the subtitle opens this artist. */
        val subtitleArtistId: String?,
        val meta:     String?,
        val coverUrl: String?,
        val showCover: Boolean,
        val round:    Boolean,
        val primary:  PageButton?,
        val secondary: PageButton?,
        val tertiary: PageButton? = null
    ) : Row() { override val key = "header" }

    data class Album(val album: SubsonicAlbum, val subtitle: String? = album.artist) : Row() {
        override val key = "album:${album.id}"
    }
    data class Artist(val artist: SubsonicArtist) : Row() { override val key = "artist:${artist.id}" }
    data class Playlist(val playlist: SubsonicPlaylist, val subtitle: String? = null) : Row() {
        override val key = "playlist:${playlist.id}"
    }
    data class Genre(val genre: SubsonicGenre) : Row() { override val key = "genre:${genre.name}" }

    /** A song in a list; tapping plays [parentId] from this song. */
    data class Track(
        val song:       SubsonicSong,
        val parentId:   String,
        val number:     Int?,
        val showCover:  Boolean,
        val showArtist: Boolean,
        val index:      Int
    ) : Row() {
        override val key = "track:$index:${song.id}"
        val mediaId get() = MediaService.songMediaId(song.id)
    }

    data class Queued(val entry: QueueEntry) : Row() { override val key = "queued:${entry.id}" }

    /** A horizontally scrolling row of cards. */
    data class Shelf(val id: String, val rows: List<Row>) : Row() { override val key = "shelf:$id" }
}

/** What tapping things on a page does. */
interface RowActions {
    fun onAlbum(album: SubsonicAlbum)
    fun onArtist(artist: SubsonicArtist)
    fun onPlaylist(playlist: SubsonicPlaylist)
    fun onGenre(genre: SubsonicGenre)
    fun onTrack(row: Row.Track)
    fun onQueued(entry: QueueEntry) {}
    fun onButton(id: String) {}
    fun onHeaderSubtitle(artistId: String) {}
}

class RowsAdapter(
    private val actions: RowActions,
    /** Cards in a horizontal shelf have a fixed width. */
    private val horizontal: Boolean = false
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {

    private val sharedPool = RecyclerView.RecycledViewPool()

    /** Highlights the song that's playing. */
    var playingMediaId: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_PLAYING)
        }

    /** Highlights the current entry in Up next. */
    var activeQueueId: Long = -1
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_PLAYING)
        }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Row.Heading  -> T_HEADING
        is Row.Message  -> T_MESSAGE
        is Row.Hero     -> T_HERO
        is Row.Header   -> T_HEADER
        is Row.Album, is Row.Artist, is Row.Playlist -> T_CARD
        is Row.Genre    -> T_GENRE
        is Row.Track, is Row.Queued -> T_TRACK
        is Row.Shelf    -> T_SHELF
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            T_HEADING -> Simple(ItemHeadingBinding.inflate(inflater, parent, false).root)
            T_MESSAGE -> Simple(ItemMessageBinding.inflate(inflater, parent, false).root)
            T_HERO    -> HeroVH(ItemHeroBinding.inflate(inflater, parent, false))
            T_HEADER  -> HeaderVH(ItemHeaderBinding.inflate(inflater, parent, false))
            T_CARD    -> CardVH(ItemCardBinding.inflate(inflater, parent, false)).also {
                if (horizontal) {
                    val res = parent.resources
                    it.itemView.layoutParams.width =
                        res.getDimensionPixelSize(R.dimen.card_width) + res.getDimensionPixelSize(R.dimen.card_gap)
                }
            }
            T_GENRE   -> GenreVH(ItemGenreBinding.inflate(inflater, parent, false))
            T_TRACK   -> TrackVH(ItemTrackBinding.inflate(inflater, parent, false))
            else      -> ShelfVH(ItemShelfBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is HeroVH   -> holder.bind(row as Row.Hero)
            is HeaderVH -> holder.bind(row as Row.Header)
            is CardVH   -> holder.bind(row)
            is GenreVH  -> holder.bind((row as Row.Genre).genre)
            is TrackVH  -> holder.bind(row)
            is ShelfVH  -> holder.bind(row as Row.Shelf)
            is Simple   -> (holder.itemView as android.widget.TextView).text = when (row) {
                is Row.Heading -> row.text
                is Row.Message -> row.text
                else -> ""
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && holder is TrackVH) holder.bindPlaying(getItem(position))
        else if (payloads.isNotEmpty() && holder is ShelfVH) holder.updatePlaying()
        else onBindViewHolder(holder, position)
    }

    // ── View holders ──────────────────────────────────────────────────────

    private class Simple(view: View) : RecyclerView.ViewHolder(view)

    private inner class HeroVH(val b: ItemHeroBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row.Hero) {
            b.primary.bindButton(row.primary)
            b.secondary.visibility = if (row.secondary != null) View.VISIBLE else View.GONE
            row.secondary?.let { b.secondary.bindButton(it) }
        }
    }

    private inner class HeaderVH(val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row.Header) {
            b.cover.visibility = if (row.showCover) View.VISIBLE else View.GONE
            if (row.showCover) {
                b.cover.shapeAppearanceModel = shape(b.root.context, row.round)
                b.cover.loadCover(row.coverUrl, if (row.round) R.drawable.artist_placeholder else R.drawable.cover_placeholder)
            }
            b.title.text = row.title
            b.subtitle.visibility = if (row.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            b.subtitle.text = row.subtitle
            if (row.subtitleArtistId != null) {
                b.subtitle.setOnClickListener { actions.onHeaderSubtitle(row.subtitleArtistId) }
                b.subtitle.setBackgroundResource(R.drawable.row_background)
            } else {
                b.subtitle.setOnClickListener(null)
                b.subtitle.isClickable = false
                b.subtitle.background = null
            }
            b.meta.visibility = if (row.meta.isNullOrBlank()) View.GONE else View.VISIBLE
            b.meta.text = row.meta
            b.primary.visibility = if (row.primary != null) View.VISIBLE else View.GONE
            row.primary?.let { b.primary.bindButton(it) }
            b.secondary.visibility = if (row.secondary != null) View.VISIBLE else View.GONE
            row.secondary?.let { b.secondary.bindButton(it) }
            b.tertiary.visibility = if (row.tertiary != null) View.VISIBLE else View.GONE
            row.tertiary?.let { b.tertiary.bindButton(it) }
        }
    }

    private fun com.google.android.material.button.MaterialButton.bindButton(button: PageButton) {
        text = button.label
        setIconResource(button.icon)
        setOnClickListener { actions.onButton(button.id) }
    }

    private inner class CardVH(val b: ItemCardBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row) {
            val ctx = b.root.context
            when (row) {
                is Row.Album -> {
                    b.cover.shapeAppearanceModel = shape(ctx, false)
                    b.cover.loadCover(Library.coverUrl(row.album.coverArt))
                    b.title.text = row.album.name
                    b.subtitle.text = row.subtitle
                    b.title.gravity = android.view.Gravity.START
                    b.subtitle.gravity = android.view.Gravity.START
                    b.root.setOnClickListener { actions.onAlbum(row.album) }
                }
                is Row.Artist -> {
                    b.cover.shapeAppearanceModel = shape(ctx, true)
                    b.cover.loadCover(Library.coverUrl(row.artist.coverArt), R.drawable.artist_placeholder)
                    b.title.text = row.artist.name
                    b.subtitle.text = if (row.artist.albumCount > 0) Format.albums(ctx, row.artist.albumCount) else null
                    b.title.gravity = android.view.Gravity.CENTER_HORIZONTAL
                    b.subtitle.gravity = android.view.Gravity.CENTER_HORIZONTAL
                    b.root.setOnClickListener { actions.onArtist(row.artist) }
                }
                is Row.Playlist -> {
                    b.cover.shapeAppearanceModel = shape(ctx, false)
                    b.cover.loadCover(Library.coverUrl(row.playlist.coverArt), R.drawable.cover_placeholder)
                    b.title.text = row.playlist.name
                    b.subtitle.text = row.subtitle
                    b.title.gravity = android.view.Gravity.START
                    b.subtitle.gravity = android.view.Gravity.START
                    b.root.setOnClickListener { actions.onPlaylist(row.playlist) }
                }
                else -> Unit
            }
            b.subtitle.visibility = if (b.subtitle.text.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
        }
    }

    private inner class GenreVH(val b: ItemGenreBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(genre: SubsonicGenre) {
            b.title.text = genre.name
            b.subtitle.text = Format.albums(b.root.context, genre.albumCount)
            b.tile.setOnClickListener { actions.onGenre(genre) }
        }
    }

    private inner class TrackVH(val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row) {
            val ctx = b.root.context
            when (row) {
                is Row.Track -> {
                    b.title.text = row.song.title
                    b.subtitle.text = row.song.artist
                    b.subtitle.visibility = if (row.showArtist && !row.song.artist.isNullOrBlank()) View.VISIBLE else View.GONE
                    b.duration.text = Format.clock(row.song.durationMs)
                    b.number.text = row.number?.toString().orEmpty()
                    b.thumb.visibility = if (row.showCover) View.VISIBLE else View.GONE
                    if (row.showCover) b.thumb.loadCover(Library.coverUrl(row.song.coverArt, 120))
                    b.row.setOnClickListener { actions.onTrack(row) }
                }
                is Row.Queued -> {
                    b.title.text = row.entry.title
                    b.subtitle.text = row.entry.artist
                    b.subtitle.visibility = if (row.entry.artist.isBlank()) View.GONE else View.VISIBLE
                    b.duration.text = if (row.entry.durationMs > 0) Format.clock(row.entry.durationMs) else ""
                    b.number.text = ""
                    b.thumb.visibility = View.VISIBLE
                    b.thumb.loadCover(row.entry.artUri)
                    b.row.setOnClickListener { actions.onQueued(row.entry) }
                }
                else -> Unit
            }
            bindPlaying(row)
            b.title.setTextColor(ContextCompat.getColor(ctx, if (isPlaying(row)) R.color.accent_ink else R.color.ink))
        }

        private fun isPlaying(row: Row) = when (row) {
            is Row.Track  -> row.mediaId == playingMediaId
            is Row.Queued -> row.entry.id == activeQueueId
            else -> false
        }

        fun bindPlaying(row: Row) {
            val playing = isPlaying(row)
            b.playing.visibility = if (playing) View.VISIBLE else View.GONE
            // Over a cover the bars sit on a dark wash; in a numbered list they replace the number.
            b.number.visibility = if (playing) View.INVISIBLE else View.VISIBLE
            b.thumb.alpha = if (playing) 0.35f else 1f
            b.title.setTextColor(ContextCompat.getColor(b.root.context, if (playing) R.color.accent_ink else R.color.ink))
            b.row.setBackgroundResource(
                if (row is Row.Queued && playing) R.drawable.row_background_active else R.drawable.row_background
            )
        }
    }

    private inner class ShelfVH(val b: ItemShelfBinding) : RecyclerView.ViewHolder(b.root) {
        private val inner = RowsAdapter(actions, horizontal = true)
        init {
            b.shelf.layoutManager = LinearLayoutManager(b.root.context, RecyclerView.HORIZONTAL, false)
            b.shelf.adapter = inner
            b.shelf.setRecycledViewPool(sharedPool)
        }
        fun bind(row: Row.Shelf) {
            inner.submitList(row.rows)
            updatePlaying()
        }
        fun updatePlaying() { inner.playingMediaId = playingMediaId }
    }

    private fun shape(context: android.content.Context, round: Boolean): ShapeAppearanceModel =
        ShapeAppearanceModel.builder(
            context, if (round) R.style.ShapeAppearance_Circle else R.style.ShapeAppearance_Cover, 0
        ).build()

    companion object {
        private const val T_HEADING = 1
        private const val T_MESSAGE = 2
        private const val T_HERO    = 3
        private const val T_HEADER  = 4
        private const val T_CARD    = 5
        private const val T_GENRE   = 6
        private const val T_TRACK   = 7
        private const val T_SHELF   = 8

        private val PAYLOAD_PLAYING = Any()

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }

        /**
         * The page grid has 60 columns so cards (5 or 6 across) and genre
         * tiles (3 or 4 across) can share one list; everything else spans
         * the full width.
         */
        const val GRID_SPANS = 60

        fun spanFor(row: Row, cardsPerRow: Int, tilesPerRow: Int): Int = when (row) {
            is Row.Album, is Row.Artist, is Row.Playlist -> GRID_SPANS / cardsPerRow
            is Row.Genre -> GRID_SPANS / tilesPerRow
            else -> GRID_SPANS
        }
    }
}

/**
 * Lays [list] out as the page grid: works out how many cards fit across
 * whenever its width changes.
 */
fun RecyclerView.useRowsGrid(adapter: RowsAdapter) {
    val res = resources
    val cardMin = res.getDimensionPixelSize(R.dimen.card_width) + res.getDimensionPixelSize(R.dimen.card_gap)
    val tileMin = res.getDimensionPixelSize(R.dimen.tile_min_width)
    var cards = 5
    var tiles = 4
    val manager = GridLayoutManager(context, RowsAdapter.GRID_SPANS)
    manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int {
            if (position !in 0 until adapter.itemCount) return RowsAdapter.GRID_SPANS
            return RowsAdapter.spanFor(adapter.currentList[position], cards, tiles)
        }
    }
    layoutManager = manager
    this.adapter = adapter
    addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
        if (r - l == or - ol) return@addOnLayoutChangeListener
        val inner = (r - l) - v.paddingLeft - v.paddingRight
        // Columns must divide the grid evenly.
        cards = listOf(6, 5, 4, 3, 2).firstOrNull { inner / it >= cardMin } ?: 2
        tiles = listOf(5, 4, 3, 2).firstOrNull { inner / it >= tileMin } ?: 2
        manager.spanSizeLookup.invalidateSpanIndexCache()
        v.post { adapter.notifyItemRangeChanged(0, adapter.itemCount) }
    }
}
