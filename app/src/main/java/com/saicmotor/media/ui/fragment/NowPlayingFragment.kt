package com.saicmotor.media.ui.fragment

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import com.saicmotor.media.ui.util.BlurTransformation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import coil.load
import com.saicmotor.media.R
import com.saicmotor.media.databinding.FragmentNowPlayingBinding
import com.saicmotor.media.service.MediaService

class NowPlayingFragment : Fragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?)   = updateMetadata(metadata)
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = updateState(state)
        override fun onShuffleModeChanged(shuffleMode: Int)              = updateShuffleButton(shuffleMode)
        override fun onRepeatModeChanged(repeatMode: Int)                = updateRepeatButton(repeatMode)
        override fun onExtrasChanged(extras: Bundle?)                    = updateSourceVisibility(extras)
    }

    private val seekUpdater = object : Runnable {
        override fun run() {
            if (!isSeeking) {
                val c = controller() ?: return
                val pos = c.playbackState?.position ?: 0L
                val dur = c.metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
                if (dur > 0) {
                    _binding?.seekBar?.max      = dur.toInt()
                    _binding?.seekBar?.progress = pos.toInt()
                    _binding?.timePosition?.text = formatMs(pos)
                    _binding?.timeDuration?.text = formatMs(dur)
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener { parentFragmentManager.popBackStack() }

        binding.btnPrevious.setOnClickListener { controller()?.transportControls?.skipToPrevious() }
        binding.btnNext.setOnClickListener     { controller()?.transportControls?.skipToNext() }

        binding.btnPlayPause.setOnClickListener {
            val c = controller() ?: return@setOnClickListener
            if (c.playbackState?.state == PlaybackStateCompat.STATE_PLAYING)
                c.transportControls.pause() else c.transportControls.play()
        }

        binding.btnShuffle.setOnClickListener {
            val c = controller() ?: return@setOnClickListener
            val next = if (c.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_NONE)
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            else
                PlaybackStateCompat.SHUFFLE_MODE_NONE
            c.transportControls.setShuffleMode(next)
        }

        binding.btnRepeat.setOnClickListener {
            val c = controller() ?: return@setOnClickListener
            val next = when (c.repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_NONE -> PlaybackStateCompat.REPEAT_MODE_ALL
                PlaybackStateCompat.REPEAT_MODE_ALL  -> PlaybackStateCompat.REPEAT_MODE_ONE
                else                                 -> PlaybackStateCompat.REPEAT_MODE_NONE
            }
            c.transportControls.setRepeatMode(next)
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                controller()?.transportControls?.seekTo(sb.progress.toLong())
                isSeeking = false
            }
        })

        onControllerReady()
    }

    fun onControllerReady() {
        val c = controller() ?: return
        c.registerCallback(controllerCallback)
        updateMetadata(c.metadata)
        updateState(c.playbackState)
        updateShuffleButton(c.shuffleMode)
        updateRepeatButton(c.repeatMode)
        updateSourceVisibility(c.extras)
    }

    override fun onStart() {
        super.onStart()
        handler.post(seekUpdater)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(seekUpdater)
        controller()?.unregisterCallback(controllerCallback)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun controller() = MediaControllerCompat.getMediaController(requireActivity())

    private fun updateMetadata(meta: MediaMetadataCompat?) {
        val b = _binding ?: return
        b.trackTitle.text  = meta?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)  ?: "—"
        b.trackArtist.text = meta?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "—"
        b.trackAlbum.text  = meta?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)  ?: ""

        val artUriStr = meta?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI)
        val artUri    = artUriStr?.let { Uri.parse(it) }

        // Foreground album art
        b.albumArt.load(artUri) {
            placeholder(R.drawable.ic_album_art_placeholder)
            error(R.drawable.ic_album_art_placeholder)
            crossfade(true)
        }

        // Ambient background — load at a moderate resolution then apply a
        // real Gaussian blur (RenderScript ScriptIntrinsicBlur, radius 25).
        // The ImageView's centerCrop upscale from ~300 px to full screen is
        // smooth because the blur has already removed all high-frequency detail.
        val ctx = context ?: return
        val fallback = ColorDrawable(resources.getColor(R.color.bg_dark, null))
        b.bgArt.load(artUri) {
            size(300, 300)
            transformations(BlurTransformation(ctx, radius = 25f))
            crossfade(500)
            placeholder(fallback)
            fallback(fallback)
            error(fallback)
        }
    }

    private fun updateState(state: PlaybackStateCompat?) {
        val b = _binding ?: return
        val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
        b.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        b.btnPlayPause.setColorFilter(resources.getColor(R.color.on_accent, null))
    }

    private fun updateShuffleButton(shuffleMode: Int) {
        val b = _binding ?: return
        val active = shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE
        b.btnShuffle.setColorFilter(
            resources.getColor(if (active) R.color.accent else R.color.text_hint, null)
        )
    }

    /**
     * Reads the active source from the session extras (published by MediaService)
     * and hides shuffle / repeat when we're forwarding to a phone over AVRCP —
     * neither command is supported by the AVRCP 1.3 PassThrough vocabulary, so
     * the buttons would just sit there doing nothing.  Seek/progress is hidden
     * too because BT track positions aren't reported reliably enough to bother.
     */
    private fun updateSourceVisibility(extras: Bundle?) {
        val b = _binding ?: return
        val source = extras?.getString(MediaService.EXTRA_ACTIVE_SOURCE)
        val isBt   = source == MediaService.BT_ROOT
        val vis    = if (isBt) View.GONE else View.VISIBLE
        b.btnShuffle.visibility = vis
        b.btnRepeat.visibility  = vis
        b.seekBar.visibility    = vis
        b.timePosition.visibility = vis
        b.timeDuration.visibility = vis
    }

    private fun updateRepeatButton(repeatMode: Int) {
        val b = _binding ?: return
        when (repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> {
                b.btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                b.btnRepeat.setColorFilter(resources.getColor(R.color.accent, null))
            }
            PlaybackStateCompat.REPEAT_MODE_ALL -> {
                b.btnRepeat.setImageResource(R.drawable.ic_repeat)
                b.btnRepeat.setColorFilter(resources.getColor(R.color.accent, null))
            }
            else -> {
                b.btnRepeat.setImageResource(R.drawable.ic_repeat)
                b.btnRepeat.setColorFilter(resources.getColor(R.color.text_hint, null))
            }
        }
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
