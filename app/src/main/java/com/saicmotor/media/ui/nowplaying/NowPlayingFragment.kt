package com.saicmotor.media.ui.nowplaying

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.palette.graphics.Palette
import coil.load
import com.saicmotor.media.R
import com.saicmotor.media.databinding.FragmentNowPlayingBinding
import com.saicmotor.media.ui.common.Format
import com.saicmotor.media.ui.common.Navigator
import com.saicmotor.media.ui.common.NowPlaying
import com.saicmotor.media.ui.common.Playback
import com.saicmotor.media.ui.common.PlayerState
import com.saicmotor.media.ui.common.Transport
import com.saicmotor.media.ui.common.coverCacheKey
import kotlinx.coroutines.launch

/**
 * The left-hand panel: the album cover, full height, with the song and its
 * controls over the lower part.  The shade behind the controls is taken from
 * the cover, so the panel changes colour with the music.
 */
class NowPlayingFragment : Fragment() {

    private var _b: FragmentNowPlayingBinding? = null
    private val b get() = _b!!

    private var current: NowPlaying? = null
    private var shownArt: String? = null
    private var panelColor = Color.BLACK
    private var colorAnimator: ValueAnimator? = null
    private var userSeeking = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentNowPlayingBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        panelColor = ContextCompat.getColor(requireContext(), R.color.panel_placeholder)
        applyPanelColor(panelColor)

        b.play.setOnClickListener     { Playback.playPause() }
        b.next.setOnClickListener     { Playback.next() }
        b.previous.setOnClickListener { Playback.previous() }
        b.emptyShuffle.setOnClickListener { Playback.shuffleLibrary() }
        // Tap the song to see its album, or the artist to see theirs.
        b.title.setOnClickListener  { current?.albumId?.let { (activity as? Navigator)?.openAlbum(it) } }
        b.album.setOnClickListener  { current?.albumId?.let { (activity as? Navigator)?.openAlbum(it) } }
        b.artist.setOnClickListener { current?.artistId?.let { (activity as? Navigator)?.openArtist(it) } }

        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) showTimes(progress.toLong(), bar.max.toLong())
            }
            override fun onStartTrackingTouch(bar: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                Playback.seekTo(bar.progress.toLong())
                userSeeking = false
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PlayerState.nowPlaying.collect { render(it) } }
                launch { PlayerState.transport.collect { renderTransport(it) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        colorAnimator?.cancel()
        _b = null
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    private fun render(np: NowPlaying?) {
        current = np
        val ctx = requireContext()
        if (np == null) {
            b.empty.visibility = View.VISIBLE
            b.info.visibility  = View.GONE
            b.cover.setImageDrawable(null)
            shownArt = null
            animatePanelTo(ContextCompat.getColor(ctx, R.color.panel_placeholder))
            return
        }
        b.empty.visibility = View.GONE
        b.info.visibility  = View.VISIBLE

        b.title.text  = np.title
        b.artist.text = np.artist
        b.artist.visibility = if (np.artist.isBlank()) View.GONE else View.VISIBLE
        renderAlbumLine()


        if (np.durationMs > 0) b.seek.max = np.durationMs.toInt()
        if (np.artUri != shownArt || b.cover.drawable == null) {
            shownArt = np.artUri
            loadArt(np.artUri)
        }
        updateProgress()
    }

    private fun renderAlbumLine() {
        val np = current ?: return
        val error = PlayerState.transport.value.error
        val ctx = requireContext()
        if (error != null) {
            b.album.text = error
            b.album.setTextColor(ContextCompat.getColor(ctx, R.color.panel_accent))
            b.album.visibility = View.VISIBLE
        } else {
            b.album.text = np.album
            b.album.setTextColor(ContextCompat.getColor(ctx, R.color.panel_ink_3))
            b.album.visibility = if (np.album.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun renderTransport(t: Transport) {
        b.play.setImageResource(if (t.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        b.play.contentDescription = getString(if (t.isPlaying) R.string.pause else R.string.play)

        renderAlbumLine()
        updateProgress()
    }

    private fun updateProgress() {
        val binding = _b ?: return
        if (userSeeking) return
        val np = current ?: return
        val duration = np.durationMs.coerceAtLeast(0)
        val position = PlayerState.transport.value.currentPosition().coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        if (duration > 0) {
            binding.seek.max = duration.toInt()
            binding.seek.progress = position.toInt()
        } else {
            binding.seek.progress = 0
        }
        showTimes(position, duration)
    }

    private fun showTimes(position: Long, duration: Long) {
        b.position.text  = Format.clock(position)
        b.remaining.text = if (duration > 0) "-" + Format.clock(duration - position) else ""
    }

    // ── Cover and colour ──────────────────────────────────────────────────

    private fun loadArt(url: String?) {
        val ctx = requireContext()
        if (url == null) {
            b.cover.setImageResource(R.drawable.panel_placeholder)
            animatePanelTo(ContextCompat.getColor(ctx, R.color.panel_placeholder))
            return
        }
        b.cover.load(url) {
            allowHardware(false)   // Palette needs to read the pixels
            crossfade(300)
            coverCacheKey(url)?.let { memoryCacheKey(it); diskCacheKey(it) }
            error(R.drawable.panel_placeholder)
            listener(
                onSuccess = { _, result ->
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    animatePanelTo(bitmap?.let(::panelColorFor) ?: ContextCompat.getColor(ctx, R.color.panel_placeholder))
                },
                onError = { _, _ ->
                    animatePanelTo(ContextCompat.getColor(ctx, R.color.panel_placeholder))
                }
            )
        }
    }

    private fun animatePanelTo(target: Int) {
        if (target == panelColor) return
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofArgb(panelColor, target).apply {
            duration = 450
            addUpdateListener {
                panelColor = it.animatedValue as Int
                applyPanelColor(panelColor)
            }
            start()
        }
    }

    private fun applyPanelColor(color: Int) {
        val binding = _b ?: return
        binding.panel.setBackgroundColor(color)
        // Clear over the top of the cover, solid behind the controls.
        binding.scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                withAlpha(color, 0f),
                withAlpha(color, 0.10f),
                withAlpha(color, 0.72f),
                withAlpha(color, 0.94f),
                color
            )
        )
    }

    private fun withAlpha(color: Int, alpha: Float) = ColorUtils.setAlphaComponent(color, (alpha * 255).toInt())

    companion object {
        /**
         * A deep shade of the cover's own colour, dark enough for white text
         * in either skin, and not so saturated it glows at night.
         */
        fun panelColorFor(bitmap: Bitmap): Int {
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            val swatch = palette.darkMutedSwatch ?: palette.mutedSwatch
                ?: palette.darkVibrantSwatch ?: palette.dominantSwatch
                ?: return Color.rgb(16, 19, 24)
            val hsl = swatch.hsl.copyOf()
            hsl[1] = hsl[1].coerceAtMost(0.5f)
            hsl[2] = hsl[2].coerceIn(0.10f, 0.17f)
            return ColorUtils.HSLToColor(hsl)
        }
    }
}
