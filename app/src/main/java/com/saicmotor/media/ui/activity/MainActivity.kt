package com.saicmotor.media.ui.activity

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.saicmotor.media.MyApplication
import com.saicmotor.media.R
import com.saicmotor.media.data.Library
import com.saicmotor.media.databinding.ActivityMainBinding
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.ui.common.Navigator
import com.saicmotor.media.ui.common.NowPlaying
import com.saicmotor.media.ui.common.Playback
import com.saicmotor.media.ui.common.PlayerState
import com.saicmotor.media.ui.detail.AlbumFragment
import com.saicmotor.media.ui.detail.ArtistFragment
import com.saicmotor.media.ui.detail.GenreFragment
import com.saicmotor.media.ui.detail.PlaylistFragment
import com.saicmotor.media.ui.home.HomeFragment
import com.saicmotor.media.ui.library.LibraryFragment
import com.saicmotor.media.ui.queue.QueueFragment
import com.saicmotor.media.ui.search.SearchFragment
import com.saicmotor.media.ui.settings.SettingsFragment

/**
 * Split screen: the now-playing panel on the left, the library on the right
 * with Home / Search / Library tabs, Up next and Settings.  Album, artist
 * and genre pages open over the current tab; Back returns.
 */
class MainActivity : AppCompatActivity(), Navigator {

    enum class Destination { HOME, SEARCH, LIBRARY, QUEUE, SETTINGS }

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaBrowser: MediaBrowserCompat
    private var destination = Destination.HOME

    private var skinReceiverRegistered = false
    private val skinReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = syncSkinTheme()
    }

    /**
     * Reads SKIN_THEME_CONFIG and aligns AppCompat's default night mode with
     * it.  If the value differs the activity is recreated by AppCompat; pages
     * and the open tab are restored from savedInstanceState.
     *
     * Called before super.onCreate (catches a stale value cached by
     * MyApplication), from onStart (catches broadcasts missed while stopped)
     * and by skinReceiver (live changes while in the foreground).
     */
    private fun syncSkinTheme() {
        val newMode = (application as MyApplication).currentNightMode()
        if (AppCompatDelegate.getDefaultNightMode() != newMode) {
            AppCompatDelegate.setDefaultNightMode(newMode)
        }
    }

    // ── Session connection ────────────────────────────────────────────────

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val controller = MediaControllerCompat(this@MainActivity, mediaBrowser.sessionToken)
            MediaControllerCompat.setMediaController(this@MainActivity, controller)
            Playback.controller = controller
            controller.registerCallback(controllerCallback)
            PlayerState.nowPlaying.value = NowPlaying.from(controller.metadata)
            PlayerState.updateFrom(controller.playbackState)
            PlayerState.updateQueue(controller.queue)
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            PlayerState.nowPlaying.value = NowPlaying.from(metadata)
        }
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            PlayerState.updateFrom(state)
        }
        override fun onQueueChanged(queue: MutableList<MediaSessionCompat.QueueItem>?) {
            PlayerState.updateQueue(queue)
        }
        override fun onSessionDestroyed() {
            Playback.controller = null
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate so the first inflation uses the right skin.
        syncSkinTheme()
        super.onCreate(savedInstanceState)
        Library.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start MediaService explicitly so it outlives this screen; a bound-only
        // service would stop when the activity goes to the background.
        startForegroundService(Intent(this, MediaService::class.java))
        mediaBrowser = MediaBrowserCompat(
            this, ComponentName(this, MediaService::class.java), connectionCallback, null
        )

        binding.tabHome.setOnClickListener     { show(Destination.HOME) }
        binding.tabSearch.setOnClickListener   { show(Destination.SEARCH) }
        binding.tabLibrary.setOnClickListener  { show(Destination.LIBRARY) }
        binding.tabQueue.setOnClickListener    { show(Destination.QUEUE) }
        binding.tabSettings.setOnClickListener { show(Destination.SETTINGS) }
        binding.back.setOnClickListener        { supportFragmentManager.popBackStack() }
        supportFragmentManager.addOnBackStackChangedListener { updateBack() }

        if (savedInstanceState == null) {
            // First run: go straight to the server settings.
            show(if (Library.isConfigured) Destination.HOME else Destination.SETTINGS)
        } else {
            destination = savedInstanceState.getString(KEY_DESTINATION)
                ?.let { name -> Destination.values().firstOrNull { it.name == name } }
                ?: Destination.HOME
            highlight()
            updateBack()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DESTINATION, destination.name)
    }

    override fun onStart() {
        super.onStart()
        if (!skinReceiverRegistered) {
            registerReceiver(skinReceiver, IntentFilter("com.saicmotor.changeSkin"))
            skinReceiverRegistered = true
        }
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
        Playback.controller = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            supportFragmentManager.backStackEntryCount > 0 -> supportFragmentManager.popBackStack()
            destination != Destination.HOME -> show(Destination.HOME)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────

    /** Switches tab, closing any album/artist pages that were open. */
    fun show(dest: Destination) {
        val fm = supportFragmentManager
        if (fm.backStackEntryCount > 0) fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        if (dest == destination && fm.findFragmentById(R.id.content) != null) return
        destination = dest
        val page: Fragment = when (dest) {
            Destination.HOME     -> HomeFragment()
            Destination.SEARCH   -> SearchFragment()
            Destination.LIBRARY  -> LibraryFragment()
            Destination.QUEUE    -> QueueFragment()
            Destination.SETTINGS -> SettingsFragment()
        }
        fm.beginTransaction().replace(R.id.content, page).commit()
        highlight()
    }

    private fun open(page: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, page)
            .addToBackStack(null)
            .commit()
    }

    override fun openAlbum(albumId: String)       = open(AlbumFragment.newInstance(albumId))
    override fun openArtist(artistId: String)     = open(ArtistFragment.newInstance(artistId))
    override fun openPlaylist(playlistId: String) = open(PlaylistFragment.newInstance(playlistId))
    override fun openGenre(name: String)          = open(GenreFragment.newInstance(name))
    override fun openSettings()                   = show(Destination.SETTINGS)
    override fun openHome()                       = show(Destination.HOME)

    private fun highlight() {
        binding.tabHome.isSelected     = destination == Destination.HOME
        binding.tabSearch.isSelected   = destination == Destination.SEARCH
        binding.tabLibrary.isSelected  = destination == Destination.LIBRARY
        binding.tabQueue.isSelected    = destination == Destination.QUEUE
        binding.tabSettings.isSelected = destination == Destination.SETTINGS
    }

    private fun updateBack() {
        binding.back.visibility =
            if (supportFragmentManager.backStackEntryCount > 0) View.VISIBLE else View.GONE
    }

    companion object {
        private const val KEY_DESTINATION = "destination"
    }
}
