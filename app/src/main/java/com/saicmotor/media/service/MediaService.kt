package com.saicmotor.media.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.VisibleForTesting
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.saicmotor.common.CommandParcelable
import com.saicmotor.media.R
import com.saicmotor.media.data.PlaybackSettings
import com.saicmotor.media.data.OfflinePlaylistStore
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.subsonic.SubsonicAlbum
import com.saicmotor.media.subsonic.SubsonicClient
import com.saicmotor.media.subsonic.SubsonicException
import com.saicmotor.media.subsonic.SubsonicSong
import com.saicmotor.media.ui.activity.MainActivity
import com.saicmotor.media.ui.common.coverCacheKey
import com.saicmotor.media.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Plays music from a Navidrome (Subsonic) server.
 *
 * Owns the ExoPlayer and the MediaSession that the app's own screen, the
 * SAIC launcher's media tile and the steering wheel all talk to, and exposes
 * the library as a browse tree for external clients (the launcher binds to
 * the proxy services in the `mbs` package, which forward to this one).
 */
@OptIn(UnstableApi::class)
class MediaService : MediaBrowserServiceCompat() {

    /** A top-level browse node for external clients such as the launcher. */
    private data class Category(val id: String, val label: Int, val playable: Boolean = false)

    companion object {
        const val ROOT_ID     = "root"
        /** Root the launcher-facing proxy services browse (same content as [ROOT_ID]). */
        const val ONLINE_ROOT = "online"

        private const val TAG     = "CarMedia"
        private const val KEY_TAG = "CarMedia.Keys"

        const val SUBSONIC_SONG_PREFIX = "sub:"

        // ── Browse nodes ───────────────────────────────────────────────────
        /** Playable pseudo-item: the whole library, shuffled, endlessly. */
        const val SHUFFLE_ALL_ID = "$ONLINE_ROOT/shuffle"
        const val ARTISTS_ID     = "$ONLINE_ROOT/artists"
        const val ALBUMS_ID      = "$ONLINE_ROOT/albums"
        const val NEWEST_ID      = "$ONLINE_ROOT/newest"
        const val RECENT_ID      = "$ONLINE_ROOT/recent"
        const val FREQUENT_ID    = "$ONLINE_ROOT/frequent"
        const val GENRES_ID      = "$ONLINE_ROOT/genres"
        const val PLAYLISTS_ID   = "$ONLINE_ROOT/playlists"
        private const val ALBUM_PREFIX        = "$ONLINE_ROOT/album/"
        private const val PLAYLIST_PREFIX     = "$ONLINE_ROOT/playlist/"
        private const val ARTIST_PREFIX       = "$ONLINE_ROOT/artist/"
        private const val ARTIST_SONGS_PREFIX = "$ONLINE_ROOT/artistsongs/"
        private const val GENRE_PREFIX        = "$ONLINE_ROOT/genre/"
        private const val GENRE_SONGS_PREFIX  = "$ONLINE_ROOT/genresongs/"
        private const val SEARCH_PREFIX       = "$ONLINE_ROOT/search/"

        fun albumNodeId(id: String)        = ALBUM_PREFIX + id
        fun playlistNodeId(id: String)     = PLAYLIST_PREFIX + id
        fun artistNodeId(id: String)       = ARTIST_PREFIX + id
        /** Every song by an artist, album by album (playable children). */
        fun artistSongsNodeId(id: String)  = ARTIST_SONGS_PREFIX + id
        fun genreNodeId(name: String)      = GENRE_PREFIX + Uri.encode(name)
        /** A random selection of songs from a genre (playable children). */
        fun genreSongsNodeId(name: String) = GENRE_SONGS_PREFIX + Uri.encode(name)
        fun searchNodeId(query: String)    = SEARCH_PREFIX + Uri.encode(query)

        fun songMediaId(songId: String) = SUBSONIC_SONG_PREFIX + songId

        private val CATEGORIES = listOf(
            Category(SHUFFLE_ALL_ID, R.string.browse_shuffle_all, playable = true),
            Category(RECENT_ID,      R.string.shelf_recent),
            Category(NEWEST_ID,      R.string.shelf_newest),
            Category(FREQUENT_ID,    R.string.shelf_frequent),
            Category(ARTISTS_ID,     R.string.segment_artists),
            Category(ALBUMS_ID,      R.string.segment_albums),
            Category(GENRES_ID,      R.string.segment_genres),
            Category(PLAYLISTS_ID,   R.string.segment_playlists)
        )

        // ── Commands (startService intents: notification, widgets, boot) ───
        const val ACTION_PLAY_PAUSE = "com.saicmotor.media.PLAY_PAUSE"
        const val ACTION_PREVIOUS   = "com.saicmotor.media.PREVIOUS"
        const val ACTION_NEXT       = "com.saicmotor.media.NEXT"
        /** Sent by BootReceiver: run the "when the car starts" action once. */
        const val ACTION_BOOT_START = "com.saicmotor.media.BOOT_START"
        /** Sent after Settings → Check for updates replaced the app while it was playing. */
        const val ACTION_RESUME_AFTER_UPDATE = "com.saicmotor.media.RESUME_AFTER_UPDATE"

        // ── Custom actions (TransportControls.sendCustomAction) ────────────
        /** Play the playable children of [EXTRA_PARENT_ID]; optional [EXTRA_SHUFFLE], [EXTRA_START_ID]. */
        const val ACTION_PLAY_ALL     = "com.saicmotor.media.PLAY_ALL"
        /** Sent by the SAIC launcher when a held steering-wheel skip key is let go
         *  (after TransportControls.fastForward / rewind). */
        const val ACTION_STOP_FAST_FORWARD = "__STOP_FAST_FORWARD__"
        const val EXTRA_PARENT_ID = "parent_id"
        const val EXTRA_SHUFFLE   = "shuffle"
        const val EXTRA_START_ID  = "start_id"

        // ── Extra metadata published on the session ───────────────────────
        const val METADATA_ALBUM_ID  = "com.saicmotor.media.ALBUM_ID"
        const val METADATA_ARTIST_ID = "com.saicmotor.media.ARTIST_ID"

        // Keys in each browse item's extras (used to build the player item)
        private const val X_DURATION  = "duration"
        private const val X_STREAM    = "stream_uri"
        private const val X_TITLE     = "title"
        private const val X_ARTIST    = "artist"
        private const val X_ALBUM     = "album"
        private const val X_ART       = "album_art_uri"
        private const val X_ALBUM_ID  = "album_id"
        private const val X_ARTIST_ID = "artist_id"

        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID      = "media_playback"

        // Hidden framework flag (android.media.session.MediaSession#FLAG_EXCLUSIVE_GLOBAL_PRIORITY).
        // Reserved for system apps — accessible to us because we ship with
        // sharedUserId="android.uid.system".  Without this, the SAIC radio
        // app's globally-priority session intercepts steering-wheel media
        // keys before they reach us, and they vanish (radio is inactive).
        // Because it makes *every* media key come to us, keys are passed on
        // to other apps (YouTube etc.) when they own the speakers — see
        // [MediaKeyRouter] and handleMediaKey().
        private const val FLAG_EXCLUSIVE_GLOBAL_PRIORITY = 0x10000

        @Suppress("DEPRECATION")
        private const val SESSION_FLAGS =
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
            MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS or
            FLAG_EXCLUSIVE_GLOBAL_PRIORITY

        // SAIC's custom AudioAttributes Bundle key + values.  The launcher's
        // OnAudioSourceChangeCallBack reads this from our AudioFocusRequest
        // and uses it (together with the calling package name) to decide
        // which MediaController to bind to its title bar / now playing tile.
        //
        // From com.saicmotor.launcher.model.MediaModel (decompiled):
        //   pkg == "com.saicmotor.media"  →  carSourceType=6 → mMusicController
        //                                 →  carSourceType=7 → mMusic2Controller
        //                                 →  carSourceType=5 → mBTMusicController
        // We always present ourselves as the main music source (6).
        private const val CAR_SOURCE_KEY        = "key_car_source_type"
        private const val CAR_SOURCE_TYPE_MUSIC = 6

        // All actions exposed to external controllers (launcher, BT, etc.)
        private const val SESSION_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE

        // Steering-wheel / media keys we handle (or pass on) ourselves.
        private val ROUTED_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD
        )
        /** One press of a fast-forward / rewind key. */
        private const val SEEK_STEP_MS = 10_000L
        /** Holding a skip key: like the stock player, 5 s every half second… */
        private const val FAST_SEEK_STEP_MS     = 5_000L
        private const val FAST_SEEK_INTERVAL_MS = 500L
        /** …once it's been held this long (shorter is a skip)… */
        private const val HOLD_MS               = 600L
        /** …and never for longer than this, in case the key's release goes missing. */
        private const val FAST_SEEK_MAX_MS      = 30_000L
        // SAIC hardware-key broadcast (see hardKeyReceiver).
        private const val ACTION_HARDKEY_REPORT = "com.saic.keyevent.hardkey.report"
        private const val EXTRA_HARDKEY_CODE    = "android.intent.extra.hardkey.keycode"
        private const val EXTRA_HARDKEY_DOWN    = "android.intent.extra.hardkey.down"
        private const val EXTRA_HARDKEY_LONG    = "android.intent.extra.hardkey.longpress"
        /** The steering wheel's centre (play/pause), next and previous keys. */
        private const val SWC_CENTER = 301
        private const val SWC_NEXT   = 300
        private const val SWC_PREV   = 299
        /** The steering wheel's volume keys. */
        private const val SWC_VOL_UP   = 297
        private const val SWC_VOL_DOWN = 298
        /** How long to wait for the launcher to act on a wheel key before doing it ourselves. */
        private const val IGNORED_KEY_WAIT_MS = 500L
        /** …and on a volume key (it changes the volume within a few milliseconds when it does). */
        private const val IGNORED_VOLUME_KEY_WAIT_MS = 300L
        /** After a pause, how long the music's own sound can still be heard (so it isn't "another app"). */
        private const val OWN_SOUND_FADE_MS = 1_000L
        /** Start-up timing: the first song was partly downloaded (size unknown). */
        private const val PARTLY = -2
        /** A launcher "play" this soon after the play/pause key was reported came from it. */
        private const val CENTRE_KEY_WINDOW_MS = 1_000L
        /** Volume up / down: phone-style keys, and the steering wheel's (SWC_UP / SWC_DOWN). */
        private val VOLUME_HARDKEYS = setOf(24, 25, 297, 298)
        /** Play/pause: the steering wheel's centre (SWC_CENTER) and the mute key, which the launcher maps to it. */
        private val PLAY_HARDKEYS = setOf(301, 164, 85)
        /** A "play" from the launcher this close to a volume change is the volume change, not a press of play. */
        private const val VOLUME_PLAY_WINDOW_MS = 800L
        /** How long to hold an outside "play" to see whether a volume change comes with it. */
        private const val VOLUME_PLAY_WAIT_MS   = 250L

        private val SKIP_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD
        )

        // Cover art requested for the now-playing screen and launcher tile.
        private const val NOW_PLAYING_ART_PX = 720
        /** Cover bitmap attached to the session for the car's own menus (they
         *  can't fetch our server's cover URLs).  Kept small: it's sent to every
         *  app watching the session. */
        private const val SESSION_ART_PX = 320

        // Library shuffle: random batches, topped up as they run low
        private const val LIBRARY_BATCH       = 200
        private const val LIBRARY_LOW_WATER   = 15
        private const val LIBRARY_TRIM_AFTER  = 150
        private const val LIBRARY_KEEP_BEHIND = 25
        private const val GENRE_BATCH         = 200

        /** Queue published to the screen: this many before the current track, and after. */
        private const val QUEUE_BEHIND = 20
        private const val QUEUE_AHEAD  = 300

        // Startup ("when the car starts")
        /** The launcher asks for the last source ~10 s after it starts.  If it
         *  hasn't asked us by this long after boot, decide for ourselves. */
        private const val BOOT_FALLBACK_MS   = 25_000L
        /** A launcher request this soon after the head unit boots is its start-up restore. */
        private const val STARTUP_WINDOW_MS  = 5 * 60_000L
        /** Wait this long for the network before giving up. */
        private const val STARTUP_GIVE_UP_MS = 120_000L
        private const val STARTUP_RETRY_MS   = 5_000L

        /** Stream failed for a reason other than the network: retry this many times, then skip. */
        private const val MAX_STREAM_RETRIES = 4
        private const val STREAM_RETRY_MS    = 3_000L
        /** How often to try again after the server refused the login (it may have been fixed in Settings). */
        private const val LOGIN_RETRY_MS     = 15_000L
        /** No network (tunnel, car park, modem still starting): keep trying this often… */
        private const val NETWORK_RETRY_MS   = 4_000L
        /** …for this long, rather than skipping songs that can't load either. */
        private const val NETWORK_GIVE_UP_MS = 15 * 60_000L

        /** Songs downloaded ahead of the one playing, and covers fetched ahead.
         *  Ten, so a run of skips still lands on songs that are already here
         *  (about 40 MB at Navidrome's Opus 128k). */
        private const val PRECACHE_SONGS  = 10
        private const val PREFETCH_COVERS = 10
        /** Let the current song get going before downloading the next ones. */
        /** The downloader checks this often for something to do. */
        private const val DOWNLOADER_IDLE_MS = 1_000L
        /** Network trouble: wait this long before trying the download again… */
        private const val DOWNLOAD_RETRY_MS  = 3_000L
        /** …and give up on a song after this many failures (until the app restarts). */
        private const val MAX_DOWNLOAD_FAILURES = 5
        /** The song playing gets the connection until it has this much in hand. */
        private const val CURRENT_BUFFER_FIRST_MS = 10_000L
        /** Songs kept in the saved queue, before and after the current one. */
        private const val SNAPSHOT_BEHIND = 10
        private const val SNAPSHOT_AHEAD  = 400

        /** Report a play once half the song, or four minutes, has played. */
        private const val SCROBBLE_AFTER_MS = 240_000L

        private const val TICK_MS = 5_000L
    }

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSessionCompat
    private lateinit var keyRouter: MediaKeyRouter
    private lateinit var resumeStore: ResumeStore
    private lateinit var pendingScrobbles: PendingScrobbles
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Queue bookkeeping ──────────────────────────────────────────────────

    /** What the current ExoPlayer queue is (saved for resume on startup). */
    private var queueKind = ResumeStore.QueueKind.SINGLE
    /** Bumped whenever a new queue is set, so slow fetches can tell they're stale. */
    private var queueGeneration = 0
    private var libraryTopUpInFlight = false
    private var streamRetries = 0
    private var snapshotJob: Job? = null

    // ── No network ─────────────────────────────────────────────────────────

    /** The server refused the login: shown as such, and tried again now and then. */
    private var loginRefused = false

    /** Waiting for the network to come back (shown as "buffering", not an error). */
    private var networkWait = false
    private var networkWaitSince = 0L
    private var networkWaitJob: Job? = null

    // ── Pre-caching ────────────────────────────────────────────────────────

    private var downloader: Job? = null
    @Volatile private var precacheWriter: CacheWriter? = null
    private val downloadFailures = HashMap<String, Int>()
    /** Songs fully downloaded this session (so they aren't fetched twice). */
    private val precached = HashSet<String>()
    private val prefetchedCovers = HashSet<String>()

    // ── Fast forward / rewind (held skip keys) ─────────────────────────────

    private var fastSeekJob: Job? = null
    /** Skip keys pressed but not yet released: the job that turns them into a hold. */
    private val holdJobs = HashMap<Int, Job>()
    /** Skip keys being held (fast seeking). */
    private val holdingKeys = HashSet<Int>()

    /** Cover bitmap for the current song's art URL (see [SESSION_ART_PX]). */
    private var sessionArtUrl: String? = null
    private var sessionArt: Bitmap? = null
    private var sessionArtJob: Job? = null

    // ── Play reporting (scrobbling) ────────────────────────────────────────

    private var playStartedAt = 0L
    private var playReported  = false
    /** The song Navidrome was last told is "now playing". */
    private var nowPlayingSentFor: String? = null

    // ── Startup ────────────────────────────────────────────────────────────

    /** The start-up restore has been decided (by the launcher, or by us). */
    private var startupHandled = false
    private var startupJob: Job? = null
    private var bootFallbackJob: Job? = null
    /** When BootReceiver last told us the head unit started (elapsedRealtime). */
    private var bootSignalAt = 0L

    // Timing of this start-up, for the log ("Startup timing: …").  traceFrom
    // is when the head unit started (elapsedRealtime); -1 = not timing.  On
    // disk: percent of the first song, PARTLY, or -1 (unknown).
    private var traceFrom       = -1L
    private var traceQueueAt    = -1L
    private var traceLauncherAt = -1L
    private var traceOnDiskPct  = -1
    /** Music was started for the driver (launcher or boot fallback), not by a press. */
    private var traceAuto       = false

    // ── Steering-wheel keys ────────────────────────────────────────────────

    /** Where each key's DOWN was sent, so its UP goes to the same place. */
    private val keyTargets = mutableMapOf<Int, MediaController>()

    // ── Volume changes (the launcher's "play" on every volume press) ──────

    /** Last time a volume key was pressed or the car's volume changed (elapsedRealtime). */
    private var lastVolumeChangeAt = -1L
    /** Last time the steering wheel's play/pause key was pressed. */
    private var lastPlayKeyAt = -1L

    /** SAIC's report of every hardware key (steering wheel included). */
    private val hardKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = intent.getIntExtra(EXTRA_HARDKEY_CODE, -1)
            when (code) {
                in VOLUME_HARDKEYS -> lastVolumeChangeAt = SystemClock.elapsedRealtime()
                in PLAY_HARDKEYS   -> lastPlayKeyAt = SystemClock.elapsedRealtime()
            }
            val down = intent.getBooleanExtra(EXTRA_HARDKEY_DOWN, false)
            mirrorLauncherKeyCount(code, down, intent.getBooleanExtra(EXTRA_HARDKEY_LONG, false))
        }
    }

    /**
     * The launcher's count of steering-wheel keys held down, mirrored.  It
     * only acts on a key if letting go of it brings that count back to 0,
     * and it starts counting afresh after every release.  The STAR key's
     * release is never reported (the system keeps it), so after a STAR press
     * the launcher ignores the next key — then we do what it would have done.
     * (A held key still works for it: only a short press is lost.)
     */
    private var launcherKeysHeld = 0
    private val longPressedKeys = mutableSetOf<Int>()

    private fun mirrorLauncherKeyCount(code: Int, down: Boolean, longPress: Boolean) {
        if (down) {
            launcherKeysHeld++
            if (longPress) longPressedKeys += code
            return
        }
        val ignored = --launcherKeysHeld != 0
        launcherKeysHeld = 0
        val held = longPressedKeys.remove(code) || longPress
        if (!ignored || held) return
        when (code) {
            SWC_CENTER, SWC_NEXT, SWC_PREV -> coverIgnoredKey(code)
            SWC_VOL_UP, SWC_VOL_DOWN       -> coverIgnoredVolumeKey(code == SWC_VOL_UP)
        }
    }

    /** Turns the car's volume like the launcher does. */
    private val carVolume by lazy { CarVolume(this) }

    @VisibleForTesting
    internal var volumeStepperForTest: VolumeStepper? = null

    /** When the car's volume last actually changed (see [volumeObserver]). */
    private var lastCarVolumeChangeAt = -1L

    /** Changes the volume as the launcher would have, for a volume key it ignored. */
    private fun coverIgnoredVolumeKey(up: Boolean) {
        val releasedAt = SystemClock.elapsedRealtime()
        serviceScope.launch {
            delay(IGNORED_VOLUME_KEY_WAIT_MS)
            if (lastCarVolumeChangeAt >= releasedAt) return@launch           // it did act after all
            val name = if (up) "up" else "down"
            Log.i(KEY_TAG, "The launcher ignored the wheel's volume $name key (after a STAR press): doing it here")
            val stepper = volumeStepperForTest ?: carVolume
            val done = withContext(Dispatchers.IO) { stepper.step(up) }
            if (!done) Log.w(KEY_TAG, "  → couldn't reach the car's audio service")
        }
    }

    /** When the music last got a play / pause / skip, from anyone (see [coverIgnoredKey]). */
    private var lastTransportCommandAt = -1L

    /** Pauses / stops so far: a launcher "play" still waiting (see SessionCallback.onPlay) is dropped by a later one. */
    private var pauseCommands = 0

    /** When the driver last paused the music: its sound takes a moment to stop. */
    private var lastUserPauseAt = -1L

    @VisibleForTesting
    internal var callerForTest: String? = null

    /** What a launcher "play" came from (see [launcherPlayCause]). */
    private enum class PlayCause { CENTRE_KEY, VOLUME, NOT_KNOWN }

    /**
     * What a launcher "play" arriving now came from: the wheel's play/pause
     * key, a volume change, or neither as far as we know (the launcher's own
     * play button, or the key's report hasn't come yet).  Whichever was
     * latest: a volume press just after pausing isn't a press of play.  Keys
     * are reported as they're pressed and the launcher acts when they're let
     * go, so the report normally comes first.
     */
    private fun launcherPlayCause(): PlayCause {
        val now = SystemClock.elapsedRealtime()
        val key = lastPlayKeyAt >= 0 && now - lastPlayKeyAt < CENTRE_KEY_WINDOW_MS
        val volume = lastVolumeChangeAt >= 0 && now - lastVolumeChangeAt < VOLUME_PLAY_WINDOW_MS
        return when {
            key && (!volume || lastPlayKeyAt > lastVolumeChangeAt) -> PlayCause.CENTRE_KEY
            volume -> PlayCause.VOLUME
            else   -> PlayCause.NOT_KNOWN
        }
    }

    /** Does what the launcher would have done with a wheel key it ignored. */
    private fun coverIgnoredKey(code: Int) {
        val releasedAt = SystemClock.elapsedRealtime()
        serviceScope.launch {
            delay(IGNORED_KEY_WAIT_MS)
            if (lastTransportCommandAt >= releasedAt) return@launch           // it did act after all
            val name = when (code) { SWC_CENTER -> "centre"; SWC_NEXT -> "next"; else -> "previous" }
            Log.i(KEY_TAG, "The launcher ignored the wheel's $name key (after a STAR press): doing it here")
            lastTransportCommandAt = SystemClock.elapsedRealtime()
            val other = wheelTarget()
            when {
                code == SWC_CENTER && other != null -> toggleOther(other)
                other != null -> Log.i(KEY_TAG, "  → ignored: ${other.packageName} has the speakers")
                code == SWC_CENTER -> {
                    cancelStartupAction()
                    stopFastSeek()
                    if (player.playWhenReady) userPause()
                    else if (!externalPlayBlocked("wheel")) userPlay()
                }
                code == SWC_NEXT -> { cancelStartupIfQueueLoaded(); skipToNext() }
                else             -> { cancelStartupIfQueueLoaded(); skipToPrevious() }
            }
        }
    }

    /**
     * Another app the steering wheel should control instead of us: the one
     * the driver is watching (YouTube) — playing, or paused but still open.
     * Null when it's our music (including once that app has been closed).
     */
    internal interface OtherPlayer {
        val packageName: String
        val isPlaying: Boolean
        fun play()
        fun pause()
    }

    @VisibleForTesting
    internal var otherPlayerForTest: OtherPlayer? = null

    private fun wheelTarget(): OtherPlayer? {
        otherPlayerForTest?.let { return it }
        val c = externalKeyTarget() ?: return null
        return object : OtherPlayer {
            override val packageName: String = c.packageName
            override val isPlaying: Boolean
                get() = c.playbackState?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING }
            override fun play()  = c.transportControls.play()
            override fun pause() = c.transportControls.pause()
        }
    }

    /** The wheel's centre key while another app is being watched: play / pause it. */
    private fun toggleOther(other: OtherPlayer) {
        if (other.isPlaying) {
            Log.i(KEY_TAG, "  → centre key: pausing ${other.packageName}")
            other.pause()
        } else {
            Log.i(KEY_TAG, "  → centre key: playing ${other.packageName}")
            other.play()
        }
    }

    /** The car's volume settings (android.car.VOLUME_GROUP/n) — catches volume changes from anywhere. */
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            lastVolumeChangeAt = SystemClock.elapsedRealtime()
            lastCarVolumeChangeAt = lastVolumeChangeAt
        }
    }

    // Audio focus.  We hold focus while playing so that the SAIC launcher's
    // OnAudioSourceChangeCallBack sees us as the active media source and
    // binds its title-bar UI to our MediaSession.  The focus request carries
    // a Bundle with key_car_source_type — the value the launcher reads to
    // decide which of its internal MediaController references to bind to.
    private var mediaFocusRequest: AudioFocusRequest? = null

    /** True if we paused on a transient focus loss and should resume on AUDIOFOCUS_GAIN. */
    private var pausedByFocusLoss = false

    /** True once another app has taken the speakers from us for good (until we
     *  take them back).  While set, steering-wheel keys go to that app. */
    private var yieldedToOtherApp = false
    private var yieldedAt = 0L

    private val mediaFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    // Something else started playing while we were paused
                    // (YouTube during a navigation prompt): leave it be.
                    if (otherAppIsPlaying()) {
                        Log.i(TAG, "Focus back, but another app is playing — staying paused")
                    } else {
                        player.play()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Navigation prompt, phone call: pause and resume afterwards.
                if (player.isPlaying) {
                    pausedByFocusLoss = true
                    player.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss — another app (e.g. SAIC radio, YouTube) took
                // the speakers.  Pause and clear our focus bookkeeping so the
                // next user-initiated play() re-requests focus instead of
                // short-circuiting on a stale request.  We never resume on our
                // own after this.
                pausedByFocusLoss = false
                if (player.isPlaying) player.pause()
                mediaFocusRequest = null
                yieldedToOtherApp = true
                yieldedAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "Another app took the speakers; steering-wheel keys will follow it")
            }
        }
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
    private fun buildMediaAudioAttributes(): android.media.AudioAttributes {
        val builder = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
        try {
            val bundle = Bundle().apply { putInt(CAR_SOURCE_KEY, CAR_SOURCE_TYPE_MUSIC) }
            android.media.AudioAttributes.Builder::class.java
                .getDeclaredMethod("addBundle", Bundle::class.java)
                .invoke(builder, bundle)
        } catch (_: Throwable) {
            // SAIC hidden API not present — carry on without the bundle.
        }
        return builder.build()
    }

    private fun requestMediaFocus(): Boolean {
        if (mediaFocusRequest != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(buildMediaAudioAttributes())
            .setOnAudioFocusChangeListener(mediaFocusListener)
            .build()
        val granted = getSystemService(AudioManager::class.java)
            .requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            mediaFocusRequest = request
            yieldedToOtherApp = false
            claimMediaKeys("audio focus granted")
        } else {
            // e.g. during a phone call.  Don't keep the dead request around,
            // or the next attempt would wrongly short-circuit to "granted".
            Log.i(TAG, "Audio focus refused")
        }
        return granted
    }

    /**
     * Makes our session the one the system sends media keys to.  Done again
     * every time playback starts, because the radio (or a second copy of this
     * service running for another Android user) may have claimed it since.
     */
    private fun claimMediaKeys(why: String) {
        session.setFlags(SESSION_FLAGS)
        Log.i(KEY_TAG, "Claimed steering-wheel keys ($why); user=${android.os.Process.myUserHandle()} pid=${android.os.Process.myPid()}")
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MediaService started; user=${android.os.Process.myUserHandle()} pid=${android.os.Process.myPid()}")
        createNotificationChannel()
        resumeStore = ResumeStore(this)
        pendingScrobbles = PendingScrobbles(this)

        player = ExoPlayer.Builder(this)
            // Songs are read through the song cache (see MediaCache), a song
            // that can't be fetched waits for the network quietly (see
            // WaitForNetworkPolicy), and one still streaming carries on where
            // it was if the connection drops (see SongSourceFactory).
            .setMediaSourceFactory(
                SongSourceFactory(MediaCache.dataSourceFactory(this)) {
                    it.mediaMetadata.extras?.getLong(X_DURATION, -1L)
                }.setLoadErrorHandlingPolicy(WaitForNetworkPolicy())
            )
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // Read up to two minutes ahead (the default is 50 s),
                    // which also puts it in the song cache.  Start / restart
                    // thresholds are the defaults: lower ones made a weak
                    // signal stutter (play 3 s, stop 5 s, …).
                    .setBufferDurationsMs(50_000, 120_000, 2_500, 5_000)
                    .setTargetBufferBytes(32 * 1024 * 1024)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // We handle audio focus ourselves so we can attach the SAIC
                // key_car_source_type Bundle to the focus request.
                /* handleAudioFocus = */ false
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playerListener)

        session = MediaSessionCompat(this, "MediaService").apply {
            setFlags(SESSION_FLAGS)
            setCallback(SessionCallback())
            setPlaybackState(buildPlaybackState())
            setQueueTitle(getString(R.string.up_next))
            isActive = true
        }

        // Shuffle is set by whatever starts playback (Play / Shuffle / a song);
        // repeat is always off.  Publish both even though they match the
        // defaults (no change callback fires then).
        player.repeatMode = Player.REPEAT_MODE_OFF
        session.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE)
        session.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE)
        session.setPlaybackState(buildPlaybackState())

        setSessionToken(session.sessionToken)
        startForeground(NOTIFICATION_ID, buildNotification())

        keyRouter = MediaKeyRouter(this).apply { start() }
        try {
            registerReceiver(hardKeyReceiver, IntentFilter(ACTION_HARDKEY_REPORT))
            contentResolver.registerContentObserver(
                Uri.parse("content://settings/global/android.car.VOLUME_GROUP"), true, volumeObserver)
        } catch (e: Exception) {
            Log.w(TAG, "Can't watch for volume changes", e)
        }
        carVolume.connect()

        // So the launcher brings us back when the car starts, not the radio.
        SaicModeBridge.reportAvailable(this)

        startTicker()
        startDownloader()

        // The launcher starts this service as the head unit comes up.  Put the
        // last queue back straight away (paused, loading) so the song is ready
        // the moment the launcher asks for music, ~10 s later.
        if (SystemClock.elapsedRealtime() < STARTUP_WINDOW_MS) {
            startTrace(0L)
            prepareForStartup()
        }
    }

    /**
     * Puts the saved queue back, paused but loading (from the song cache
     * where it can), ready for the launcher's "play" (or "pause") a few
     * seconds later.
     */
    private fun prepareForStartup() {
        if (player.mediaItemCount > 0) return
        if (PlaybackSettings.startupMode(this) == PlaybackSettings.StartupMode.NOTHING) return
        val saved = resumeStore.load() ?: return
        Log.i(TAG, "Startup: getting the last queue ready before the launcher asks")
        launchRestore(restoreMode(), saved, play = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = SaicModeBridge.commandFrom(intent)
        if (command != null) {
            handleLauncherCommand(command)
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_BOOT_START -> {
                pushWidgets()   // couldn't be updated before the user storage unlocked
                bootSignalAt = SystemClock.elapsedRealtime()
                // Woken from sleep rather than booted: this is a new start-up.
                if (bootSignalAt > STARTUP_WINDOW_MS) {
                    startupHandled = false
                    startTrace(bootSignalAt)
                    prepareForStartup()
                }
                scheduleBootFallback()
            }
            // Just updated by Settings → Check for updates, while playing.
            ACTION_RESUME_AFTER_UPDATE -> {
                Log.i(TAG, "Updated while playing: carrying on")
                if (player.mediaItemCount > 0) userPlay()
                else launchRestore(PlaybackSettings.StartupMode.RESUME, resumeStore.load(), play = true)
            }
            // Notification and widget buttons — always act on our player.
            ACTION_PLAY_PAUSE -> { cancelStartupAction(); togglePlayPause() }
            ACTION_PREVIOUS   -> { cancelStartupIfQueueLoaded(); skipToPrevious() }
            ACTION_NEXT       -> { cancelStartupIfQueueLoaded(); skipToNext() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopFastSeek()
        stopDownloader()
        keyRouter.stop()
        try { unregisterReceiver(hardKeyReceiver) } catch (_: Exception) {}
        try { contentResolver.unregisterContentObserver(volumeObserver) } catch (_: Exception) {}
        carVolume.disconnect()
        mediaFocusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        mediaFocusRequest = null
        session.release()
        player.release()
        serviceScope.cancel()
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    /**
     * Every few seconds: save "still alive" and the position (see
     * [ResumeStore]), and report the play to Navidrome once enough of it
     * has been heard.
     */
    private fun startTicker() {
        serviceScope.launch {
            var ticks = 0
            while (isActive) {
                delay(TICK_MS)
                val playing = player.isPlaying
                if (playing) resumeStore.savePosition(player.currentPosition)
                if (playing) maybeReportPlay()
                // Plays that couldn't be reported (no signal): try again every half minute.
                if (++ticks % 6 == 0 && pendingScrobbles.all().isNotEmpty()) {
                    launch(Dispatchers.IO) { sendPendingScrobbles() }
                }
            }
        }
    }

    // ── Player listener → keep the session in sync ─────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                requestMediaFocus()
                claimMediaKeys("playback started")
                sendNowPlaying()
                scheduleLookAhead()
                finishTrace()
            } else {
                resumeStore.savePosition(player.currentPosition)
            }
            session.setPlaybackState(buildPlaybackState())
            refreshNotification()
            pushWidgets()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                streamRetries = 0
                loginRefused = false
                endNetworkWait()
            }
            session.setPlaybackState(buildPlaybackState())
            refreshNotification()
            if (playbackState == Player.STATE_ENDED) onQueueEnded()
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            // Use the item passed directly: player.mediaMetadata may not have
            // settled yet when this fires.
            session.setMetadata(buildMetadata(item))
            session.setPlaybackState(buildPlaybackState())
            refreshNotification()
            pushWidgets()
            // currentPosition, not 0: when resuming mid-track this fires with
            // the player already at the resume point.
            saveCurrentItem(item, player.currentPosition)
            Log.i(TAG, "Now playing: ${item?.mediaMetadata?.title} — ${item?.mediaMetadata?.artist}")
            stopFastSeek()
            startPlayReport(item)
            topUpLibraryQueueIfLow()
            scheduleLookAhead()
            saveSnapshotSoon()
        }
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            session.setMetadata(buildMetadata())
            refreshNotification()
            pushWidgets()
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            publishQueue()
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                saveSnapshotSoon()
                scheduleLookAhead()
            }
        }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            session.setShuffleMode(
                if (shuffleModeEnabled) PlaybackStateCompat.SHUFFLE_MODE_ALL
                else PlaybackStateCompat.SHUFFLE_MODE_NONE
            )
            // Turning shuffle on mid-queue: shuffle everything *after* the
            // current track, so no other track is skipped.
            if (shuffleModeEnabled && player.mediaItemCount > 1) {
                applyShuffleOrder(player.currentMediaItemIndex)
            }
            session.setPlaybackState(buildPlaybackState())
            publishQueue()
            saveSnapshotSoon()
            scheduleLookAhead()
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
            // Repeat isn't offered; put it back if anything turns it on.
            if (repeatMode != Player.REPEAT_MODE_OFF) player.repeatMode = Player.REPEAT_MODE_OFF
        }
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error: ${error.errorCodeName}")
            recoverFromError(error)
            session.setPlaybackState(buildPlaybackState())
        }
    }

    // ── Errors and lost network ────────────────────────────────────────────

    /** True if the song failed because the server couldn't be reached (not because it's bad). */
    private fun isNetworkError(error: PlaybackException): Boolean {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> return true
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                // 5xx: the server (or the proxy in front of it) is restarting.
                val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: return false
                return code >= 500
            }
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> return !networkAvailable()
        }
        return false
    }

    private fun networkAvailable(): Boolean = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        true   // can't tell: just try
    }

    /**
     * The network is down (tunnel, car park, the modem still starting up after
     * the car is switched on).  Keep the song and keep trying every few
     * seconds — showing "buffering", not an error — instead of skipping
     * through songs that can't load either.  We keep the speakers meanwhile,
     * so the car doesn't swap to the radio.
     */
    private fun startNetworkWait() {
        if (!networkWait) {
            networkWait = true
            networkWaitSince = SystemClock.elapsedRealtime()
            Log.i(TAG, "No connection to the server — waiting for the network")
        }
        if (networkWaitJob?.isActive == true) return
        val generation = queueGeneration
        networkWaitJob = serviceScope.launch {
            while (isActive) {
                delay(NETWORK_RETRY_MS)
                if (generation != queueGeneration || player.playerError == null) return@launch
                if (SystemClock.elapsedRealtime() - networkWaitSince > NETWORK_GIVE_UP_MS) {
                    Log.w(TAG, "Network still down — giving up")
                    networkWait = false
                    session.setPlaybackState(buildPlaybackState())
                    return@launch
                }
                if (!networkAvailable()) continue
                Log.i(TAG, "Retrying now the network is back")
                player.prepare()
                if (mediaFocusRequest != null) player.play()
                return@launch   // a new error starts a new wait
            }
        }
    }

    private fun endNetworkWait() {
        if (!networkWait) return
        networkWait = false
        networkWaitJob?.cancel()
        networkWaitJob = null
        Log.i(TAG, "Connection to the server is back")
    }

    /**
     * A stream that drops without the network going down is retried from the
     * same spot a few times, backing off.  If it keeps failing, skip the song.
     * If the network is down, wait for it instead ([startNetworkWait]).
     */
    private fun recoverFromError(error: PlaybackException) {
        // The server sent a message instead of the song (see SongReplies).
        val refused = generateSequence(error.cause) { it.cause }.take(8).filterIsInstance<SongRefused>().firstOrNull()
        if (refused != null) Log.w(TAG, "The server didn't send the song: ${refused.message}")
        val generation = queueGeneration
        val wanted     = mediaFocusRequest != null
        if (refused?.notFound == true) {
            // It's gone from the library (a queue saved before a rescan): no
            // point waiting.  Skip it, or if it's a later song (being loaded
            // ahead), take it out of the queue and carry on with this one.
            val missing = refused.songId?.let { id ->
                (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == songMediaId(id) }
            }
            val current = missing == null || missing == player.currentMediaItemIndex
            if (!current || player.hasNextMediaItem()) {
                endNetworkWait()
                streamRetries = 0
                if (current) {
                    Log.w(TAG, "The server has no such song; skipping")
                    player.seekToNextMediaItem()
                } else {
                    Log.w(TAG, "The server has no such song; taking it out of the queue")
                    player.removeMediaItem(missing!!)
                }
                player.prepare()
                if (wanted) player.play()
                return
            }
        }
        if (refused?.loginRefused == true) {
            // Nothing will play until the login is fixed in Settings: say so,
            // rather than skipping through the queue, and try again now and
            // then (each try with the login as it is then, see MediaCache).
            if (!loginRefused) Log.w(TAG, "The server refused the login; waiting for it to be fixed in Settings")
            loginRefused = true
            endNetworkWait()
            serviceScope.launch {
                delay(LOGIN_RETRY_MS)
                if (generation != queueGeneration || player.playerError == null) return@launch
                player.prepare()
                if (wanted) player.play()
            }
            return
        }
        if (isNetworkError(error)) {
            startNetworkWait()
            return
        }
        endNetworkWait()
        if (streamRetries < MAX_STREAM_RETRIES) {
            streamRetries++
            val wait = STREAM_RETRY_MS * streamRetries
            serviceScope.launch {
                delay(wait)
                if (generation != queueGeneration || player.playerError == null) return@launch
                Log.i(TAG, "Retrying stream (attempt $streamRetries)")
                player.prepare()
                if (wanted) player.play()
            }
        } else if (player.hasNextMediaItem()) {
            Log.w(TAG, "Giving up on this song; skipping")
            streamRetries = 0
            player.seekToNextMediaItem()
            player.prepare()
            if (wanted) player.play()
        }
    }

    // ── Session state ──────────────────────────────────────────────────────

    private fun buildPlaybackState(): PlaybackStateCompat {
        // Loading (just skipped to, or waiting for the network): on its way, not broken.
        val loading = player.playbackState == Player.STATE_BUFFERING ||
                      (networkWait && player.playerError != null)
        val state = when {
            player.playerError != null && !networkWait     -> PlaybackStateCompat.STATE_ERROR
            // Shown as playing, not buffering: the car's home-screen tile shows
            // anything but "playing" as paused, so a song that was still
            // loading after a skip looked paused, and pressing play there did
            // nothing.  Speed 0, so the position doesn't run on meanwhile.
            loading && player.playWhenReady                -> PlaybackStateCompat.STATE_PLAYING
            loading                                        -> PlaybackStateCompat.STATE_PAUSED
            player.isPlaying                               -> PlaybackStateCompat.STATE_PLAYING
            player.playbackState == Player.STATE_READY     -> PlaybackStateCompat.STATE_PAUSED
            player.playbackState == Player.STATE_ENDED     -> PlaybackStateCompat.STATE_STOPPED
            player.mediaItemCount > 0                      -> PlaybackStateCompat.STATE_PAUSED
            else                                           -> PlaybackStateCompat.STATE_NONE
        }
        return PlaybackStateCompat.Builder()
            .setState(state, player.currentPosition, if (player.isPlaying) 1.0f else 0f)
            .setActions(SESSION_ACTIONS)
            .setActiveQueueItemId(
                if (player.mediaItemCount > 0) player.currentMediaItemIndex.toLong()
                else MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()
            )
            .apply {
                if (state == PlaybackStateCompat.STATE_ERROR) {
                    if (loginRefused) setErrorMessage(PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED, getString(R.string.error_auth_title))
                    else setErrorMessage(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR, getString(R.string.error_stream))
                }
            }
            .build()
    }

    /**
     * Builds the session metadata from [sourceItem] if provided, otherwise
     * from the player.  [durationMs] can be supplied for the eager push before
     * the player has buffered, so the UI doesn't flash "0:00".
     */
    private fun buildMetadata(sourceItem: MediaItem? = null, durationMs: Long? = null): MediaMetadataCompat {
        val item     = sourceItem ?: player.currentMediaItem
        val meta     = item?.mediaMetadata ?: player.mediaMetadata
        val extras   = meta.extras
        val mediaId  = item?.mediaId.orEmpty()
        val duration = durationMs
                       ?: player.duration.takeIf { it != C.TIME_UNSET && sourceItem == null }
                       ?: extras?.getLong(X_DURATION, -1L)?.takeIf { it > 0 }
                       ?: -1L
        val artUri   = meta.artworkUri?.toString().orEmpty()
        val title    = meta.title?.toString().orEmpty()
        val artist   = meta.artist?.toString().orEmpty()
        loadSessionArt(artUri.ifEmpty { null })
        val art = sessionArt.takeIf { sessionArtUrl == artUri.ifEmpty { null } }
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID,         mediaId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,            title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,           artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,            meta.albumTitle?.toString().orEmpty())
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE,    title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,           duration)
            // No ALBUM_ART_URI: when it's there, the SAIC launcher's media
            // tile ignores the cover bitmap below and downloads the URL itself
            // (Glide, over the mobile connection, a new URL each app start),
            // which is why covers there were often slow.  Without it, the tile
            // shows the bitmap, which is fetched ahead.  The app's own screen
            // reads DISPLAY_ICON_URI.
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUri)
            .putString(METADATA_ALBUM_ID,  extras?.getString(X_ALBUM_ID).orEmpty())
            .putString(METADATA_ARTIST_ID, extras?.getString(X_ARTIST_ID).orEmpty())
            .apply {
                if (art != null) {
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
                }
            }
            .build()
    }

    /**
     * Fetches the cover for [url] as a small bitmap and republishes the
     * metadata with it.  The car's menus show the session's bitmap; they can't
     * load our server's (login-protected) cover URLs themselves.
     */
    private fun loadSessionArt(url: String?) {
        if (url == sessionArtUrl) return
        sessionArtUrl = url
        sessionArt    = null
        sessionArtJob?.cancel()
        if (url == null) return
        sessionArtJob = serviceScope.launch {
            val key = coverCacheKey(url)
            val request = ImageRequest.Builder(this@MediaService)
                .data(url)
                .size(SESSION_ART_PX)
                .allowHardware(false)
                // Same file on disk as the screen's cover, but its own small
                // bitmap in memory: the screen's big one is too large to send
                // to the car's menus.
                .apply { if (key != null) { memoryCacheKey("$key#session"); diskCacheKey(key) } }
                .build()
            val bitmap = (imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
            if (sessionArtUrl != url) return@launch
            if (bitmap == null) {
                // Couldn't get it (no signal, server busy): try again with the
                // next metadata update rather than never for this song.
                sessionArtUrl = null
                return@launch
            }
            sessionArt = bitmap
            session.setMetadata(buildMetadata())
        }
    }

    /** Publishes the play order (shuffle-aware) as the session queue, for the Up next screen. */
    private fun publishQueue() {
        val order = playOrder()
        if (order.isEmpty()) {
            session.setQueue(emptyList())
            return
        }
        val position = order.indexOf(player.currentMediaItemIndex).coerceAtLeast(0)
        val from     = (position - QUEUE_BEHIND).coerceAtLeast(0)
        val to       = (position + QUEUE_AHEAD).coerceAtMost(order.size)
        session.setQueue(order.subList(from, to).map { i ->
            val item = player.getMediaItemAt(i)
            val m    = item.mediaMetadata
            MediaSessionCompat.QueueItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(item.mediaId)
                    .setTitle(m.title)
                    .setSubtitle(m.artist)
                    .setIconUri(m.artworkUri)
                    .setExtras(Bundle().apply {
                        putLong(X_DURATION, m.extras?.getLong(X_DURATION, -1L) ?: -1L)
                    })
                    .build(),
                i.toLong()
            )
        })
    }

    // ── Transport (shared by the session, keys, notification and widgets) ──

    private fun userPlay() {
        // Nothing loaded (e.g. first press after the car starts): pick up
        // where we left off, or shuffle the library if there's nothing saved.
        if (player.mediaItemCount == 0) {
            restoreOnPlay()
            return
        }
        // Take focus first.  Until we hold it the radio (or whatever else was
        // playing) keeps the speakers.  If it's refused, stay paused.
        if (!requestMediaFocus()) return
        when (player.playbackState) {
            Player.STATE_IDLE  -> player.prepare()
            Player.STATE_ENDED -> {
                val first = player.currentTimeline.getFirstWindowIndex(player.shuffleModeEnabled)
                if (first != C.INDEX_UNSET) player.seekTo(first, 0L)
            }
        }
        player.play()
    }

    private fun userPause() {
        pausedByFocusLoss = false
        lastUserPauseAt = SystemClock.elapsedRealtime()
        player.pause()
    }

    private fun togglePlayPause() {
        if (player.isPlaying) userPause() else userPlay()
    }

    private fun skipToNext() {
        stopFastSeek()
        player.seekToNextMediaItem()
    }

    /**
     * Like the stock player (and phones): back to the start of the song,
     * unless it's only just started (first 3 s), then the song before.
     */
    private fun skipToPrevious() {
        stopFastSeek()
        player.seekToPrevious()
    }

    /**
     * A song still streaming (not downloaded yet) can't be skipped through:
     * the player would start it over from the beginning.  Not known until it
     * has started loading, so a seek isn't stopped before then.
     */
    private fun seekWouldRestart(): Boolean {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return false
        val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        return !window.isPlaceholder && !window.isSeekable
    }

    /** Seeks within the song.  Returns false if it hit the start or end (or can't seek). */
    private fun seekBy(deltaMs: Long): Boolean {
        if (player.mediaItemCount == 0) return false
        if (seekWouldRestart()) {
            Log.i(KEY_TAG, "  → can't skip through this song until it's downloaded")
            return false
        }
        val duration = player.duration
        var target = player.currentPosition + deltaMs
        var hitEdge = false
        if (target <= 0L) { target = 0L; hitEdge = true }
        // Stop just short of the end, so the song finishes and moves on by itself.
        if (duration != C.TIME_UNSET && target >= duration - 1_000L) {
            target = (duration - 1_000L).coerceAtLeast(0L)
            hitEdge = true
        }
        player.seekTo(target)
        return !hitEdge
    }

    /**
     * Fast forward / rewind while a skip key is held: 5 s every half second
     * (10 s of song per second held), as the stock player does, until
     * [stopFastSeek].
     */
    private fun startFastSeek(forward: Boolean) {
        if (player.mediaItemCount == 0) return
        fastSeekJob?.cancel()
        Log.i(KEY_TAG, "  → ${if (forward) "fast forward" else "rewind"}")
        fastSeekJob = serviceScope.launch {
            val started = SystemClock.elapsedRealtime()
            while (isActive && SystemClock.elapsedRealtime() - started < FAST_SEEK_MAX_MS) {
                if (!seekBy(if (forward) FAST_SEEK_STEP_MS else -FAST_SEEK_STEP_MS)) break
                delay(FAST_SEEK_INTERVAL_MS)
            }
        }
    }

    private fun stopFastSeek() {
        fastSeekJob?.cancel()
        fastSeekJob = null
    }

    /**
     * True if a "play" from another app should be ignored because something
     * else is playing.  The SAIC launcher sends our session "play" whenever the
     * volume is changed while we're paused — even while YouTube is playing —
     * which is what made the stock radio barge in.
     */
    private fun externalPlayBlocked(caller: String): Boolean {
        if (caller == packageName || player.isPlaying) return false
        val other = keyRouter.playingElsewhere()
        val blocked = otherAudioActive() || (other != null && !other.packageName.startsWith("com.saicmotor."))
        if (blocked) Log.i(TAG, "  → ignored: ${other?.packageName ?: "another app"} is playing")
        return blocked
    }

    // ── Steering-wheel keys ────────────────────────────────────────────────

    /** True while we're the app the driver is listening to (or just paused). */
    private fun weOwnPlayback(): Boolean =
        player.isPlaying || pausedByFocusLoss || mediaFocusRequest != null

    /** The other app a key should go to, or null if it's ours to handle. */
    private fun externalKeyTarget(): MediaController? {
        if (weOwnPlayback()) return null
        return if (yieldedToOtherApp) keyRouter.targetAfterWeYielded(yieldedAt)
               else keyRouter.playingElsewhere()
    }

    private fun otherAudioActive(): Boolean {
        // Just paused: the sound still playing is ours, fading out.
        if (lastUserPauseAt >= 0 && SystemClock.elapsedRealtime() - lastUserPauseAt < OWN_SOUND_FADE_MS) return false
        return getSystemService(AudioManager::class.java).isMusicActive && !player.isPlaying
    }

    /**
     * Handles a media key from the steering wheel (or any other hardware
     * button).  If another app owns the speakers — YouTube, the radio, the
     * phone's own Bluetooth player — the key is passed to it untouched, so we
     * never get in its way.  Otherwise we act on it directly, on key-down,
     * without the double-tap delay MediaSessionCompat adds to play/pause.
     */
    private fun handleMediaKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code !in ROUTED_KEYS) return false
        Log.i(KEY_TAG, "key ${KeyEvent.keyCodeToString(code)} action=${event.action} repeat=${event.repeatCount} " +
            "playing=${player.isPlaying} focus=${mediaFocusRequest != null} yielded=$yieldedToOtherApp")

        // Decide on the first DOWN and send the matching UP to the same place.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val target = externalKeyTarget()
            if (target != null) keyTargets[code] = target else keyTargets.remove(code)
        }
        val target = if (event.action == KeyEvent.ACTION_UP) keyTargets.remove(code) else keyTargets[code]
        if (target != null) {
            val delivered = try { target.dispatchMediaButtonEvent(event) } catch (_: Exception) { false }
            Log.i(KEY_TAG, "  → forwarded to ${target.packageName} (delivered=$delivered)")
            if (delivered) return true
            keyTargets.remove(code)
            if (event.action != KeyEvent.ACTION_DOWN) return true
            // The other app has gone away — fall through and handle it ourselves.
        }

        // Skip keys: a press skips, a hold fast-forwards / rewinds.
        if (code in SKIP_KEYS) {
            handleSkipKey(event)
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN) return true
        val startsPlayback = code == KeyEvent.KEYCODE_MEDIA_PLAY ||
            ((code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_HEADSETHOOK) && !player.isPlaying)
        if (startsPlayback && !weOwnPlayback() && otherAudioActive()) {
            // Something we can't identify is playing (no media session to pass
            // the key to).  Don't start our music over the top of it.
            Log.i(KEY_TAG, "  → ignored: other audio is playing")
            return true
        }
        val isSeek = code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_REWIND
        if (isSeek) cancelStartupIfQueueLoaded() else cancelStartupAction()
        stopFastSeek()
        val repeat = event.repeatCount > 0
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY         -> if (!repeat) userPlay()
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP         -> if (!repeat) userPause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK        -> if (!repeat) togglePlayPause()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(SEEK_STEP_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND       -> seekBy(-SEEK_STEP_MS)
        }
        return true
    }

    /**
     * Next / previous from the steering wheel.  Acted on when the key is let
     * go: a short press skips; holding it for [HOLD_MS] starts fast forward /
     * rewind, which runs until the key is let go.
     */
    private fun handleSkipKey(event: KeyEvent) {
        val code    = event.keyCode
        val forward = code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0 || holdJobs[code]?.isActive == true || code in holdingKeys) return
                cancelStartupIfQueueLoaded()
                holdJobs[code] = serviceScope.launch {
                    delay(HOLD_MS)
                    holdingKeys += code
                    startFastSeek(forward)
                }
            }
            KeyEvent.ACTION_UP -> {
                holdJobs.remove(code)?.cancel()
                if (holdingKeys.remove(code)) {
                    stopFastSeek()
                    return
                }
                cancelStartupIfQueueLoaded()
                // If the press and release only reached us together, the
                // key's own times still say whether it was held.
                val heldFor = if (event.downTime > 0) event.eventTime - event.downTime else 0L
                when {
                    heldFor >= HOLD_MS -> seekBy(if (forward) SEEK_STEP_MS else -SEEK_STEP_MS)
                    forward            -> skipToNext()
                    else               -> skipToPrevious()
                }
            }
        }
    }

    // ── MediaSessionCompat.Callback ────────────────────────────────────────

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        fun caller(): String =
            callerForTest ?: try { session.currentControllerInfo.packageName } catch (_: Exception) { "?" }

        private fun logCommand(name: String) {
            lastTransportCommandAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "$name from ${caller()}")
        }

        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            lastTransportCommandAt = SystemClock.elapsedRealtime()
            @Suppress("DEPRECATION")
            val event: KeyEvent? = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            if (event == null) {
                Log.i(KEY_TAG, "media button with no key event, from ${caller()}")
            } else if (handleMediaKey(event)) {
                return true
            } else {
                Log.i(KEY_TAG, "unhandled key ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}) action=${event.action}")
            }
            return super.onMediaButtonEvent(mediaButtonEvent)
        }

        override fun onPlay() {
            lastTransportCommandAt = SystemClock.elapsedRealtime()
            val from = caller()
            Log.i(TAG, "play from $from")
            if (from == packageName || player.isPlaying) {
                playPressed()
                return
            }
            // While we're paused the SAIC launcher sends "play" for the wheel's
            // centre key — even while YouTube is playing — and for every volume
            // change.  Centre key → play / pause the app being watched, if
            // there is one, else play; volume change → stay paused.  The
            // centre key is acted on at once: the launcher picks play or pause
            // by what it sees, so while a "play" waited here a quick second
            // press sent "play" again (and was lost) instead of "pause".
            when (launcherPlayCause()) {
                PlayCause.CENTRE_KEY -> centreKeyPlay(from)
                PlayCause.VOLUME     -> Log.i(TAG, "  → ignored: that was the volume being changed")
                PlayCause.NOT_KNOWN  -> {
                    // Give the key or volume change a moment to show up.
                    val pausesBefore = pauseCommands
                    serviceScope.launch {
                        delay(VOLUME_PLAY_WAIT_MS)
                        when {
                            // Paused since: the later command wins.
                            pauseCommands != pausesBefore -> Log.i(TAG, "  → dropped: paused since")
                            else -> when (launcherPlayCause()) {
                                PlayCause.CENTRE_KEY -> centreKeyPlay(from)
                                PlayCause.VOLUME     -> Log.i(TAG, "  → ignored: that was the volume being changed")
                                PlayCause.NOT_KNOWN  -> if (!externalPlayBlocked(from)) playPressed()
                            }
                        }
                    }
                }
            }
        }
        /** The wheel's centre key, paused: the app being watched, if there is one, else the music. */
        private fun centreKeyPlay(from: String) {
            val other = wheelTarget()
            when {
                other != null             -> toggleOther(other)
                externalPlayBlocked(from) -> Unit
                else                      -> playPressed()
            }
        }
        private fun playPressed() {
            cancelStartupAction()
            stopFastSeek()
            userPlay()
        }
        override fun onPause() {
            lastTransportCommandAt = SystemClock.elapsedRealtime()
            val from = caller()
            Log.i(TAG, "pause from $from")
            pauseCommands++
            cancelStartupAction()
            stopFastSeek()
            userPause()
        }
        override fun onStop() {
            val from = caller()
            Log.i(TAG, "stop from $from")
            pauseCommands++
            cancelStartupAction()
            stopFastSeek()
            player.stop()
        }
        /** A wheel key for the app being watched (YouTube) — skips and seeks leave our queue alone. */
        private fun forOtherApp(): Boolean {
            if (caller() == packageName) return false
            val other = wheelTarget() ?: return false
            Log.i(TAG, "  → ignored: ${other.packageName} has the speakers")
            return true
        }
        override fun onSkipToNext() {
            logCommand("next")
            if (forOtherApp()) return
            cancelStartupIfQueueLoaded()
            skipToNext()
        }
        override fun onSkipToPrevious() {
            logCommand("previous")
            if (forOtherApp()) return
            cancelStartupIfQueueLoaded()
            skipToPrevious()
        }
        // The SAIC launcher turns a held steering-wheel skip key into these,
        // then sends ACTION_STOP_FAST_FORWARD when it's let go.
        override fun onFastForward() {
            logCommand("fastForward")
            if (forOtherApp()) return
            cancelStartupIfQueueLoaded()
            startFastSeek(forward = true)
        }
        override fun onRewind() {
            logCommand("rewind")
            if (forOtherApp()) return
            cancelStartupIfQueueLoaded()
            startFastSeek(forward = false)
        }
        override fun onSkipToQueueItem(id: Long) {
            val index = id.toInt()
            if (index in 0 until player.mediaItemCount) {
                stopFastSeek()
                player.seekTo(index, 0L)
                userPlay()
            }
        }
        override fun onSeekTo(pos: Long) {
            stopFastSeek()
            if (pos > 0 && seekWouldRestart()) {
                Log.i(KEY_TAG, "  → can't skip through this song until it's downloaded")
                return
            }
            player.seekTo(pos)
        }

        override fun onSetShuffleMode(shuffleMode: Int) {
            logCommand("setShuffleMode($shuffleMode)")
            player.shuffleModeEnabled = shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE
        }
        override fun onSetRepeatMode(repeatMode: Int) {
            // Repeat isn't offered.
            logCommand("setRepeatMode($repeatMode) — ignored")
        }

        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            logCommand("playFromMediaId")
            cancelStartupAction()
            playFromMediaId(mediaId, extras)
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            handleCustomAction(action, extras)
        }
    }

    private fun handleCustomAction(action: String, extras: Bundle?): Boolean {
        when (action) {
            ACTION_PLAY_ALL -> {
                val parentId = extras?.getString(EXTRA_PARENT_ID) ?: return true
                cancelStartupAction()
                val shuffle = if (extras.containsKey(EXTRA_SHUFFLE)) extras.getBoolean(EXTRA_SHUFFLE) else null
                playAll(parentId, shuffle, extras.getString(EXTRA_START_ID))
            }
            ACTION_STOP_FAST_FORWARD -> {
                Log.i(KEY_TAG, "stop fast forward / rewind")
                stopFastSeek()
            }
            else -> return false
        }
        return true
    }

    // ── Play reporting ─────────────────────────────────────────────────────

    /** A new song started: tell Navidrome it's "now playing". */
    private fun startPlayReport(item: MediaItem?) {
        playStartedAt = System.currentTimeMillis()
        playReported  = false
        if (player.isPlaying) sendNowPlaying()
    }

    /**
     * Tells Navidrome what's playing — once the song is really playing, so
     * not while the queue is put back (paused) at start-up, before the
     * network is up.
     */
    private fun sendNowPlaying() {
        val songId = player.currentMediaItem?.mediaId?.takeIf { it.startsWith(SUBSONIC_SONG_PREFIX) }
            ?.removePrefix(SUBSONIC_SONG_PREFIX) ?: return
        if (songId == nowPlayingSentFor) return
        nowPlayingSentFor = songId
        reportPlay(songId, submission = false, timeMs = System.currentTimeMillis())
    }

    /** Once enough has been heard, report the play (updates play counts). */
    private fun maybeReportPlay() {
        if (playReported) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = player.currentPosition
        if (position < duration / 2 && position < SCROBBLE_AFTER_MS) return
        playReported = true
        val songId = player.currentMediaItem?.mediaId
            ?.takeIf { it.startsWith(SUBSONIC_SONG_PREFIX) }?.removePrefix(SUBSONIC_SONG_PREFIX) ?: return
        reportPlay(songId, submission = true, timeMs = playStartedAt)
    }

    private fun reportPlay(songId: String, submission: Boolean, timeMs: Long) {
        val cfg = subsonicCfg() ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                SubsonicClient.scrobble(cfg, songId, submission, timeMs)
                if (submission) sendPendingScrobbles()
            } catch (_: SubsonicException) {
                // No signal: keep the play and send it later ("now playing" isn't worth keeping).
                if (submission) pendingScrobbles.add(PendingScrobbles.Play(songId, timeMs))
            }
        }
    }

    /** Sends plays that couldn't be reported earlier, oldest first; stops at the first failure.  Blocking. */
    @Synchronized
    private fun sendPendingScrobbles() {
        val cfg = subsonicCfg() ?: return
        for (play in pendingScrobbles.all()) {
            try {
                SubsonicClient.scrobble(cfg, play.songId, true, play.timeMs)
                pendingScrobbles.remove(play)
            } catch (_: SubsonicException) {
                return
            }
        }
    }

    // ── Look-ahead: download the next songs and covers ─────────────────────

    /** The next [count] songs in play order after the current one. */
    private fun upcomingItems(count: Int): List<MediaItem> {
        val result   = ArrayList<MediaItem>(count)
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return result
        var index = player.currentMediaItemIndex
        while (result.size < count) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            result += player.getMediaItemAt(index)
        }
        return result
    }

    /**
     * A song changed (or the queue did, or playback started): fetch the next
     * covers.  The songs themselves are downloaded by [startDownloader],
     * which keeps going across song changes.
     */
    private fun scheduleLookAhead() {
        if (player.mediaItemCount == 0) return
        prefetchCovers(upcomingItems(PREFETCH_COVERS))
    }

    /**
     * Keeps the next [PRECACHE_SONGS] songs downloaded into the song cache,
     * one at a time, so the next song — or a run of skips — starts straight
     * away.  Runs for as long as the service does, rather than restarting at
     * every song change: when you skip a lot, a restart each time meant the
     * downloads hardly ever got going.
     *
     *  - Only while playing (not while paused, or before the launcher asks).
     *  - A new download waits while the song playing is short of data, so it
     *    gets the connection first (one already going carries on: carrying on
     *    later would fetch its first part again, as Navidrome starts a
     *    transcoded song over for every request).
     *  - A download stops if its song is no longer coming up (you jumped
     *    elsewhere) or is now the one playing (the player takes over).
     *  - If the network drops, it tries again a few seconds later.
     */
    private fun startDownloader() {
        downloader = serviceScope.launch {
            while (isActive) {
                delay(DOWNLOADER_IDLE_MS)
                if (!player.playWhenReady || currentNeedsConnection()) continue
                // A song the player has already streamed into the cache (see
                // KeepWhatPlays) is found there: not downloaded again.
                val target = upcomingItems(PRECACHE_SONGS).firstOrNull {
                    it.mediaId !in precached && (downloadFailures[it.mediaId] ?: 0) < MAX_DOWNLOAD_FAILURES
                } ?: continue
                val uri = target.localConfiguration?.uri ?: continue
                val id  = target.mediaId
                val download = async(Dispatchers.IO) { downloadAhead(id, uri) }
                var stopped = false
                while (download.isActive) {
                    delay(500)
                    if (!stopped && (player.currentMediaItem?.mediaId == id ||
                                     upcomingItems(PRECACHE_SONGS).none { it.mediaId == id })) {
                        stopped = true
                        precacheWriter?.cancel()
                    }
                }
                val error = download.await()
                when {
                    error == null -> {
                        precached += id
                        downloadFailures.remove(id)
                        Log.i(TAG, "Downloaded ahead: ${target.mediaMetadata.title}")
                    }
                    stopped -> Log.i(TAG, "Stopped downloading ahead: ${target.mediaMetadata.title} (skipped to, or no longer coming up)")
                    generateSequence<Throwable>(error) { it.cause }.take(8).any { it is SongRefused && it.notFound } -> {
                        downloadFailures[id] = MAX_DOWNLOAD_FAILURES   // not asked for again
                        Log.i(TAG, "Not downloading ahead: ${target.mediaMetadata.title} (the server has no such song)")
                    }
                    else -> {
                        downloadFailures[id] = (downloadFailures[id] ?: 0) + 1
                        Log.i(TAG, "Couldn't download ahead: ${target.mediaMetadata.title} — trying again shortly")
                        delay(DOWNLOAD_RETRY_MS)   // probably the network: give it a moment
                    }
                }
            }
        }
    }

    /** Downloads a song into the song cache (blocking; what went wrong, or null if it's all there). */
    private fun downloadAhead(mediaId: String, uri: Uri): Exception? =
        try {
            MediaCache.precache(this, mediaId, uri) { writer -> precacheWriter = writer }
            null
        } catch (e: Exception) {
            e
        } finally {
            precacheWriter = null
        }

    private fun currentFullyBuffered(): Boolean {
        // A transcoded stream doesn't say how long it is until it's all in;
        // fall back to the length the server gave for the song.
        val duration = player.duration.takeIf { it != C.TIME_UNSET }
            ?: player.currentMediaItem?.mediaMetadata?.extras?.getLong(X_DURATION, -1L)?.takeIf { it > 0 }
            ?: return false
        return player.bufferedPosition >= duration - 500
    }

    /** The song playing is short of data (just skipped to, or a weak signal): it gets the connection. */
    private fun currentNeedsConnection(): Boolean =
        !currentFullyBuffered() && player.bufferedPosition - player.currentPosition < CURRENT_BUFFER_FIRST_MS

    private fun stopDownloader() {
        downloader?.cancel()
        downloader = null
        precacheWriter?.cancel()
    }

    /**
     * Fetches covers before their song comes up: onto disk for the screen,
     * and as the small bitmap the car's menus get (see [loadSessionArt]), so
     * the cover is there the moment the song changes.
     */
    private fun prefetchCovers(items: List<MediaItem>) {
        if (prefetchedCovers.size > 500) prefetchedCovers.clear()
        for (item in items) {
            val url = item.mediaMetadata.artworkUri?.toString() ?: continue
            val key = coverCacheKey(url) ?: url
            if (!prefetchedCovers.add(key)) continue
            imageLoader.enqueue(
                ImageRequest.Builder(this)
                    .data(url)
                    .size(SESSION_ART_PX)
                    .allowHardware(false)
                    .memoryCacheKey("$key#session")
                    .diskCacheKey(key)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    // Not fetched (no signal, server busy): try again next time.
                    .listener(onError = { _, _ -> prefetchedCovers.remove(key) })
                    .build()
            )
        }
    }

    // ── Queue management ───────────────────────────────────────────────────

    private fun playFromMediaId(mediaId: String, extras: Bundle?) {
        if (mediaId == SHUFFLE_ALL_ID) {
            serviceScope.launch { startLibraryShuffle() }
            return
        }
        serviceScope.launch {
            val parentId     = extras?.getString(EXTRA_PARENT_ID)
            val durationHint = extras?.getLong(X_DURATION, -1L)?.takeIf { it > 0 }
            if (parentId != null) {
                // Build the whole queue from the browse parent so next/previous work.
                val items = playableChildren(parentId)
                val start = items.indexOfFirst { it.mediaId == mediaId }
                if (start >= 0) {
                    playQueue(items, start, 0L, ResumeStore.QueueKind.BROWSE, parentId, durationHint)
                    return@launch
                }
            }
            // Fallback: a single song (e.g. picked in the launcher, which doesn't
            // pass back the details we attached when it browsed).
            val item = resolveMediaItem(mediaId, extras) ?: lookUpSong(mediaId) ?: return@launch
            playQueue(listOf(item), 0, 0L, ResumeStore.QueueKind.SINGLE, null, durationHint)
        }
    }

    /**
     * Plays everything playable under [parentId].  [shuffle] true/false sets
     * shuffle mode first (the screen's Shuffle and Play buttons); null keeps
     * it.  [startSongMediaId] picks the first song.
     */
    private fun playAll(parentId: String, shuffle: Boolean?, startSongMediaId: String?) {
        serviceScope.launch {
            val items = playableChildren(parentId)
            if (items.isEmpty()) return@launch
            if (shuffle != null) player.shuffleModeEnabled = shuffle
            val start = when {
                startSongMediaId != null  -> items.indexOfFirst { it.mediaId == startSongMediaId }.coerceAtLeast(0)
                player.shuffleModeEnabled -> items.indices.random()
                else                      -> 0
            }
            playQueue(items, start, 0L, ResumeStore.QueueKind.BROWSE, parentId)
        }
    }

    /** The playable children of a browse node, ready for ExoPlayer. */
    private suspend fun playableChildren(parentId: String): List<MediaItem> =
        loadChildren(parentId)
            .filter { it.isPlayable && it.mediaId != SHUFFLE_ALL_ID }
            .mapNotNull { child -> child.mediaId?.let { resolveMediaItem(it, child.description.extras) } }

    /**
     * Replaces the queue and starts playing.  Every way of starting playback
     * goes through here so the queue type is always known (for resume) and
     * the shuffle order always starts with the chosen track.
     */
    private fun playQueue(
        items:           List<MediaItem>,
        startIndex:      Int,
        startPositionMs: Long,
        kind:            ResumeStore.QueueKind,
        parentId:        String?,
        durationHintMs:  Long? = null,
        /** False: load the queue but stay paused. */
        play:            Boolean = true
    ) {
        if (items.isEmpty()) return
        queueGeneration++
        queueKind     = kind
        streamRetries = 0
        endNetworkWait()
        stopFastSeek()

        val start = startIndex.coerceIn(0, items.size - 1)
        // Push metadata straight away so the launcher sees the right title
        // before ExoPlayer's callbacks arrive.
        session.setMetadata(buildMetadata(items[start], durationMs = durationHintMs))
        player.setMediaItems(items, start, startPositionMs)
        applyShuffleOrder(start)
        player.prepare()

        resumeStore.saveQueue(ONLINE_ROOT, kind, parentId)
        saveCurrentItem(items[start], startPositionMs)
        traceQueueReady(items[start].mediaId)

        // Take focus before play() so we own the speakers even if another
        // source had them.  If it's refused (e.g. a phone call), load the
        // queue but stay paused.
        if (play && requestMediaFocus()) player.play() else player.pause()
        publishQueue()
        topUpLibraryQueueIfLow()
    }

    /**
     * ExoPlayer's default shuffle order is random across the whole queue, so
     * starting at track 5 with shuffle on can skip tracks that land "before"
     * it.  We start the order at [startIndex] and shuffle the rest.  A library
     * shuffle is already random, so it keeps its order as-is (which also lets
     * us append more tracks to the end).
     */
    private fun applyShuffleOrder(startIndex: Int) {
        val n = player.mediaItemCount
        if (n == 0) return
        val order = if (queueKind == ResumeStore.QueueKind.LIBRARY) {
            IntArray(n) { it }
        } else {
            val start = startIndex.coerceIn(0, n - 1)
            intArrayOf(start) + (0 until n).filter { it != start }.shuffled().toIntArray()
        }
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, Random.nextLong()))
    }

    private fun saveCurrentItem(item: MediaItem?, positionMs: Long) {
        val current = item ?: return
        resumeStore.saveItem(toSavedItem(current), positionMs)
    }

    private fun toSavedItem(item: MediaItem): ResumeStore.SavedItem {
        val m = item.mediaMetadata
        val x = m.extras
        return ResumeStore.SavedItem(
            mediaId    = item.mediaId,
            title      = m.title?.toString()      ?: "",
            artist     = m.artist?.toString()     ?: "",
            album      = m.albumTitle?.toString() ?: "",
            artUri     = m.artworkUri?.toString(),
            albumId    = x?.getString(X_ALBUM_ID),
            artistId   = x?.getString(X_ARTIST_ID),
            durationMs = x?.getLong(X_DURATION, -1L) ?: -1L
        )
    }

    /** The queue in the order it will play (shuffle included), as window indexes. */
    private fun playOrder(): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val shuffle = player.shuffleModeEnabled
        val order   = ArrayList<Int>(timeline.windowCount)
        var index   = timeline.getFirstWindowIndex(shuffle)
        while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        return order
    }

    /**
     * Saves the upcoming songs (in play order) so the queue can be put back
     * instantly when the car starts.  Batched: queue changes come in bursts.
     */
    private fun saveSnapshotSoon() {
        snapshotJob?.cancel()
        snapshotJob = serviceScope.launch {
            delay(1_000)
            val order    = playOrder()
            val position = order.indexOf(player.currentMediaItemIndex).coerceAtLeast(0)
            val items    = order.subList((position - SNAPSHOT_BEHIND).coerceAtLeast(0),
                                         (position + SNAPSHOT_AHEAD).coerceAtMost(order.size))
                .map { toSavedItem(player.getMediaItemAt(it)) }
            withContext(Dispatchers.IO) { resumeStore.saveSnapshot(items) }
        }
    }

    private fun onQueueEnded() {
        if (queueKind == ResumeStore.QueueKind.LIBRARY && mediaFocusRequest != null) {
            // Library shuffle never really ends: deal a fresh batch.
            val generation = queueGeneration
            serviceScope.launch {
                val items = buildLibraryBatch()
                if (generation == queueGeneration && items.isNotEmpty()) {
                    playQueue(items, 0, 0L, ResumeStore.QueueKind.LIBRARY, null)
                }
            }
        }
    }

    // ── Library shuffle ────────────────────────────────────────────────────

    /**
     * Plays the whole library in random order, dealt in random batches that
     * are topped up as they run low, so it never ends.  [first] is played
     * first (from [firstPositionMs]) — used when resuming a library shuffle.
     *
     * Returns false if the server couldn't be reached, so callers can retry.
     */
    private suspend fun startLibraryShuffle(first: MediaItem? = null, firstPositionMs: Long = 0L): Boolean {
        val generation = queueGeneration
        val rest = buildLibraryBatch(setOfNotNull(first?.mediaId))
        // The driver started something else while we were fetching.
        if (generation != queueGeneration) return true
        val queue = listOfNotNull(first) + rest
        if (queue.isEmpty()) {
            Log.w(TAG, "Shuffle all: nothing to play")
            return false
        }
        Log.i(TAG, "Shuffle all: ${queue.size} songs")
        playQueue(queue, 0, if (first != null) firstPositionMs else 0L, ResumeStore.QueueKind.LIBRARY, null)
        return true
    }

    private suspend fun buildLibraryBatch(exclude: Set<String> = emptySet()): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val cfg = subsonicCfg() ?: return@withContext emptyList()
            val songs = try { SubsonicClient.getRandomSongs(cfg, LIBRARY_BATCH) } catch (_: SubsonicException) { emptyList() }
            songItems(cfg, songs)
                .filter { it.mediaId !in exclude }
                .mapNotNull { child -> child.mediaId?.let { resolveMediaItem(it, child.description.extras) } }
        }

    /** Keeps a library shuffle going by appending another random batch. */
    private fun topUpLibraryQueueIfLow() {
        if (queueKind != ResumeStore.QueueKind.LIBRARY) return
        if (libraryTopUpInFlight || player.mediaItemCount == 0) return
        val remaining = player.mediaItemCount - 1 - player.currentMediaItemIndex
        if (remaining > LIBRARY_LOW_WATER) return

        libraryTopUpInFlight = true
        val generation = queueGeneration
        val queued = (0 until player.mediaItemCount).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
        serviceScope.launch {
            try {
                val more = buildLibraryBatch(queued)
                if (generation != queueGeneration || more.isEmpty()) return@launch
                // Drop songs long since played so the queue doesn't grow forever.
                val current = player.currentMediaItemIndex
                if (current > LIBRARY_TRIM_AFTER) {
                    player.removeMediaItems(0, current - LIBRARY_KEEP_BEHIND)
                }
                player.addMediaItems(more)
                // Appended songs play after the current ones, in the order fetched.
                applyShuffleOrder(0)
                publishQueue()
                Log.i(TAG, "Shuffle all: added ${more.size} songs")
            } finally {
                libraryTopUpInFlight = false
            }
        }
    }

    // ── Start-up, and the launcher's source switching ──────────────────────

    private enum class Restore { PLAYED, NOT_READY, GONE }

    /**
     * A command from the SAIC launcher (see [SaicModeBridge]).  When the car
     * starts, the launcher restores the last source after ~10 s: for us, it
     * sends SWITCH_SOURCE_ONLY_PLAY (or _PAUSE if we were paused).  The source
     * button on the wheel / screen sends SWITCH_SOURCE_ONLY.
     */
    private fun handleLauncherCommand(command: CommandParcelable) {
        Log.i(TAG, "Launcher: $command")
        if (command.event != CommandParcelable.UPDATE_SOURCE_AVAILABLE) {
            lastTransportCommandAt = SystemClock.elapsedRealtime()
        }
        when (command.event) {
            CommandParcelable.UPDATE_SOURCE_AVAILABLE  -> SaicModeBridge.reportAvailable(this)
            CommandParcelable.SWITCH_SOURCE_ONLY       -> launcherPlay(explicit = true)
            CommandParcelable.SWITCH_SOURCE_ONLY_PLAY  -> launcherPlay(explicit = false)
            CommandParcelable.SWITCH_SOURCE_ONLY_PAUSE -> launcherPause()
        }
    }

    /** True for the launcher's first request after the head unit boots (its start-up restore). */
    private fun isStartupRequest(): Boolean {
        if (startupHandled) return false
        val now = SystemClock.elapsedRealtime()
        return now < STARTUP_WINDOW_MS || (bootSignalAt > 0 && now - bootSignalAt < STARTUP_WINDOW_MS)
    }

    private fun markStartupHandled() {
        startupHandled = true
        bootFallbackJob?.cancel()
        bootFallbackJob = null
    }

    /** Resume, unless the setting says to shuffle the library. */
    private fun restoreMode(): PlaybackSettings.StartupMode =
        if (PlaybackSettings.startupMode(this) == PlaybackSettings.StartupMode.SHUFFLE_LIBRARY)
            PlaybackSettings.StartupMode.SHUFFLE_LIBRARY else PlaybackSettings.StartupMode.RESUME

    /** The launcher wants our music playing ([explicit]: the driver pressed the source button). */
    private fun launcherPlay(explicit: Boolean) {
        val startup = isStartupRequest()
        if (startup) { traceLauncherAsked(); markStartupHandled() }
        val nothing = PlaybackSettings.startupMode(this) == PlaybackSettings.StartupMode.NOTHING
        val stayPaused = when {
            !explicit && otherAppIsPlaying(ignoreSaic = true) -> { Log.i(TAG, "Launcher: another app is playing — not starting"); true }
            startup && !explicit && nothing  -> { Log.i(TAG, "Launcher: start-up setting is \"nothing\" — not starting"); true }
            else -> false
        }
        if (stayPaused) {
            if (player.mediaItemCount == 0) launchRestore(restoreMode(), resumeStore.load(), play = false)
            return
        }
        if (player.mediaItemCount > 0) {
            cancelStartupAction()
            userPlay()
        } else {
            launchRestore(restoreMode(), resumeStore.load(), play = true)
        }
    }

    /**
     * The launcher wants us as the source, paused.  At start-up that means it
     * last saw us paused — but the head unit can pause music as it powers
     * down, so if we were playing right up to then, carry on playing.
     */
    private fun launcherPause() {
        if (!isStartupRequest()) {
            if (player.isPlaying) userPause()
            return
        }
        traceLauncherAsked()
        markStartupHandled()
        val resume = PlaybackSettings.startupMode(this) != PlaybackSettings.StartupMode.NOTHING &&
                     !otherAppIsPlaying(ignoreSaic = true)
        Log.i(TAG, "Launcher: restore paused → ${if (resume) "playing (start-up always plays)" else "paused"}")
        when {
            player.mediaItemCount == 0 -> launchRestore(restoreMode(), resumeStore.load(), play = resume)
            resume                     -> userPlay()
        }
    }

    /**
     * Sent by BootReceiver.  Normally the launcher asks for us (above); if it
     * hasn't within [BOOT_FALLBACK_MS], start the music ourselves — but
     * never over another app that's already playing (e.g. the radio, if the
     * launcher went back to it).
     */
    private fun scheduleBootFallback() {
        if (startupHandled || bootFallbackJob != null) return
        bootFallbackJob = serviceScope.launch {
            delay(BOOT_FALLBACK_MS)
            bootFallbackJob = null
            if (startupHandled) return@launch
            startupHandled = true
            val mode  = PlaybackSettings.startupMode(this@MediaService)
            val saved = resumeStore.load()
            Log.i(TAG, "Startup (launcher didn't ask): mode=$mode, last queue=${saved?.kind}")
            if (mode == PlaybackSettings.StartupMode.NOTHING) return@launch
            if (player.playWhenReady) return@launch
            traceAuto = true
            if (player.mediaItemCount > 0) {
                // Got ready at start-up but nobody asked for it: play.
                if (otherAppIsPlaying()) {
                    Log.i(TAG, "Startup: another app is already playing — leaving it alone")
                } else {
                    userPlay()
                }
                return@launch
            }
            launchRestore(mode, saved, play = true, leaveOthersAlone = true)
        }
    }

    /**
     * Play pressed with nothing loaded (e.g. the first press after the car
     * starts): resume the saved queue — or shuffle the library, if that's the
     * startup setting or there's nothing to resume.
     */
    private fun restoreOnPlay() {
        if (startupJob?.isActive == true) return   // already on its way
        launchRestore(restoreMode(), resumeStore.load(), play = true)
    }

    /**
     * Puts the last queue back.  First from the saved queue ([ResumeStore.saveSnapshot]),
     * which needs no network, so it's instant; the song itself starts as soon
     * as it can be fetched (straight away if it's cached), meanwhile we hold
     * the speakers so the car doesn't swap to the radio.  If there's no saved
     * queue, rebuild it from the server, waiting for the network.
     *
     * [play] false: load it paused (only from the saved queue).
     * [leaveOthersAlone]: give up if another app starts playing meanwhile.
     */
    private fun launchRestore(
        mode:  PlaybackSettings.StartupMode,
        saved: ResumeStore.State?,
        play:  Boolean,
        leaveOthersAlone: Boolean = false
    ) {
        startupJob?.cancel()
        startupJob = serviceScope.launch {
            if (leaveOthersAlone && otherAppIsPlaying()) {
                Log.i(TAG, "Startup: another app is already playing — leaving it alone")
                return@launch
            }
            if (saved != null && restoreFromSnapshot(mode, saved, play)) return@launch
            if (!play) return@launch
            val started    = SystemClock.elapsedRealtime()
            var resumeGone = false
            while (isActive) {
                if (leaveOthersAlone && otherAppIsPlaying()) {
                    Log.i(TAG, "Startup: another app is already playing — leaving it alone")
                    return@launch
                }
                val tryResume = mode == PlaybackSettings.StartupMode.RESUME && saved != null && !resumeGone
                val done = if (tryResume) {
                    when (resumeSaved(saved!!)) {
                        Restore.PLAYED    -> true
                        Restore.NOT_READY -> false
                        Restore.GONE      -> {
                            Log.i(TAG, "Resume: what was playing no longer exists — shuffling instead")
                            resumeGone = true
                            startLibraryShuffle()
                        }
                    }
                } else {
                    startLibraryShuffle()
                }
                if (done) return@launch
                if (SystemClock.elapsedRealtime() - started > STARTUP_GIVE_UP_MS) {
                    Log.w(TAG, "Restore: gave up waiting for the server")
                    return@launch
                }
                delay(STARTUP_RETRY_MS)
            }
        }
    }

    /** Rebuilds the queue from the saved copy (see [launchRestore]).  False if there isn't one to use. */
    private suspend fun restoreFromSnapshot(mode: PlaybackSettings.StartupMode, saved: ResumeStore.State, play: Boolean): Boolean {
        if (saved.source != ONLINE_ROOT) return false
        val library = saved.kind == ResumeStore.QueueKind.LIBRARY
        // "Shuffle the library" at start-up: a saved library shuffle is just
        // that, so carry on with it; any other saved queue isn't.
        if (mode == PlaybackSettings.StartupMode.SHUFFLE_LIBRARY && !library) return false
        val cfg = subsonicCfg() ?: return false
        val snapshot = withContext(Dispatchers.IO) { resumeStore.loadSnapshot() }
        val items = snapshot.mapNotNull { savedToPlayable(it, cfg) }
        if (items.isEmpty()) return false
        val current = saved.item?.let { s -> items.indexOfFirst { it.mediaId == s.mediaId } } ?: -1
        var start    = current.coerceAtLeast(0)
        var position = if (current >= 0) saved.positionMs else 0L
        if (mode == PlaybackSettings.StartupMode.SHUFFLE_LIBRARY && current >= 0 && current + 1 < items.size) {
            // A fresh song, not the middle of the last one: the first one
            // coming up that's already downloaded, so it starts without
            // waiting for the network.
            val ahead = (current + 1 until minOf(items.size, current + 11)).toList()
            start = withContext(Dispatchers.IO) {
                ahead.firstOrNull { MediaCache.isCached(this@MediaService, items[it].mediaId) }
            } ?: (current + 1)
            position = 0L
        }
        Log.i(TAG, "Restoring the saved queue (${items.size} songs, ${saved.kind}) ${if (play) "playing" else "paused"}")
        // The saved copy is already in play order.
        player.shuffleModeEnabled = false
        playQueue(items, start, position, saved.kind, saved.parentId, play = play)
        return true
    }

    // ── Start-up timing ────────────────────────────────────────────────────

    private fun startTrace(from: Long) {
        traceFrom       = from
        traceQueueAt    = -1L
        traceLauncherAt = -1L
        traceOnDiskPct  = -1
        traceAuto       = false
    }

    private fun traceLauncherAsked() {
        traceAuto = true
        if (traceFrom >= 0 && traceLauncherAt < 0) traceLauncherAt = SystemClock.elapsedRealtime()
    }

    private fun traceQueueReady(mediaId: String) {
        if (traceFrom < 0 || traceQueueAt >= 0) return
        traceQueueAt = SystemClock.elapsedRealtime()
        traceOnDiskPct = try {
            MediaCache.cachedFraction(this, mediaId)?.let { (it * 100).toInt() }
                ?: if (MediaCache.cachedBytesFromStart(this, mediaId) > 0) PARTLY else 0
        } catch (_: Exception) { -1 }
    }

    /** Music is playing: record how long this start-up took (if we're timing one). */
    private fun finishTrace() {
        val from = traceFrom
        if (from < 0) return
        traceFrom = -1L
        val now = SystemClock.elapsedRealtime()
        if (!traceAuto) return                            // started by a press, not at start-up
        if (now - from > STARTUP_WINDOW_MS * 2) return   // not a start-up any more
        fun rel(t: Long) = if (t >= 0) t - from else -1L
        val onDisk = if (traceOnDiskPct == PARTLY) "partly" else "$traceOnDiskPct%"
        Log.i(TAG, "Startup timing: queue back ${rel(traceQueueAt)} ms, launcher asked ${rel(traceLauncherAt)} ms, " +
            "playing ${now - from} ms after the head unit started; first song $onDisk on disk")
    }

    /**
     * Skip/seek with nothing loaded does nothing, so it shouldn't cancel a
     * resume that's still waiting for the network.
     */
    private fun cancelStartupIfQueueLoaded() {
        if (player.mediaItemCount > 0) cancelStartupAction()
    }

    /** The driver did something — they're in charge now, so drop any pending startup action. */
    private fun cancelStartupAction() {
        val job = startupJob ?: return
        if (job.isActive) {
            Log.i(TAG, "Startup: cancelled — driver took over")
            job.cancel()
        }
        startupJob = null
    }

    /**
     * True if another app is playing.  [ignoreSaic]: don't count the car's own
     * sources (radio…) — when the launcher asks for us, it has already
     * switched those off, and their sessions can still say "playing".
     */
    private fun otherAppIsPlaying(ignoreSaic: Boolean = false): Boolean {
        if (otherAudioActive()) return true
        val other = keyRouter.playingElsewhere() ?: return false
        return !(ignoreSaic && other.packageName.startsWith("com.saicmotor."))
    }

    private suspend fun serverReady(): Boolean = withContext(Dispatchers.IO) {
        subsonicCfg()?.let { SubsonicClient.isReachable(it) } ?: false
    }

    /** Rebuilds the saved queue and carries on from the saved position. */
    private suspend fun resumeSaved(saved: ResumeStore.State): Restore {
        if (saved.source != ONLINE_ROOT) return Restore.GONE   // saved by an older, USB-capable version
        if (!serverReady()) return Restore.NOT_READY
        val item = saved.item
        return when (saved.kind) {
            ResumeStore.QueueKind.BROWSE -> {
                val parent = saved.parentId ?: return resumeSingle(saved)
                val items = playableChildren(parent)
                if (items.isEmpty()) return Restore.GONE
                val index = items.indexOfFirst { it.mediaId == item?.mediaId }
                playQueue(items, maxOf(index, 0), if (index >= 0) saved.positionMs else 0L,
                          ResumeStore.QueueKind.BROWSE, parent)
                Restore.PLAYED
            }
            ResumeStore.QueueKind.LIBRARY -> {
                val first = item?.let { savedToPlayable(it) }
                if (startLibraryShuffle(first, saved.positionMs)) Restore.PLAYED else Restore.NOT_READY
            }
            ResumeStore.QueueKind.SINGLE -> resumeSingle(saved)
        }
    }

    private fun resumeSingle(saved: ResumeStore.State): Restore {
        val first = saved.item?.let { savedToPlayable(it) } ?: return Restore.GONE
        playQueue(listOf(first), 0, saved.positionMs, ResumeStore.QueueKind.SINGLE, null)
        return Restore.PLAYED
    }

    /** Fetches a song's details from the server and makes it playable. */
    private suspend fun lookUpSong(mediaId: String): MediaItem? = withContext(Dispatchers.IO) {
        if (!mediaId.startsWith(SUBSONIC_SONG_PREFIX)) return@withContext null
        val cfg = subsonicCfg() ?: return@withContext null
        val song = try { SubsonicClient.getSong(cfg, mediaId.removePrefix(SUBSONIC_SONG_PREFIX)) }
                   catch (_: SubsonicException) { return@withContext null }
        songItems(cfg, listOf(song)).firstOrNull()?.let { resolveMediaItem(mediaId, it.description.extras) }
    }

    /** Rebuilds a playable item from what [ResumeStore] saved. */
    private fun savedToPlayable(saved: ResumeStore.SavedItem, config: SubsonicSettings.Config? = null): MediaItem? {
        val id  = saved.mediaId.takeIf { it.startsWith(SUBSONIC_SONG_PREFIX) } ?: return null
        val cfg = config ?: subsonicCfg() ?: return null
        val extras = Bundle().apply {
            putString(X_TITLE,  saved.title)
            putString(X_ARTIST, saved.artist)
            putString(X_ALBUM,  saved.album)
            saved.artUri?.let { putString(X_ART, it) }
            saved.albumId?.let { putString(X_ALBUM_ID, it) }
            saved.artistId?.let { putString(X_ARTIST_ID, it) }
            putLong(X_DURATION, saved.durationMs)
            // A fresh login token: the saved one may be out of date.
            putString(X_STREAM, SubsonicClient.streamUrl(cfg, id.removePrefix(SUBSONIC_SONG_PREFIX)))
        }
        return resolveMediaItem(id, extras)
    }

    /** Builds a playable [MediaItem] from a mediaId + the extras bundle we stashed at browse time. */
    private fun resolveMediaItem(mediaId: String, extras: Bundle?): MediaItem? {
        if (!mediaId.startsWith(SUBSONIC_SONG_PREFIX)) return null
        val streamUri = extras?.getString(X_STREAM)?.let { Uri.parse(it) } ?: return null
        val meta = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(extras.getString(X_TITLE))
            .setArtist(extras.getString(X_ARTIST))
            .setAlbumTitle(extras.getString(X_ALBUM))
            .apply { extras.getString(X_ART)?.let { setArtworkUri(Uri.parse(it)) } }
            .setExtras(Bundle().apply {
                putLong(X_DURATION,    extras.getLong(X_DURATION, -1L))
                putString(X_ALBUM_ID,  extras.getString(X_ALBUM_ID))
                putString(X_ARTIST_ID, extras.getString(X_ARTIST_ID))
            })
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(streamUri)
            .setCustomCacheKey(MediaCache.keyFor(mediaId))
            .setMediaMetadata(meta)
            .build()
    }

    // ── MediaBrowserServiceCompat — browsing contract ──────────────────────

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        Log.i(TAG, "Browse connection from $clientPackageName (uid $clientUid)")
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        serviceScope.launch { result.sendResult(loadChildren(parentId)) }
    }

    override fun onCustomAction(action: String, extras: Bundle?, result: Result<Bundle>) {
        if (handleCustomAction(action, extras)) result.sendResult(null)
        else super.onCustomAction(action, extras, result)
    }

    private suspend fun loadChildren(parentId: String): MutableList<MediaBrowserCompat.MediaItem> =
        withContext(Dispatchers.IO) {
            if (parentId == ROOT_ID || parentId == ONLINE_ROOT) return@withContext categoryItems()
            if (parentId.startsWith(PLAYLIST_PREFIX)) {
                val id = parentId.removePrefix(PLAYLIST_PREFIX)
                val offline = OfflinePlaylistStore.get(this@MediaService, id)
                val cfg = subsonicCfg()
                if (cfg == null || !networkAvailable())
                    return@withContext offline?.let { songItems(cfg, it.songs) } ?: mutableListOf()
                try {
                    return@withContext songItems(cfg, SubsonicClient.getPlaylist(cfg, id).songs)
                } catch (e: SubsonicException) {
                    if (offline != null) {
                        Log.i(TAG, "Using the offline copy of playlist $id")
                        return@withContext songItems(cfg, offline.songs)
                    }
                    Log.w(TAG, "Couldn't load playlist $id: ${e.kind}")
                    return@withContext mutableListOf()
                }
            }
            val cfg = subsonicCfg() ?: return@withContext mutableListOf()
            try {
                when {
                    parentId == ARTISTS_ID   -> SubsonicClient.getArtists(cfg).map { a ->
                        browsable(artistNodeId(a.id), a.name, null, cover(cfg, a.coverArt))
                    }.toMutableList()
                    parentId == ALBUMS_ID    -> albumItems(cfg, SubsonicClient.getAllAlbums(cfg))
                    parentId == NEWEST_ID    -> albumItems(cfg, SubsonicClient.getAlbumList(cfg, "newest", 100))
                    parentId == RECENT_ID    -> albumItems(cfg, SubsonicClient.getAlbumList(cfg, "recent", 100))
                    parentId == FREQUENT_ID  -> albumItems(cfg, SubsonicClient.getAlbumList(cfg, "frequent", 100))
                    parentId == GENRES_ID    -> SubsonicClient.getGenres(cfg)
                        .filter { it.albumCount > 0 }
                        .sortedBy { it.name.lowercase() }
                        .map { g -> browsable(genreNodeId(g.name), g.name,
                            resources.getQuantityString(R.plurals.albums, g.albumCount, g.albumCount)) }
                        .toMutableList()
                    parentId == PLAYLISTS_ID -> SubsonicClient.getPlaylists(cfg).map { p ->
                        browsable(playlistNodeId(p.id), p.name, getString(R.string.playlist_song_count, p.songCount), cover(cfg, p.coverArt))
                    }.toMutableList()
                    parentId.startsWith(PLAYLIST_PREFIX) ->
                        songItems(cfg, SubsonicClient.getPlaylist(cfg, parentId.removePrefix(PLAYLIST_PREFIX)).songs)
                    parentId.startsWith(ALBUM_PREFIX) ->
                        songItems(cfg, SubsonicClient.getAlbum(cfg, parentId.removePrefix(ALBUM_PREFIX)).songs)
                    parentId.startsWith(ARTIST_SONGS_PREFIX) ->
                        songItems(cfg, artistSongs(cfg, parentId.removePrefix(ARTIST_SONGS_PREFIX)))
                    parentId.startsWith(ARTIST_PREFIX) ->
                        albumItems(cfg, SubsonicClient.getArtist(cfg, parentId.removePrefix(ARTIST_PREFIX)).albums)
                    parentId.startsWith(GENRE_SONGS_PREFIX) -> songItems(cfg,
                        SubsonicClient.getRandomSongs(cfg, GENRE_BATCH, Uri.decode(parentId.removePrefix(GENRE_SONGS_PREFIX))))
                    parentId.startsWith(GENRE_PREFIX) -> albumItems(cfg,
                        SubsonicClient.getAllAlbums(cfg, "byGenre", Uri.decode(parentId.removePrefix(GENRE_PREFIX))))
                    parentId.startsWith(SEARCH_PREFIX) -> {
                        val query = Uri.decode(parentId.removePrefix(SEARCH_PREFIX)).trim()
                        if (query.isEmpty()) mutableListOf() else {
                            val found = SubsonicClient.search(cfg, query)
                            (found.artists.map { a -> browsable(artistNodeId(a.id), a.name, getString(R.string.segment_artists), cover(cfg, a.coverArt)) } +
                             albumItems(cfg, found.albums) + songItems(cfg, found.songs)).toMutableList()
                        }
                    }
                    else -> mutableListOf()
                }
            } catch (e: SubsonicException) {
                Log.w(TAG, "Couldn't load $parentId: ${e.kind}")
                mutableListOf()
            }
        }

    /** Every song by an artist: albums oldest first, each in track order. */
    private fun artistSongs(cfg: SubsonicSettings.Config, artistId: String): List<SubsonicSong> =
        SubsonicClient.getArtist(cfg, artistId).albums
            .sortedWith(compareBy({ it.year }, { it.name.lowercase() }))
            .flatMap { album ->
                try { SubsonicClient.getAlbum(cfg, album.id).songs } catch (_: SubsonicException) { emptyList() }
            }

    private fun categoryItems(): MutableList<MediaBrowserCompat.MediaItem> =
        CATEGORIES.map { c ->
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder().setMediaId(c.id).setTitle(getString(c.label)).build(),
                if (c.playable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                else            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            )
        }.toMutableList()

    private fun browsable(id: String, title: String, subtitle: String? = null, icon: Uri? = null) =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id).setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .apply { if (icon != null) setIconUri(icon) }
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )

    private fun cover(cfg: SubsonicSettings.Config, coverArt: String?, size: Int = 300): Uri? =
        coverArt?.let { Uri.parse(SubsonicClient.coverArtUrl(cfg, it, size)) }

    private fun albumItems(cfg: SubsonicSettings.Config, albums: List<SubsonicAlbum>) =
        albums.map { a -> browsable(albumNodeId(a.id), a.name, a.artist, cover(cfg, a.coverArt)) }.toMutableList()

    private fun songItems(cfg: SubsonicSettings.Config?, songs: List<SubsonicSong>): MutableList<MediaBrowserCompat.MediaItem> =
        songs.map { song ->
            val artUrl = if (cfg == null) null else song.coverArt?.let { SubsonicClient.coverArtUrl(cfg, it, NOW_PLAYING_ART_PX) }
            val extras = Bundle().apply {
                putLong(X_DURATION,    song.durationMs)
                putString(X_STREAM,    cfg?.let { SubsonicClient.streamUrl(it, song.id) }
                    ?: "https://offline.invalid/rest/stream?id=${Uri.encode(song.id)}")
                putString(X_TITLE,     song.title)
                putString(X_ARTIST,    song.artist ?: "")
                putString(X_ALBUM,     song.album  ?: "")
                putString(X_ALBUM_ID,  song.albumId)
                putString(X_ARTIST_ID, song.artistId)
                if (artUrl != null) putString(X_ART, artUrl)
            }
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(songMediaId(song.id))
                    .setTitle(song.title).setSubtitle(song.artist)
                    .apply { cfg?.let { server -> song.coverArt?.let { setIconUri(Uri.parse(SubsonicClient.coverArtUrl(server, it))) } } }
                    .setExtras(extras).build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        }.toMutableList()

    private fun subsonicCfg(): SubsonicSettings.Config? =
        SubsonicSettings.load(this).takeIf { it.isValid }

    // ── Widgets & notification ─────────────────────────────────────────────

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
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
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
            .addAction(R.drawable.ic_skip_previous, getString(R.string.previous), pendingServiceAction(ACTION_PREVIOUS, 10))
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (isPlaying) R.string.pause else R.string.play),
                pendingServiceAction(ACTION_PLAY_PAUSE, 11)
            )
            .addAction(R.drawable.ic_skip_next, getString(R.string.next), pendingServiceAction(ACTION_NEXT, 12))
            .build()
    }

    private fun pendingMainActivity(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun pendingServiceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, MediaService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { description = getString(R.string.notification_channel_description); setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
