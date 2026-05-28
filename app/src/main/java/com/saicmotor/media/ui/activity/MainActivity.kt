package com.saicmotor.media.ui.activity

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import coil.load
import com.saicmotor.media.MyApplication
import com.saicmotor.media.R
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.databinding.ActivityMainBinding
import com.saicmotor.media.databinding.DialogSubsonicSettingsBinding
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.subsonic.SubsonicClient
import com.saicmotor.media.ui.fragment.BrowseFragment
import com.saicmotor.media.ui.fragment.NowPlayingFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaBrowser: MediaBrowserCompat

    private var skinReceiverRegistered = false

    private val skinReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            syncSkinTheme()
        }
    }

    /**
     * Reads SKIN_THEME_CONFIG and aligns AppCompat's default night mode with
     * it.  If the value differs from the current default the activity will be
     * recreated by AppCompat — fragment state (back stack, BrowseFragment's
     * navigation stack, current source) is preserved via savedInstanceState.
     *
     * Called from three places to cover every path that can desync us from
     * SKIN_THEME_CONFIG: before super.onCreate (catches a stale value cached
     * by MyApplication when the process was kept alive across a skin change),
     * onStart (catches broadcasts missed while the activity was stopped),
     * and the skinReceiver (catches live changes while we're foregrounded).
     */
    private fun syncSkinTheme() {
        val newMode = (application as MyApplication).currentNightMode()
        if (AppCompatDelegate.getDefaultNightMode() != newMode) {
            AppCompatDelegate.setDefaultNightMode(newMode)
        }
    }

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val token = mediaBrowser.sessionToken
            val controller = MediaControllerCompat(this@MainActivity, token)
            MediaControllerCompat.setMediaController(this@MainActivity, controller)
            controller.registerCallback(controllerCallback)
            controllerCallback.onMetadataChanged(controller.metadata)
            controllerCallback.onPlaybackStateChanged(controller.playbackState)
            nowPlayingFragment()?.onControllerReady()
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            val title  = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)  ?: ""
            val artist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""
            binding.miniTitle.text  = title
            binding.miniArtist.text = artist
            val miniVis = if (title.isNotEmpty()) View.VISIBLE else View.GONE
            // Only show if Now Playing isn't already fullscreen
            if (supportFragmentManager.findFragmentByTag(TAG_NOW_PLAYING) == null) {
                binding.miniPlayer.visibility = miniVis
                binding.miniPlayerDivider.visibility = miniVis
            }

            val artUriStr = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI)
            binding.miniAlbumArt.load(artUriStr?.let { Uri.parse(it) }) {
                placeholder(R.drawable.ic_album_art_placeholder)
                error(R.drawable.ic_album_art_placeholder)
            }
        }
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
            binding.miniBtnPlayPause.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the activity's AppCompat delegate
        // applies the correct night mode during its initial inflation.
        syncSkinTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start MediaService explicitly so it survives the activity going to the background.
        // Without this the service only runs while a client is bound; once onStop() disconnects
        // the MediaBrowserCompat the service is destroyed and playback stops.
        startForegroundService(Intent(this, MediaService::class.java))

        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, MediaService::class.java),
            connectionCallback,
            null
        )

        setupClickListeners()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_container, BrowseFragment(), TAG_BROWSE)
                .commit()
            highlightSource(MediaService.USB1_ROOT)
        } else {
            // After recreate (e.g. SKIN_THEME_CONFIG flip) restore the sidebar
            // highlight and the fullscreen overlay visibility — the fragments
            // themselves were restored by FragmentManager.
            highlightSource(browseFragment()?.sourceRoot ?: MediaService.USB1_ROOT)
            binding.fullscreenContainer.visibility =
                if (supportFragmentManager.findFragmentByTag(TAG_NOW_PLAYING) != null)
                    View.VISIBLE else View.GONE
        }

        // Hide the full-screen overlay as soon as Now Playing is popped from
        // the back stack — covers both the hardware back button and the in-app
        // close button (which both call popBackStack()).
        supportFragmentManager.addOnBackStackChangedListener {
            val nowPlayingVisible =
                supportFragmentManager.findFragmentByTag(TAG_NOW_PLAYING) != null
            binding.fullscreenContainer.visibility =
                if (nowPlayingVisible) View.VISIBLE else View.GONE
            if (!nowPlayingVisible) showMiniPlayer()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!skinReceiverRegistered) {
            registerReceiver(skinReceiver, IntentFilter("com.saicmotor.changeSkin"))
            skinReceiverRegistered = true
        }
        // Catch any skin change that happened while we were stopped — the
        // broadcast was dropped because the receiver was unregistered.
        syncSkinTheme()
        mediaBrowser.connect()
    }

    override fun onStop() {
        super.onStop()
        if (skinReceiverRegistered) {
            unregisterReceiver(skinReceiver)
            skinReceiverRegistered = false
        }
        MediaControllerCompat.getMediaController(this)?.unregisterCallback(controllerCallback)
        mediaBrowser.disconnect()
    }

    override fun onBackPressed() {
        val browse = browseFragment()
        if (supportFragmentManager.backStackEntryCount > 0) {
            // The OnBackStackChangedListener handles hiding the overlay and
            // restoring the mini player once the pop completes.
            supportFragmentManager.popBackStack()
        } else if (browse?.navigateBack() == true) {
            // handled
        } else {
            super.onBackPressed()
        }
    }

    private fun setupClickListeners() {
        // Source tab clicks
        binding.btnSourceUsb1.setOnClickListener   { switchSource(MediaService.USB1_ROOT) }
        binding.btnSourceOnline.setOnClickListener { switchSource(MediaService.ONLINE_ROOT) }
        binding.btnSourceBt.setOnClickListener     { switchSource(MediaService.BT_ROOT) }

        // Long-press on Subsonic tab → re-open settings
        binding.btnSourceOnline.setOnLongClickListener {
            showSubsonicSettings()
            true
        }

        // Now Playing pill
        binding.btnNowPlaying.setOnClickListener { showNowPlaying() }

        // Mini player controls
        binding.miniBtnPlayPause.setOnClickListener {
            val c = MediaControllerCompat.getMediaController(this)
            if (c?.playbackState?.state == PlaybackStateCompat.STATE_PLAYING)
                c.transportControls.pause() else c?.transportControls?.play()
        }
        binding.miniBtnPrevious.setOnClickListener {
            MediaControllerCompat.getMediaController(this)?.transportControls?.skipToPrevious()
        }
        binding.miniBtnNext.setOnClickListener {
            MediaControllerCompat.getMediaController(this)?.transportControls?.skipToNext()
        }
        binding.miniPlayer.setOnClickListener { showNowPlaying() }
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    private fun switchSource(root: String) {
        highlightSource(root)
        // Tell MediaService which source is now in the foreground so it can
        // route transport controls correctly (ExoPlayer vs AVRCP).
        startService(
            Intent(this, MediaService::class.java)
                .setAction(MediaService.ACTION_SET_SOURCE)
                .putExtra(MediaService.EXTRA_SOURCE, root)
        )
        if (root == MediaService.BT_ROOT) {
            // Bluetooth has no browse tree — jump straight to the Now Playing screen
            showNowPlaying()
            return
        }
        browseFragment()?.sourceRoot = root
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            showMiniPlayer()
        }
        // Auto-prompt settings if Subsonic has never been configured
        if (root == MediaService.ONLINE_ROOT && !SubsonicSettings.load(this).isValid) {
            showSubsonicSettings()
        }
    }

    private fun showMiniPlayer() {
        val hasTrack = binding.miniTitle.text.isNotEmpty()
        val vis = if (hasTrack) View.VISIBLE else View.GONE
        binding.miniPlayer.visibility = vis
        binding.miniPlayerDivider.visibility = vis
    }

    internal fun showNowPlaying() {
        if (supportFragmentManager.findFragmentByTag(TAG_NOW_PLAYING) != null) return
        binding.miniPlayer.visibility = View.GONE
        binding.miniPlayerDivider.visibility = View.GONE
        binding.fullscreenContainer.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.fullscreen_container, NowPlayingFragment(), TAG_NOW_PLAYING)
            .addToBackStack(null)
            .commit()
        binding.root.post {
            nowPlayingFragment()?.onControllerReady()
        }
    }

    /**
     * Shows the Subsonic server configuration dialog.
     * Pre-fills current settings; saves on OK and triggers a re-browse
     * if the Subsonic source is currently active.
     */
    internal fun showSubsonicSettings() {
        val cfg         = SubsonicSettings.load(this)
        val dialogBinding = DialogSubsonicSettingsBinding.inflate(layoutInflater)

        dialogBinding.editUrl.setText(cfg.url)
        dialogBinding.editUsername.setText(cfg.username)
        dialogBinding.editPassword.setText(cfg.password)

        AlertDialog.Builder(this)
            .setTitle(R.string.subsonic_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newCfg = SubsonicSettings.Config(
                    url      = dialogBinding.editUrl.text.toString().trim().trimEnd('/'),
                    username = dialogBinding.editUsername.text.toString().trim(),
                    password = dialogBinding.editPassword.text.toString()
                )
                SubsonicSettings.save(this, newCfg)
                SubsonicClient.invalidateCache()
                // Re-browse if the Subsonic source is already selected
                browseFragment()?.let { fragment ->
                    if (fragment.sourceRoot == MediaService.ONLINE_ROOT) {
                        fragment.sourceRoot = MediaService.ONLINE_ROOT
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun highlightSource(root: String) {
        val accent    = getColor(R.color.accent)
        val secondary = getColor(R.color.text_secondary)
        binding.btnSourceUsb1.setTextColor(   if (root == MediaService.USB1_ROOT)   accent else secondary)
        binding.btnSourceOnline.setTextColor( if (root == MediaService.ONLINE_ROOT) accent else secondary)
        binding.btnSourceBt.setTextColor(     if (root == MediaService.BT_ROOT)     accent else secondary)
    }

    private fun browseFragment() =
        supportFragmentManager.findFragmentByTag(TAG_BROWSE) as? BrowseFragment

    private fun nowPlayingFragment() =
        supportFragmentManager.findFragmentByTag(TAG_NOW_PLAYING) as? NowPlayingFragment

    companion object {
        private const val TAG_BROWSE      = "browse"
        private const val TAG_NOW_PLAYING = "now_playing"
    }
}
