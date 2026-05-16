package com.saicmotor.media.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import java.io.File
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.saicmotor.media.R
import com.saicmotor.media.bluetooth.BluetoothMediaManager
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.subsonic.SubsonicAlbum
import com.saicmotor.media.subsonic.SubsonicClient
import com.saicmotor.media.ui.activity.MainActivity
import com.saicmotor.media.usb.UsbScanner
import com.saicmotor.media.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class MediaService : MediaBrowserServiceCompat() {

    companion object {
        const val ROOT_ID     = "root"
        const val USB1_ROOT   = "usb1"
        const val ONLINE_ROOT = "online"
        const val BT_ROOT     = "bt"

        private const val TYPE_ARTISTS = "artists"
        private const val TYPE_ALBUMS  = "albums"
        private const val TYPE_TRACKS  = "tracks"
        private const val TYPE_ARTIST  = "artist"
        private const val TYPE_ALBUM   = "album"

        const val USB_FILE_PREFIX      = "file:"
        const val SUBSONIC_SONG_PREFIX = "sub:"

        fun artistsId(root: String) = "$root/$TYPE_ARTISTS"
        fun albumsId(root: String)  = "$root/$TYPE_ALBUMS"
        fun tracksId(root: String)  = "$root/$TYPE_TRACKS"
        fun artistId(root: String, id: Long) = "$root/$TYPE_ARTIST/$id"
        fun albumId(root: String, id: Long)  = "$root/$TYPE_ALBUM/$id"

        fun subsonicArtistNodeId(id: String) = "$ONLINE_ROOT/$TYPE_ARTIST/$id"
        fun subsonicAlbumNodeId(id: String)  = "$ONLINE_ROOT/$TYPE_ALBUM/$id"

        fun usbArtistNodeId(artistName: String) =
            "$USB1_ROOT/$TYPE_ARTIST/${Uri.encode(artistName)}"
        fun usbAlbumNodeId(artistName: String, albumName: String) =
            "$USB1_ROOT/$TYPE_ALBUM/${Uri.encode(artistName)}:${Uri.encode(albumName)}"

        private val STORAGE_SKIP = setOf("self", "emulated")

        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID      = "media_playback"

        const val ACTION_PLAY_PAUSE = "com.saicmotor.media.PLAY_PAUSE"
        const val ACTION_PREVIOUS   = "com.saicmotor.media.PREVIOUS"
        const val ACTION_NEXT       = "com.saicmotor.media.NEXT"
        const val ACTION_SCAN_USB   = "com.saicmotor.media.SCAN_USB"
        const val ACTION_PLAY_ALL   = "com.saicmotor.media.PLAY_ALL"
        const val ACTION_SET_SOURCE = "com.saicmotor.media.SET_SOURCE"
        const val EXTRA_PARENT_ID   = "parent_id"
        const val EXTRA_SOURCE      = "source"

        // SharedPreferences keys for persisted playback preferences
        private const val PREF_SHUFFLE     = "shuffle_enabled"
        private const val PREF_REPEAT_MODE = "repeat_mode"

        // Hidden framework flag (android.media.session.MediaSession#FLAG_EXCLUSIVE_GLOBAL_PRIORITY).
        // Reserved for system apps — accessible to us because we ship with
        // sharedUserId="android.uid.system".  Without this, the SAIC radio
        // app's globally-priority session intercepts steering-wheel media
        // keys before they reach us, and they vanish (radio is inactive).
        private const val FLAG_EXCLUSIVE_GLOBAL_PRIORITY = 0x10000

        // SAIC's custom AudioAttributes Bundle key + values.  The launcher's
        // OnAudioSourceChangeCallBack reads this from our AudioFocusRequest
        // and uses it (together with the calling package name) to decide
        // which MediaController to bind to its title bar / now playing tile.
        //
        // From com.saicmotor.launcher.model.MediaModel (decompiled):
        //   pkg == "com.saicmotor.media"  →  carSourceType=6 → mMusicController
        //                                 →  carSourceType=7 → mMusic2Controller
        //                                 →  carSourceType=5 → mBTMusicController
        private const val CAR_SOURCE_KEY            = "key_car_source_type"
        private const val CAR_SOURCE_TYPE_USB1      = 6
        private const val CAR_SOURCE_TYPE_USB2      = 7
        private const val CAR_SOURCE_TYPE_BT_MUSIC  = 5

        // All actions exposed to external controllers (launcher, BT, etc.)
        private const val SESSION_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
            PlaybackStateCompat.ACTION_SET_REPEAT_MODE
    }

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSessionCompat
    private lateinit var btManager: BluetoothMediaManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val prefs by lazy { getSharedPreferences("playback_prefs", MODE_PRIVATE) }

    /** Tracks which source is currently in the foreground so transport controls
     *  can be routed correctly — ExoPlayer for USB/Online, AVRCP for Bluetooth. */
    private var activeSource: String = USB1_ROOT

    // Unified audio focus management.  We always hold focus while a source is
    // active so that:
    //   - the A2DP stream has a claimant and routes to the car's speakers (BT)
    //   - the SAIC launcher's OnAudioSourceChangeCallBack sees us as the active
    //     media source and binds its title-bar UI to our MediaSession (all sources)
    //
    // The focus request carries a Bundle with key_car_source_type set per the
    // active source — that's the value the launcher reads to decide which of
    // its internal MediaController references to bind to.
    private var mediaFocusRequest: AudioFocusRequest? = null

    /** True if we paused ExoPlayer on a transient focus loss and should
     *  resume on AUDIOFOCUS_GAIN. */
    private var pausedByFocusLoss = false

    private val mediaFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (activeSource) {
            BT_ROOT -> handleBtFocusChange(change)
            else    -> handleExoFocusChange(change)
        }
    }

    private fun handleBtFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Another app (navigation, phone call, another media app) took
                // the speakers — tell the phone to stop streaming so its audio
                // doesn't bleed under whatever is talking on top.
                btManager.sendPassThrough(BluetoothMediaManager.PASSTHRU_PAUSE)
                session.setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setState(PlaybackStateCompat.STATE_PAUSED, 0L, 1.0f)
                        .setActions(SESSION_ACTIONS)
                        .build()
                )
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Focus returned (e.g. navigation finished) — resume the phone.
                btManager.sendPassThrough(BluetoothMediaManager.PASSTHRU_PLAY)
                session.setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
                        .setActions(SESSION_ACTIONS)
                        .build()
                )
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK — let the system handle volume
            // ducking on the A2DP stream; no need to pause the phone.
        }
    }

    private fun handleExoFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    player.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (player.isPlaying) {
                    pausedByFocusLoss = true
                    player.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent — don't auto-resume.
                pausedByFocusLoss = false
                if (player.isPlaying) player.pause()
            }
        }
    }

    private fun carSourceTypeForActive(): Int = when (activeSource) {
        BT_ROOT -> CAR_SOURCE_TYPE_BT_MUSIC
        else    -> CAR_SOURCE_TYPE_USB1
    }

    /**
     * Builds the AudioAttributes used for our focus request, attaching the
     * SAIC-custom Bundle that identifies our carSourceType.
     *
     * `AudioAttributes.Builder.addBundle(Bundle)` is `@hide` in stock AOSP
     * but exposed in SAIC's customised framework — we reach it via reflection.
     * Our app runs as system UID (sharedUserId="android.uid.system") so the
     * call succeeds.  If the method ever disappears focus still works; the
     * launcher just won't switch its tile to us.
     */
    private fun buildMediaAudioAttributes(carSourceType: Int): android.media.AudioAttributes {
        val builder = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)

        try {
            val bundle = Bundle().apply { putInt(CAR_SOURCE_KEY, carSourceType) }
            val addBundle = android.media.AudioAttributes.Builder::class.java
                .getDeclaredMethod("addBundle", Bundle::class.java)
            addBundle.invoke(builder, bundle)
        } catch (_: Throwable) {
            // SAIC hidden API not present — carry on without the bundle.
        }

        return builder.build()
    }

    private fun requestMediaFocus(): Boolean {
        // If we already hold focus for the current source, nothing to do.
        if (mediaFocusRequest != null) return true

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(buildMediaAudioAttributes(carSourceTypeForActive()))
            .setOnAudioFocusChangeListener(mediaFocusListener)
            .build()
        mediaFocusRequest = request

        val am = getSystemService(AudioManager::class.java)
        return am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonMediaFocus() {
        mediaFocusRequest?.let {
            getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)
        }
        mediaFocusRequest = null
        pausedByFocusLoss = false
    }

    // ── Mount receiver ─────────────────────────────────────────────────────

    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            UsbScanner.invalidate()
            serviceScope.launch {
                notifyChildrenChanged(USB1_ROOT)
                notifyChildrenChanged(ROOT_ID)
            }
        }
    }

    private fun usbPaths(): List<String> =
        File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name !in STORAGE_SKIP }
            ?.sortedBy { it.name }
            ?.map { it.absolutePath }
            ?: emptyList()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // We handle audio focus ourselves so we can attach the SAIC
                // key_car_source_type Bundle to the focus request — without
                // that the launcher doesn't recognise us as the active media
                // source and steering-wheel keys / now playing tile stay
                // pointed at whichever app was active before.
                /* handleAudioFocus = */ false
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playerListener)

        session = MediaSessionCompat(this, "MediaService").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                FLAG_EXCLUSIVE_GLOBAL_PRIORITY
            )
            setCallback(SessionCompatCallback())
            setPlaybackState(buildPlaybackState())
            isActive = true
        }

        // Restore persisted shuffle / repeat preferences from the previous session.
        // Must come after session is initialised — setting these on the player
        // fires playerListener callbacks that call session.setShuffleMode() /
        // session.setRepeatMode(), so session must exist first.
        player.shuffleModeEnabled = prefs.getBoolean(PREF_SHUFFLE, false)
        player.repeatMode         = prefs.getInt(PREF_REPEAT_MODE, Player.REPEAT_MODE_OFF)

        setSessionToken(session.sessionToken)
        startForeground(NOTIFICATION_ID, buildNotification())

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        registerReceiver(mountReceiver, filter)

        UsbScanner.init(this)

        btManager = BluetoothMediaManager(this).apply {
            listener = btListener
            register()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
            ACTION_PREVIOUS   -> player.seekToPreviousMediaItem()
            ACTION_NEXT       -> player.seekToNextMediaItem()
            ACTION_SCAN_USB   -> {
                UsbScanner.invalidate()
                serviceScope.launch {
                    notifyChildrenChanged(USB1_ROOT)
                    notifyChildrenChanged(ROOT_ID)
                }
            }
            ACTION_SET_SOURCE -> {
                val src = intent.getStringExtra(EXTRA_SOURCE) ?: return START_STICKY
                activateSource(src)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(mountReceiver)
        btManager.unregister()
        abandonMediaFocus()
        session.release()
        player.release()
        serviceScope.cancel()
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    // ── Source management ──────────────────────────────────────────────────

    private fun activateSource(src: String) {
        if (activeSource == src) return
        val prev = activeSource
        activeSource = src

        // Drop the previous source's focus (with its now-stale carSourceType)
        // before switching.  When leaving BT we also have to tell the phone to
        // stop streaming first, otherwise A2DP audio bleeds under whatever
        // source we're switching to.
        if (prev == BT_ROOT) {
            btManager.sendPassThrough(BluetoothMediaManager.PASSTHRU_PAUSE)
        }
        abandonMediaFocus()

        when (src) {
            BT_ROOT -> {
                // BT is now active.  Stop ExoPlayer if it was running and grab
                // focus straight away so the A2DP stream has a claimant and
                // routes to the car's speakers.
                if (player.isPlaying) player.pause()
                requestMediaFocus()
                session.setPlaybackState(buildPlaybackState())
            }
            else -> {
                // USB / Online: focus will be requested lazily when playback
                // actually starts (onIsPlayingChanged), so we don't grab the
                // speakers from another app while we're idle.
            }
        }
    }

    // ── Bluetooth listener ─────────────────────────────────────────────────

    private val btListener = object : BluetoothMediaManager.Listener {
        override fun onBtConnectionChanged(connected: Boolean, deviceName: String?) {
            serviceScope.launch { notifyChildrenChanged(ROOT_ID) }
            // If BT disconnects while it is the active source, release focus and
            // fall back to USB so the ExoPlayer controls work again.
            if (!connected && activeSource == BT_ROOT) {
                // No need to send PAUSE — the device is gone — but clean up focus.
                abandonMediaFocus()
                activeSource = USB1_ROOT
            }
        }

        override fun onBtMetadataChanged(
            title: String, artist: String, album: String, artUri: String?
        ) {
            if (activeSource == BT_ROOT) {
                session.setMetadata(buildBtMetadata(title, artist, album, artUri))
                refreshNotification()
                WidgetUpdater.pushAll(
                    this@MediaService, title = title, artist = artist,
                    isPlaying = player.isPlaying
                )
            }
        }

        override fun onBtPlaybackChanged(isPlaying: Boolean) {
            if (activeSource == BT_ROOT) {
                // Mirror the phone's playback state so our session stays in sync
                val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                            else           PlaybackStateCompat.STATE_PAUSED
                session.setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setState(state, 0L, 1.0f)
                        .setActions(SESSION_ACTIONS)
                        .build()
                )
                refreshNotification()
            }
        }
    }

    // ── Player listener → keep MediaSessionCompat in sync ──────────────────

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Acquire audio focus on first play after idle for USB / Online —
            // BT manages its own focus from activateSource().  Held until the
            // source is switched away from us or the service is destroyed.
            if (isPlaying && activeSource != BT_ROOT) requestMediaFocus()
            session.setPlaybackState(buildPlaybackState())
            refreshNotification()
            pushWidgets()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            session.setPlaybackState(buildPlaybackState())
            refreshNotification()
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            // Use the item passed directly to avoid a race where player.mediaMetadata
            // may not have settled yet when this callback fires.
            session.setMetadata(buildMetadata(item))
            session.setPlaybackState(buildPlaybackState())
            refreshNotification()
            pushWidgets()
        }
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            // ExoPlayer read ID3/MP4 tags from the stream — update with the definitive values
            session.setMetadata(buildMetadata())
            refreshNotification()
            pushWidgets()
        }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            session.setShuffleMode(
                if (shuffleModeEnabled) PlaybackStateCompat.SHUFFLE_MODE_ALL
                else PlaybackStateCompat.SHUFFLE_MODE_NONE
            )
            session.setPlaybackState(buildPlaybackState())
            prefs.edit().putBoolean(PREF_SHUFFLE, shuffleModeEnabled).apply()
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
            session.setRepeatMode(exoRepeatToCompat(repeatMode))
            session.setPlaybackState(buildPlaybackState())
            prefs.edit().putInt(PREF_REPEAT_MODE, repeatMode).apply()
        }
    }

    // ── Session helpers ─────────────────────────────────────────────────────

    private fun buildPlaybackState(): PlaybackStateCompat {
        val state = when {
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            player.isPlaying                               -> PlaybackStateCompat.STATE_PLAYING
            player.playbackState == Player.STATE_READY     -> PlaybackStateCompat.STATE_PAUSED
            player.playbackState == Player.STATE_ENDED     -> PlaybackStateCompat.STATE_STOPPED
            else                                           -> PlaybackStateCompat.STATE_NONE
        }
        return PlaybackStateCompat.Builder()
            .setState(state, player.currentPosition, 1.0f)
            .setActions(SESSION_ACTIONS)
            .build()
    }

    /**
     * Builds a [MediaMetadataCompat] from [sourceItem] if provided, otherwise from
     * [player.mediaMetadata].  Passing [sourceItem] directly is preferred in callbacks
     * (e.g. [onMediaItemTransition]) to avoid a race where the player's combined
     * metadata property has not yet reflected the new item.
     */
    private fun buildMetadata(sourceItem: MediaItem? = null): MediaMetadataCompat {
        val meta     = sourceItem?.mediaMetadata ?: player.mediaMetadata
        val title    = meta.title?.toString()      ?: ""
        val artist   = meta.artist?.toString()     ?: ""
        val album    = meta.albumTitle?.toString() ?: ""
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: -1L
        val artUri   = meta.artworkUri?.toString() ?: ""
        return buildMetadataCompat(title, artist, album, duration, artUri)
    }

    /** Builds a [MediaMetadataCompat] from raw BT/AVRCP field values. */
    private fun buildBtMetadata(
        title: String, artist: String, album: String, artUri: String?
    ): MediaMetadataCompat = buildMetadataCompat(title, artist, album, -1L, artUri ?: "")

    private fun buildMetadataCompat(
        title: String, artist: String, album: String, duration: Long, artUri: String
    ): MediaMetadataCompat = MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE,            title)
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,           artist)
        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,            album)
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE,    title)
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,           duration)
        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,    artUri)
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUri)
        .build()

    private fun exoRepeatToCompat(repeatMode: Int) = when (repeatMode) {
        Player.REPEAT_MODE_ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
        Player.REPEAT_MODE_ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
        else                   -> PlaybackStateCompat.REPEAT_MODE_NONE
    }

    // ── MediaSessionCompat.Callback — transport controls ───────────────────

    private inner class SessionCompatCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (activeSource == BT_ROOT) btManager.sendPassThrough(BluetoothMediaManager.PASSTHRU_PLAY)
            else player.play()
        }
        override fun onPause() {
            if (activeSource == BT_ROOT) btManager.sendPassThrough(BluetoothMediaManager.PASSTHRU_PAUSE)
            else player.pause()
        }
        override fun onStop() {
            if (activeSource == BT_ROOT) btManager.sendPassThrough(BluetoothMediaManager.PASSTHRU_STOP)
            else player.stop()
        }
        override fun onSkipToNext() {
            if (activeSource == BT_ROOT) btManager.sendPassThrough(BluetoothMediaManager.PASSTHRU_NEXT)
            else player.seekToNextMediaItem()
        }
        override fun onSkipToPrevious() {
            if (activeSource == BT_ROOT) btManager.sendPassThrough(BluetoothMediaManager.PASSTHRU_PREVIOUS)
            else player.seekToPreviousMediaItem()
        }
        override fun onSeekTo(pos: Long) {
            // Seeking via AVRCP from the sink side is not reliably supported; ignore for BT
            if (activeSource != BT_ROOT) player.seekTo(pos)
        }

        override fun onSetShuffleMode(shuffleMode: Int) {
            player.shuffleModeEnabled = shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE
        }
        override fun onSetRepeatMode(repeatMode: Int) {
            player.repeatMode = when (repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
                PlaybackStateCompat.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
                else                                -> Player.REPEAT_MODE_OFF
            }
        }

        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            serviceScope.launch(Dispatchers.IO) {
                val parentId = extras?.getString(EXTRA_PARENT_ID)
                if (parentId != null) {
                    // Build a full playlist from the browse parent so next/previous work.
                    val siblings = loadChildren(parentId).filter { it.isPlayable }
                    val mediaItems = siblings.mapNotNull { sibling ->
                        resolveMediaItem(sibling.mediaId ?: return@mapNotNull null,
                                         sibling.description.extras)
                    }
                    val startIndex = mediaItems.indexOfFirst { it.mediaId == mediaId }
                        .coerceAtLeast(0)
                    if (mediaItems.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            // Eagerly push metadata so the launcher sees the right track
                            // title immediately, before ExoPlayer fires async callbacks.
                            mediaItems.getOrNull(startIndex)?.let {
                                session.setMetadata(buildMetadata(it))
                            }
                            player.setMediaItems(mediaItems, startIndex, 0L)
                            player.prepare()
                            player.play()
                        }
                    }
                } else {
                    // Fallback: single item (e.g. launched from outside the browse UI)
                    val item = resolveMediaItem(mediaId, extras) ?: return@launch
                    withContext(Dispatchers.Main) {
                        session.setMetadata(buildMetadata(item))
                        player.setMediaItem(item)
                        player.prepare()
                        player.play()
                    }
                }
            }
        }
    }

    /** Builds a playable [MediaItem] from a mediaId + the extras bundle we stashed at browse time. */
    private fun resolveMediaItem(mediaId: String, extras: Bundle?): MediaItem? {
        val title  = extras?.getString("title")
        val artist = extras?.getString("artist")
        val album  = extras?.getString("album")
        val artUri = extras?.getString("album_art_uri")?.let { Uri.parse(it) }

        val meta = androidx.media3.common.MediaMetadata.Builder()
            .apply { if (title  != null) setTitle(title) }
            .apply { if (artist != null) setArtist(artist) }
            .apply { if (album  != null) setAlbumTitle(album) }
            .apply { if (artUri != null) setArtworkUri(artUri) }
            .build()

        return when {
            mediaId.startsWith(USB_FILE_PREFIX) -> {
                val path = mediaId.removePrefix(USB_FILE_PREFIX)
                MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(Uri.fromFile(File(path)))
                    .setMediaMetadata(meta)
                    .build()
            }
            mediaId.startsWith(SUBSONIC_SONG_PREFIX) -> {
                val streamUri = extras?.getString("stream_uri")?.let { Uri.parse(it) }
                    ?: return null
                MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(streamUri)
                    .setMediaMetadata(meta)
                    .build()
            }
            else -> null
        }
    }

    // ── MediaBrowserServiceCompat — browsing contract ──────────────────────

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()
        serviceScope.launch { result.sendResult(loadChildren(parentId)) }
    }

    override fun onCustomAction(action: String, extras: Bundle?, result: Result<Bundle>) {
        if (action != ACTION_PLAY_ALL) { super.onCustomAction(action, extras, result); return }

        result.detach()
        val parentId = extras?.getString(EXTRA_PARENT_ID)
        if (parentId == null) { result.sendResult(null); return }

        serviceScope.launch {
            val tracks = loadChildren(parentId).filter { it.isPlayable }
            val mediaItems = tracks.mapNotNull { item ->
                val id = item.mediaId ?: return@mapNotNull null
                buildPlayableMediaItem(id, item.description)
            }
            if (mediaItems.isNotEmpty()) {
                player.setMediaItems(mediaItems)
                player.prepare()
                player.play()
            }
            result.sendResult(null)
        }
    }

    private suspend fun loadChildren(
        parentId: String
    ): MutableList<MediaBrowserCompat.MediaItem> = withContext(Dispatchers.IO) {

        val usbPath = usbPaths().firstOrNull()

        when {
            parentId == ROOT_ID -> buildSourceList(usbPath != null)
            parentId == BT_ROOT -> mutableListOf()   // No browse tree for Bluetooth

            parentId == USB1_ROOT ->
                buildCategoryList(USB1_ROOT, includeTracksFlat = true)
            parentId == artistsId(USB1_ROOT) -> usbArtists(usbPath)
            parentId == albumsId(USB1_ROOT)  -> usbAllAlbums(usbPath)
            parentId == tracksId(USB1_ROOT)  -> usbAllTracks(usbPath)
            parentId.startsWith("$USB1_ROOT/$TYPE_ARTIST/") -> {
                val artistName = Uri.decode(parentId.removePrefix("$USB1_ROOT/$TYPE_ARTIST/"))
                usbAlbumsByArtist(usbPath, artistName)
            }
            parentId.startsWith("$USB1_ROOT/$TYPE_ALBUM/") -> {
                val encoded  = parentId.removePrefix("$USB1_ROOT/$TYPE_ALBUM/")
                val colon    = encoded.indexOf(':')
                if (colon < 0) return@withContext mutableListOf()
                val artist   = Uri.decode(encoded.substring(0, colon))
                val album    = Uri.decode(encoded.substring(colon + 1))
                usbTracksInAlbum(usbPath, artist, album)
            }

            parentId == ONLINE_ROOT ->
                buildCategoryList(ONLINE_ROOT, includeTracksFlat = false)
            parentId == artistsId(ONLINE_ROOT) -> subsonicArtists()
            parentId == albumsId(ONLINE_ROOT)  -> subsonicAlbums()
            parentId.startsWith("$ONLINE_ROOT/$TYPE_ARTIST/") -> {
                subsonicAlbumsByArtist(parentId.removePrefix("$ONLINE_ROOT/$TYPE_ARTIST/"))
            }
            parentId.startsWith("$ONLINE_ROOT/$TYPE_ALBUM/") -> {
                subsonicSongs(parentId.removePrefix("$ONLINE_ROOT/$TYPE_ALBUM/"))
            }

            else -> mutableListOf()
        }
    }

    // ── Browse tree builders ───────────────────────────────────────────────

    private fun buildSourceList(usbMounted: Boolean): MutableList<MediaBrowserCompat.MediaItem> {
        fun browsable(id: String, title: String) = MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
        return mutableListOf<MediaBrowserCompat.MediaItem>().apply {
            if (usbMounted) add(browsable(USB1_ROOT, "USB"))
            add(browsable(ONLINE_ROOT, "Subsonic"))
            if (btManager.isConnected) add(browsable(BT_ROOT, btManager.connectedDeviceName))
        }
    }

    private fun buildCategoryList(
        root: String,
        includeTracksFlat: Boolean
    ): MutableList<MediaBrowserCompat.MediaItem> {
        fun browsable(id: String, title: String) = MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
        return mutableListOf<MediaBrowserCompat.MediaItem>().apply {
            add(browsable(artistsId(root), "Artists"))
            add(browsable(albumsId(root),  "Albums"))
            if (includeTracksFlat) add(browsable(tracksId(root), "Tracks"))
        }
    }

    // ── USB filesystem queries ─────────────────────────────────────────────

    private suspend fun usbArtists(usbPath: String?): MutableList<MediaBrowserCompat.MediaItem> {
        if (usbPath == null) return mutableListOf()
        return UsbScanner.scan(usbPath)
            .map { it.artist }.distinct().sorted()
            .map { artist ->
                MediaBrowserCompat.MediaItem(
                    MediaDescriptionCompat.Builder()
                        .setMediaId(usbArtistNodeId(artist)).setTitle(artist).build(),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                )
            }.toMutableList()
    }

    private suspend fun usbAllAlbums(usbPath: String?): MutableList<MediaBrowserCompat.MediaItem> {
        if (usbPath == null) return mutableListOf()
        val tracks = UsbScanner.scan(usbPath)
        data class Key(val artist: String, val album: String)
        val seen = LinkedHashSet<Key>()
        tracks.sortedBy { it.album }.forEach { seen += Key(it.artist, it.album) }
        return seen.map { key ->
            val artUri = tracks.firstOrNull { it.artist == key.artist && it.album == key.album }?.artUri
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(usbAlbumNodeId(key.artist, key.album))
                    .setTitle(key.album).setSubtitle(key.artist)
                    .apply { if (artUri != null) setIconUri(artUri) }.build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            )
        }.toMutableList()
    }

    private suspend fun usbAlbumsByArtist(
        usbPath: String?, artistName: String
    ): MutableList<MediaBrowserCompat.MediaItem> {
        if (usbPath == null) return mutableListOf()
        val tracks = UsbScanner.scan(usbPath)
        return tracks.filter { it.artist == artistName }
            .map { it.album }.distinct().sorted()
            .map { album ->
                val artUri = tracks.firstOrNull { it.artist == artistName && it.album == album }?.artUri
                MediaBrowserCompat.MediaItem(
                    MediaDescriptionCompat.Builder()
                        .setMediaId(usbAlbumNodeId(artistName, album))
                        .setTitle(album).setSubtitle(artistName)
                        .apply { if (artUri != null) setIconUri(artUri) }.build(),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                )
            }.toMutableList()
    }

    private suspend fun usbTracksInAlbum(
        usbPath: String?, artistName: String, albumName: String
    ): MutableList<MediaBrowserCompat.MediaItem> {
        if (usbPath == null) return mutableListOf()
        return UsbScanner.scan(usbPath)
            .filter { it.artist == artistName && it.album == albumName }
            .sortedWith(compareBy({ it.trackNumber }, { it.title }))
            .map { it.toMediaItem() }.toMutableList()
    }

    private suspend fun usbAllTracks(usbPath: String?): MutableList<MediaBrowserCompat.MediaItem> {
        if (usbPath == null) return mutableListOf()
        return UsbScanner.scan(usbPath)
            .sortedBy { it.title }
            .map { it.toMediaItem() }.toMutableList()
    }

    private fun UsbScanner.Track.toMediaItem(): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply {
            putLong("duration",   durationMs)
            putString("path",     path)
            putString("title",    title)
            putString("artist",   artist)
            putString("album",    album)
            if (artUri != null) putString("album_art_uri", artUri.toString())
        }
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId("$USB_FILE_PREFIX$path")
                .setTitle(title).setSubtitle(artist)
                .apply { if (artUri != null) setIconUri(artUri) }
                .setExtras(extras).build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    // ── Subsonic queries ───────────────────────────────────────────────────

    private fun subsonicCfg(): SubsonicSettings.Config? {
        val cfg = SubsonicSettings.load(this)
        return if (cfg.isValid) cfg else null
    }

    private fun subsonicArtists(): MutableList<MediaBrowserCompat.MediaItem> {
        val cfg = subsonicCfg() ?: return mutableListOf()
        return SubsonicClient.getArtists(cfg).map { artist ->
            val artUri = artist.coverArt?.let { Uri.parse(SubsonicClient.coverArtUrl(cfg, it)) }
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(subsonicArtistNodeId(artist.id)).setTitle(artist.name)
                    .apply { if (artUri != null) setIconUri(artUri) }.build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            )
        }.toMutableList()
    }

    private fun subsonicAlbums(): MutableList<MediaBrowserCompat.MediaItem> {
        val cfg = subsonicCfg() ?: return mutableListOf()
        return subsonicAlbumsToItems(cfg, SubsonicClient.getAlbumList(cfg))
    }

    private fun subsonicAlbumsByArtist(artistId: String): MutableList<MediaBrowserCompat.MediaItem> {
        val cfg = subsonicCfg() ?: return mutableListOf()
        return subsonicAlbumsToItems(cfg, SubsonicClient.getArtist(cfg, artistId))
    }

    private fun subsonicAlbumsToItems(
        cfg: SubsonicSettings.Config, albums: List<SubsonicAlbum>
    ): MutableList<MediaBrowserCompat.MediaItem> =
        albums.map { album ->
            val artUri = album.coverArt?.let { Uri.parse(SubsonicClient.coverArtUrl(cfg, it)) }
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(subsonicAlbumNodeId(album.id))
                    .setTitle(album.name).setSubtitle(album.artist)
                    .apply { if (artUri != null) setIconUri(artUri) }.build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            )
        }.toMutableList()

    private fun subsonicSongs(albumId: String): MutableList<MediaBrowserCompat.MediaItem> {
        val cfg = subsonicCfg() ?: return mutableListOf()
        return SubsonicClient.getAlbum(cfg, albumId).map { song ->
            val streamUrl = SubsonicClient.streamUrl(cfg, song.id)
            val artUrl    = song.coverArt?.let { SubsonicClient.coverArtUrl(cfg, it) }
            val extras = Bundle().apply {
                putLong("duration",     song.durationMs)
                putString("stream_uri", streamUrl)
                putString("title",      song.title)
                putString("artist",     song.artist ?: "")
                putString("album",      song.album  ?: "")
                if (artUrl != null) putString("album_art_uri", artUrl)
            }
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId("$SUBSONIC_SONG_PREFIX${song.id}")
                    .setTitle(song.title).setSubtitle(song.artist)
                    .apply { if (artUrl != null) setIconUri(Uri.parse(artUrl)) }
                    .setExtras(extras).build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        }.toMutableList()
    }

    // ── Play All helper ────────────────────────────────────────────────────

    private fun buildPlayableMediaItem(
        mediaId: String, desc: MediaDescriptionCompat
    ): MediaItem? {
        val artUri = desc.extras?.getString("album_art_uri")?.let { Uri.parse(it) }
        val meta = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(desc.title)
            .setArtist(desc.subtitle)
            .apply { if (artUri != null) setArtworkUri(artUri) }
            .build()
        return when {
            mediaId.startsWith(USB_FILE_PREFIX) ->
                MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(Uri.fromFile(File(mediaId.removePrefix(USB_FILE_PREFIX))))
                    .setMediaMetadata(meta).build()
            mediaId.startsWith(SUBSONIC_SONG_PREFIX) -> {
                val streamUri = desc.extras?.getString("stream_uri")?.let { Uri.parse(it) }
                    ?: return null
                MediaItem.Builder()
                    .setMediaId(mediaId).setUri(streamUri).setMediaMetadata(meta).build()
            }
            else -> null
        }
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun pushWidgets() {
        val meta = player.mediaMetadata
        WidgetUpdater.pushAll(
            this,
            title     = meta.title?.toString() ?: "",
            artist    = meta.artist?.toString() ?: "",
            isPlaying = player.isPlaying
        )
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val meta      = player.mediaMetadata
        val isPlaying = player.isPlaying
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(meta.title?.toString() ?: getString(R.string.app_name))
            .setContentText(meta.artist?.toString() ?: "")
            .setContentIntent(pendingMainActivity())
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(R.drawable.ic_skip_previous, "Previous",
                pendingServiceAction(ACTION_PREVIOUS, 10))
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pause"             else "Play",
                pendingServiceAction(ACTION_PLAY_PAUSE, 11)
            )
            .addAction(R.drawable.ic_skip_next, "Next",
                pendingServiceAction(ACTION_NEXT, 12))
            .build()
    }

    private fun pendingMainActivity(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun pendingServiceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, MediaService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Media playback controls"; setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
