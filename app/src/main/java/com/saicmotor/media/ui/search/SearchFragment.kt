package com.saicmotor.media.ui.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import com.saicmotor.media.R
import com.saicmotor.media.data.Library
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.ui.common.Row
import com.saicmotor.media.ui.common.RowsPageFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search as you type: artists, albums, then songs. */
class SearchFragment : RowsPageFragment() {

    override val layoutId = R.layout.fragment_search

    private lateinit var input: EditText
    private var query = lastQuery
    private var typing: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        input = view.findViewById(R.id.query)
        val clear = view.findViewById<View>(R.id.clear)
        input.setText(query)
        input.setSelection(query.length)
        clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        clear.setOnClickListener {
            input.setText("")
            input.requestFocus()
            showKeyboard()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                // Wait for a pause in typing before asking the server.
                typing?.cancel()
                typing = viewLifecycleOwner.lifecycleScope.launch {
                    delay(TYPING_PAUSE_MS)
                    search(s?.toString().orEmpty())
                }
            }
        })
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                typing?.cancel()
                search(input.text.toString())
                hideKeyboard()
                true
            } else false
        }
        super.onViewCreated(view, savedInstanceState)
        if (query.isEmpty()) {
            input.requestFocus()
            showKeyboard()
        }
    }

    private fun search(text: String) {
        val q = text.trim()
        if (q == query && adapter.itemCount > 0) return
        query = q
        lastQuery = q
        adapter.submitList(emptyList())
        reload()
    }

    override suspend fun load(): List<Row> {
        val q = query
        if (q.isEmpty()) return emptyList()
        val found = Library.load { search(q) }
        val parent = MediaService.searchNodeId(q)
        return buildList {
            if (found.artists.isNotEmpty()) {
                add(Row.Heading(getString(R.string.search_artists)))
                add(Row.Shelf("artists:$q", found.artists.map { Row.Artist(it) }))
            }
            if (found.albums.isNotEmpty()) {
                add(Row.Heading(getString(R.string.search_albums)))
                add(Row.Shelf("albums:$q", found.albums.map { Row.Album(it) }))
            }
            if (found.songs.isNotEmpty()) {
                add(Row.Heading(getString(R.string.search_songs)))
                found.songs.forEachIndexed { i, song ->
                    add(Row.Track(song, parent, number = null, showCover = true, showArtist = true, index = i))
                }
            }
        }
    }

    override fun emptyText(): CharSequence =
        if (query.isEmpty()) getString(R.string.search_prompt) else getString(R.string.search_none, query)

    override fun onTrack(row: Row.Track) {
        hideKeyboard()
        super.onTrack(row)
    }

    private fun showKeyboard() {
        input.post {
            requireContext().getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    override fun onStop() {
        super.onStop()
        hideKeyboard()
    }

    companion object {
        private const val TYPING_PAUSE_MS = 450L
        /** Keep the last search when coming back to the tab. */
        private var lastQuery = ""
    }
}
