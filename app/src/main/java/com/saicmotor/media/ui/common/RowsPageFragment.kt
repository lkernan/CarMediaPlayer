package com.saicmotor.media.ui.common

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.saicmotor.media.R
import com.saicmotor.media.subsonic.SubsonicAlbum
import com.saicmotor.media.subsonic.SubsonicArtist
import com.saicmotor.media.subsonic.SubsonicGenre
import com.saicmotor.media.subsonic.SubsonicPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A page made of [Row]s on the shared grid, with a loading / error overlay.
 * Subclasses say what to [load]; tapping albums, artists and songs is handled
 * here.
 */
abstract class RowsPageFragment : Fragment(), RowActions {

    protected lateinit var list: RecyclerView
    protected lateinit var state: LoadStateView
    protected lateinit var adapter: RowsAdapter
    private var loadJob: Job? = null

    @LayoutRes protected open val layoutId: Int = R.layout.fragment_rows

    protected val navigator: Navigator get() = requireActivity() as Navigator

    /**
     * Keep the song that's playing in view (album and playlist pages): when
     * the page opens, and each time the next song starts — unless you've
     * touched the list in the last few seconds.
     */
    protected open val followPlaying: Boolean = false
    private var lastTouchAt = 0L

    /** The rows to show.  Throw to show an error; return nothing to show [emptyText]. */
    protected abstract suspend fun load(): List<Row>

    protected open fun emptyText(): CharSequence = getString(R.string.empty_list)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(layoutId, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list  = view.findViewById(R.id.list)
        state = view.findViewById(R.id.state)
        adapter = RowsAdapter(this)
        list.useRowsGrid(adapter)

        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                lastTouchAt = SystemClock.uptimeMillis()
                return false
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlayerState.nowPlaying.collect {
                    val changed = adapter.playingMediaId != it?.mediaId
                    adapter.playingMediaId = it?.mediaId
                    if (changed && followPlaying && SystemClock.uptimeMillis() - lastTouchAt > FOLLOW_AFTER_TOUCH_MS) {
                        list.post { scrollToPlaying(smooth = true) }
                    }
                }
            }
        }
        reload()
    }

    /** Brings the playing song's row into view (a third of the way down), if it's on this page. */
    private fun scrollToPlaying(smooth: Boolean) {
        if (view == null) return
        val playing  = adapter.playingMediaId ?: return
        val position = adapter.currentList.indexOfFirst { it is Row.Track && it.mediaId == playing }
        if (position < 0) return
        val manager = list.layoutManager as? LinearLayoutManager ?: return
        val first = manager.findFirstCompletelyVisibleItemPosition()
        val last  = manager.findLastCompletelyVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && position in first..last) return   // already in view
        if (!smooth) {
            manager.scrollToPositionWithOffset(position, list.height / 3)
            return
        }
        manager.startSmoothScroll(object : LinearSmoothScroller(requireContext()) {
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int =
                boxStart + (boxEnd - boxStart) / 3 - viewStart
        }.apply { targetPosition = position })
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            if (adapter.itemCount == 0) state.showLoading()
            try {
                val rows = load()
                val firstLoad = adapter.itemCount == 0
                adapter.submitList(rows) {
                    if (firstLoad && followPlaying) list.post { scrollToPlaying(smooth = false) }
                }
                if (rows.isEmpty()) state.showEmpty(emptyText()) else state.showContent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                adapter.submitList(emptyList())
                state.showError(e, ::reload) { navigator.openSettings() }
            }
        }
    }

    /** Replaces the rows without reloading (e.g. after starring something). */
    protected fun show(rows: List<Row>) = adapter.submitList(rows)

    // ── Default row actions ───────────────────────────────────────────────

    override fun onAlbum(album: SubsonicAlbum)          = navigator.openAlbum(album.id)
    override fun onArtist(artist: SubsonicArtist)       = navigator.openArtist(artist.id)
    override fun onPlaylist(playlist: SubsonicPlaylist) = navigator.openPlaylist(playlist.id)
    override fun onGenre(genre: SubsonicGenre)          = navigator.openGenre(genre.name)
    override fun onTrack(row: Row.Track)                = Playback.playFrom(row.parentId, row.song.id)
    override fun onHeaderSubtitle(artistId: String)     = navigator.openArtist(artistId)

    private companion object {
        /** Don't move the list for this long after you've touched it. */
        const val FOLLOW_AFTER_TOUCH_MS = 8_000L
    }
}
