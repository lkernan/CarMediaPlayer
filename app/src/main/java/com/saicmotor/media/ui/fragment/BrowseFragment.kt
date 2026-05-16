package com.saicmotor.media.ui.fragment

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.saicmotor.media.databinding.FragmentBrowseBinding
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.ui.activity.MainActivity
import com.saicmotor.media.ui.adapter.BrowseAdapter

class BrowseFragment : Fragment() {

    private var _binding: FragmentBrowseBinding? = null
    private val binding get() = _binding!!

    private lateinit var mediaBrowser: MediaBrowserCompat
    private lateinit var adapter: BrowseAdapter

    // Navigation stack: list of (parentId, displayLabel)
    private val stack = ArrayDeque<Pair<String, String>>()

    // Currently active source root — set externally by MainActivity
    var sourceRoot: String = MediaService.USB1_ROOT
        set(value) {
            field = value
            if (::mediaBrowser.isInitialized && mediaBrowser.isConnected) {
                resetToSource(value)
            }
        }

    // Currently highlighted category (artists/albums/tracks)
    private var activeCategoryId: String? = null

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            resetToSource(sourceRoot)
        }
    }

    private val subscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(
            parentId: String,
            children: MutableList<MediaBrowserCompat.MediaItem>
        ) {
            val list = children.toList()
            adapter.submitList(list)
            val empty = list.isEmpty()
            _binding?.emptyState?.visibility = if (empty) View.VISIBLE else View.GONE

            // Show Play All only when the list contains playable tracks
            val hasPlayable = list.any { it.isPlayable }
            _binding?.btnPlayAll?.visibility     = if (hasPlayable) View.VISIBLE else View.GONE
            _binding?.playAllDivider?.visibility = if (hasPlayable) View.VISIBLE else View.GONE

            buildAlphabetStrip(list)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = BrowseAdapter(
            onBrowsable = { item -> navigateTo(item.mediaId!!, item.description.title.toString()) },
            onPlayable   = { item ->
                // Include the current browse parent so the service can build
                // a full sibling playlist and enable next/previous skipping.
                val extras = Bundle(item.description.extras ?: Bundle())
                currentParentId()?.let { extras.putString(MediaService.EXTRA_PARENT_ID, it) }
                MediaControllerCompat.getMediaController(requireActivity())
                    ?.transportControls?.playFromMediaId(item.mediaId, extras)
                (requireActivity() as MainActivity).showNowPlaying()
            }
        )
        binding.browseList.layoutManager = LinearLayoutManager(requireContext())
        binding.browseList.adapter = adapter

        binding.btnBack.setOnClickListener { navigateBack() }

        binding.btnPlayAll.setOnClickListener {
            val parentId = currentParentId() ?: return@setOnClickListener
            if (mediaBrowser.isConnected) {
                mediaBrowser.sendCustomAction(
                    MediaService.ACTION_PLAY_ALL,
                    Bundle().apply { putString(MediaService.EXTRA_PARENT_ID, parentId) },
                    null
                )
            }
            (requireActivity() as MainActivity).showNowPlaying()
        }

        binding.btnCatArtists.setOnClickListener {
            setActiveCategory(MediaService.artistsId(sourceRoot))
        }
        binding.btnCatAlbums.setOnClickListener {
            setActiveCategory(MediaService.albumsId(sourceRoot))
        }
        binding.btnCatTracks.setOnClickListener {
            setActiveCategory(MediaService.tracksId(sourceRoot))
        }

        binding.btnScan.setOnClickListener {
            requireContext().startService(
                Intent(requireContext(), MediaService::class.java)
                    .setAction(MediaService.ACTION_SCAN_USB)
            )
        }

        binding.btnSubsonicSettings.setOnClickListener {
            (requireActivity() as MainActivity).showSubsonicSettings()
        }

        mediaBrowser = MediaBrowserCompat(
            requireContext(),
            ComponentName(requireContext(), MediaService::class.java),
            connectionCallback,
            null
        )
    }

    override fun onStart() {
        super.onStart()
        mediaBrowser.connect()
    }

    override fun onStop() {
        super.onStop()
        if (mediaBrowser.isConnected) {
            currentParentId()?.let { mediaBrowser.unsubscribe(it) }
            mediaBrowser.disconnect()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    private fun resetToSource(root: String) {
        currentParentId()?.let { mediaBrowser.unsubscribe(it) }
        stack.clear()
        val defaultCat  = MediaService.artistsId(root)
        val sourceLabel = if (root == MediaService.ONLINE_ROOT) "Subsonic" else "USB"
        stack.addLast(root       to sourceLabel)
        stack.addLast(defaultCat to "Artists")
        activeCategoryId = defaultCat
        subscribeToCurrent()
        updateSidebarState()
    }

    private fun setActiveCategory(categoryId: String) {
        if (!mediaBrowser.isConnected) return
        currentParentId()?.let { mediaBrowser.unsubscribe(it) }
        while (stack.size > 1) stack.removeLast()
        val label = when {
            categoryId.endsWith("artists") -> "Artists"
            categoryId.endsWith("albums")  -> "Albums"
            else                           -> "Tracks"
        }
        stack.addLast(categoryId to label)
        activeCategoryId = categoryId
        subscribeToCurrent()
        updateSidebarState()
    }

    private fun navigateTo(parentId: String, label: String) {
        currentParentId()?.let { mediaBrowser.unsubscribe(it) }
        stack.addLast(parentId to label)
        subscribeToCurrent()
        updateSidebarState()
    }

    fun navigateBack(): Boolean {
        if (stack.size <= 2) return false  // keep at least [source, category]
        currentParentId()?.let { mediaBrowser.unsubscribe(it) }
        stack.removeLast()
        subscribeToCurrent()
        updateSidebarState()
        return true
    }

    private fun subscribeToCurrent() {
        currentParentId()?.let { mediaBrowser.subscribe(it, subscriptionCallback) }
    }

    private fun currentParentId() = stack.lastOrNull()?.first

    // ── Alphabet fast-scroll strip ─────────────────────────────────────────

    private fun buildAlphabetStrip(items: List<MediaBrowserCompat.MediaItem>) {
        val strip = _binding?.alphabetStrip ?: return
        strip.removeAllViews()

        // Collect the first character of each item's title, deduped and sorted
        val letters = items.mapNotNull { item ->
            item.description.title?.toString()?.trimStart()?.firstOrNull()
                ?.uppercaseChar()?.takeIf { it.isLetter() || it.isDigit() }
        }.distinct().sorted()

        if (letters.size < 4) {          // not useful for very short lists
            strip.visibility = View.GONE
            return
        }

        // Add one TextView per letter — used only for display, touch handled below
        val textColor = resources.getColor(com.saicmotor.media.R.color.text_secondary, null)
        letters.forEach { letter ->
            val tv = TextView(requireContext()).apply {
                text = letter.toString()
                setTextColor(textColor)
                textSize = 13f
                gravity = Gravity.CENTER
                isClickable = false
                isFocusable = false
            }
            strip.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        strip.visibility = View.VISIBLE

        // Single touch listener covers the whole strip so dragging works seamlessly
        strip.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN ||
                event.action == MotionEvent.ACTION_MOVE) {
                val fraction = (event.y / v.height).coerceIn(0f, 0.9999f)
                val letter   = letters[(fraction * letters.size).toInt()]
                val listIdx  = items.indexOfFirst { item ->
                    item.description.title?.toString()?.trimStart()
                        ?.firstOrNull()?.uppercaseChar() == letter
                }
                if (listIdx >= 0) {
                    (binding.browseList.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(listIdx, 0)
                }
            }
            true
        }
    }

    private fun updateSidebarState() {
        val b = _binding ?: return
        val isSubsonic = sourceRoot == MediaService.ONLINE_ROOT

        // Show/hide source-specific sidebar controls
        b.btnCatTracks.visibility       = if (isSubsonic) View.GONE else View.VISIBLE
        b.btnScan.visibility            = if (isSubsonic) View.GONE else View.VISIBLE
        b.btnSubsonicSettings.visibility = if (isSubsonic) View.VISIBLE else View.GONE

        // Back button visible when deeper than [source, category]
        val depth = stack.size
        b.btnBack.visibility     = if (depth > 2) View.VISIBLE else View.GONE
        b.backDivider.visibility = if (depth > 2) View.VISIBLE else View.GONE

        // Breadcrumb label
        if (depth > 2) {
            b.labelBreadcrumb.text       = stack.lastOrNull()?.second ?: ""
            b.labelBreadcrumb.visibility = View.VISIBLE
        } else {
            b.labelBreadcrumb.visibility = View.GONE
        }

        // Highlight active category button
        val accentColor    = resources.getColor(com.saicmotor.media.R.color.accent,        null)
        val secondaryColor = resources.getColor(com.saicmotor.media.R.color.text_secondary, null)
        listOf(
            b.btnCatArtists to MediaService.artistsId(sourceRoot),
            b.btnCatAlbums  to MediaService.albumsId(sourceRoot),
            b.btnCatTracks  to MediaService.tracksId(sourceRoot),
        ).forEach { (btn, id) ->
            btn.setTextColor(if (activeCategoryId == id) accentColor else secondaryColor)
        }
    }
}
