package shots

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.provider.Settings
import android.support.v4.media.session.PlaybackStateCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import com.github.takahirom.roborazzi.captureRoboImage
import com.saicmotor.media.data.Library
import com.saicmotor.media.data.LibrarySource
import com.saicmotor.media.subsonic.*
import com.saicmotor.media.ui.activity.MainActivity
import com.saicmotor.media.ui.common.NowPlaying
import com.saicmotor.media.ui.common.PlayerState
import com.saicmotor.media.ui.common.QueueEntry
import com.saicmotor.media.ui.common.Transport
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration
import kotlin.math.abs

// ── Sample library (invented names) ──────────────────────────────────────────

object Sample {
    val artistNames = listOf(
        "Harbour Lights", "Mara Lindqvist", "Southerly Buster", "The Long Weekend", "Night Freight",
        "Coastal Lines", "Ivy & the Gravel Road", "Kestrel", "Low Tide Choir", "Paper Planes Club",
        "Ruth Okonkwo", "Saltwater Static"
    )
    val albumNames = listOf(
        "After Midnight", "Late Night Radio", "Overpass", "Tide Tables", "Paper Weight", "Cold Sun",
        "Mile Markers", "Lantern Season", "Gravel & Glass", "All the Long Roads", "Kestrel II", "Breakwater",
        "Static Bloom", "Low Light", "Echo Lane", "Second Wind", "Shoreline Hymns", "Night Shift",
        "The Quiet Hours", "Undertow", "Ferry Songs", "Golden Hour Traffic", "Signal Fires", "Northbound",
        "Moth Light", "Open Water", "Heat Haze", "Late Checkout", "Sunday Drivers", "Rain on the Highway"
    )
    val songNames = listOf(
        "Golden Hour Traffic", "Westbound at Dusk", "Lighthouse Keeper", "Cold Coffee, Warm Hands",
        "Pier Lights", "Breakwater", "Half Past Nowhere", "The Long Way Round", "Kites over the Beach",
        "Two Lanes", "Last Ferry Home", "Static on the AM Band", "Salt in the Air", "Neon Fish & Chips",
        "Every Exit", "Slow Burn"
    )

    val artists = artistNames.mapIndexed { i, n ->
        SubsonicArtist("ar$i", n, if (i % 4 == 3) null else "ar$i", albumCount = 1 + (i * 7) % 6)
    }
    val albums = albumNames.mapIndexed { i, n ->
        val a = artists[i % artists.size]
        SubsonicAlbum("al$i", n, a.name, "al$i", a.id, 2008 + (i * 3) % 18, 9 + i % 6, 2400 + i * 97)
    }
    fun songs(albumIndex: Int, count: Int = 11): List<SubsonicSong> {
        val album = albums[albumIndex]
        return (0 until count).map { t ->
            SubsonicSong(
                "s$albumIndex-$t", songNames[(albumIndex + t) % songNames.size],
                if (t == 4) "Mara Lindqvist" else album.artist, album.name,
                (150 + (t * 37) % 140) * 1000L, album.coverArt, album.id, album.artistId,
                track = t + 1
            )
        }
    }
    val genres = listOf("Rock" to 42, "Electronic" to 18, "Folk" to 11, "Jazz" to 9, "Hip-Hop" to 14,
        "Indie" to 37, "Ambient" to 6, "Soundtrack" to 4, "Pop" to 25, "Blues" to 3, "Classical" to 5, "Metal" to 8)
        .map { (n, c) -> SubsonicGenre(n, c, c * 11) }
}

class FakeLibrary(var configured: Boolean = true, var failWith: SubsonicException? = null) : LibrarySource {
    override val isConfigured get() = configured
    override val serverName get() = "music.example.com"
    override fun coverUrl(coverArt: String?, sizePx: Int) = coverArt?.let { "fake://cover/$it" }
    private fun <T> answer(v: () -> T): T { failWith?.let { throw it }; return v() }
    override fun albumList(type: String, size: Int) = answer {
        val shift = when (type) { "recent" -> 3; "newest" -> 11; "frequent" -> 19; else -> 7 }
        (0 until 12).map { Sample.albums[(it * 5 + shift) % Sample.albums.size] }
    }
    override fun allAlbums() = answer { Sample.albums.sortedBy { it.name } }
    override fun artists() = answer { Sample.artists.sortedBy { it.name } }
    override fun artist(id: String) = answer {
        val a = Sample.artists.first { it.id == id }
        SubsonicArtistDetail(a, Sample.albums.filter { it.artistId == id } + Sample.albums.take(4))
    }
    override fun album(id: String) = answer {
        val i = Sample.albums.indexOfFirst { it.id == id }
        SubsonicAlbumDetail(Sample.albums[i], Sample.songs(i))
    }
    override fun genres() = answer { Sample.genres }
    override fun genreAlbums(genre: String) = answer { Sample.albums.take(13) }
    override fun search(query: String) = answer {
        SubsonicResults(Sample.artists.take(4), Sample.albums.drop(4).take(8), (0 until 6).flatMap { Sample.songs(it * 2, 2) })
    }
    override fun ping() = answer { SubsonicServerInfo("navidrome", "0.53.3") }
}

/** Stand-in covers: gradients with simple shapes, a different look per album. */
object FakeCovers {
    private val palettes = listOf(
        intArrayOf(0xFF1D3557.toInt(), 0xFFE63946.toInt(), 0xFFF1FAEE.toInt()),
        intArrayOf(0xFF264653.toInt(), 0xFF2A9D8F.toInt(), 0xFFE9C46A.toInt()),
        intArrayOf(0xFF3D348B.toInt(), 0xFF7678ED.toInt(), 0xFFF7B801.toInt()),
        intArrayOf(0xFF0B3954.toInt(), 0xFF087E8B.toInt(), 0xFFBFD7EA.toInt()),
        intArrayOf(0xFF6B2737.toInt(), 0xFFE08E45.toInt(), 0xFFF8F4A6.toInt()),
        intArrayOf(0xFF22223B.toInt(), 0xFF4A4E69.toInt(), 0xFFC9ADA7.toInt()),
        intArrayOf(0xFF2B2D42.toInt(), 0xFF8D99AE.toInt(), 0xFFEF233C.toInt()),
        intArrayOf(0xFF283618.toInt(), 0xFF606C38.toInt(), 0xFFDDA15E.toInt()),
        intArrayOf(0xFF03071E.toInt(), 0xFF9D0208.toInt(), 0xFFFFBA08.toInt()),
        intArrayOf(0xFF5F0F40.toInt(), 0xFF0F4C5C.toInt(), 0xFFFB8B24.toInt())
    )

    fun paint(key: String, size: Int = 480): Bitmap {
        val h = abs(key.hashCode())
        val p = palettes[h % palettes.size]
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), p[0], p[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = p[2]
        when (h / 7 % 4) {
            0 -> c.drawCircle(size * 0.62f, size * 0.38f, size * 0.24f, paint)
            1 -> for (i in 0 until 6) c.drawRect(0f, size * (0.1f + i * 0.15f), size.toFloat(), size * (0.16f + i * 0.15f), paint)
            2 -> { paint.alpha = 210; c.drawCircle(size * 0.3f, size * 0.7f, size * 0.42f, paint) }
            else -> { paint.strokeWidth = size * 0.05f; for (i in -4..8) c.drawLine(i * size / 6f, 0f, i * size / 6f + size, size.toFloat(), paint) }
        }
        return bmp
    }
}

// ── Screenshots ──────────────────────────────────────────────────────────────

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi", shadows = [InstantAudioTrack::class])
class Shots {

    private val out = java.io.File("build/shots").absolutePath
    private lateinit var fake: FakeLibrary

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        fake = FakeLibrary()
        Library.source = fake
        Library.io = Dispatchers.Unconfined
        Library.clearCache()
        Coil.setImageLoader(
            ImageLoader.Builder(app)
                .components {
                    add(Interceptor { chain ->
                        val key = chain.request.data.toString()
                        SuccessResult(BitmapDrawable(app.resources, FakeCovers.paint(key)), chain.request, DataSource.MEMORY)
                    })
                }
                .build()
        )
        // No real MediaService here: make binding to it fail quietly.
        shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, com.saicmotor.media.service.MediaService::class.java))
        PlayerState.nowPlaying.value = null
        PlayerState.transport.value = Transport()
        PlayerState.queue.value = emptyList()
    }

    private fun nightMode(night: Boolean) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Settings.System.putInt(ctx.contentResolver, "SKIN_THEME_CONFIG", if (night) 0 else 1)
    }

    private fun playing() {
        PlayerState.nowPlaying.value = NowPlaying(
            mediaId = "sub:s21-0", title = "Golden Hour Traffic", artist = "Harbour Lights",
            album = "After Midnight", artUri = "fake://cover/al21", durationMs = 237_000,
            albumId = "al21", artistId = "ar0"
        )
        PlayerState.transport.value = Transport(
            state = PlaybackStateCompat.STATE_PAUSED, positionMs = 84_000, updatedAt = 0, speed = 0f,
            activeQueueId = 3
        )
        PlayerState.queue.value = (0 until 14).map { i ->
            val s = Sample.songs(i, 1)[0]
            QueueEntry(i.toLong(), "sub:${s.id}", s.title, s.artist.orEmpty(), "fake://cover/${s.coverArt}", s.durationMs)
        }
    }

    private fun settle() {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(6) { looper.idleFor(Duration.ofMillis(300)) }
    }

    private var activity: org.robolectric.android.controller.ActivityController<MainActivity>? = null

    private fun launch(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller
        settle()
        return controller.get()
    }

    @org.junit.After
    fun tearDown() {
        // AppCompat keeps live activities in a static list; close this one so
        // the next test's skin change doesn't recreate it.
        try { activity?.pause()?.stop()?.destroy() } catch (_: Exception) {}
        activity = null
    }

    private fun MainActivity.snap(name: String) {
        settle()
        window.decorView.captureRoboImage("$out/$name.png")
    }

    @Test fun home_night()  { nightMode(true);  playing(); launch().snap("01_home_night") }
    @Test fun home_day()    { nightMode(false); playing(); launch().snap("02_home_day") }
    @Test fun album_night() { nightMode(true);  playing(); launch().apply { openAlbum("al21") }.snap("03_album_night") }
    @Test fun album_day()   { nightMode(false); playing(); launch().apply { openAlbum("al21") }.snap("04_album_day") }
    @Test fun artist_night() { nightMode(true); playing(); launch().apply { openArtist("ar0") }.snap("05_artist_night") }
    @Test fun library_albums() {
        nightMode(true); playing()
        launch().apply {
            show(MainActivity.Destination.LIBRARY); settle()
            findViewById<android.view.View>(com.saicmotor.media.R.id.seg_albums).performClick()
        }.snap("06_library_albums")
    }
    @Test fun no_cover_night() {
        nightMode(true); playing()
        PlayerState.nowPlaying.value = PlayerState.nowPlaying.value!!.copy(artUri = null, title = "Static on the AM Band")
        launch().apply { show(MainActivity.Destination.QUEUE) }.snap("16_no_cover_queue_night")
    }
    @Test fun settings_day_connected() {
        nightMode(false); playing()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        com.saicmotor.media.data.SubsonicSettings.save(ctx, com.saicmotor.media.data.SubsonicSettings.Config("https://music.example.com", "demo", "secret"))
        launch().apply { show(MainActivity.Destination.SETTINGS) }.snap("17_settings_day")
    }
    /** Settings scrolled to the bottom: the server, which is set once. */
    @Test fun settings_server_at_bottom() {
        nightMode(true); playing()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        com.saicmotor.media.data.SubsonicSettings.save(ctx, com.saicmotor.media.data.SubsonicSettings.Config("https://music.example.com", "demo", "secret"))
        val a = launch().apply { show(MainActivity.Destination.SETTINGS) }
        settle()
        a.findViewById<android.view.View>(com.saicmotor.media.R.id.server_heading)?.let { v ->
            (v.parent.parent as? android.widget.ScrollView)?.scrollTo(0, 10_000)
        }
        a.snap("21_settings_server_at_bottom_night")
    }
    /** The bottom of Settings while an update downloads. */
    @Test fun settings_update_downloading() {
        nightMode(true); playing()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        com.saicmotor.media.data.SubsonicSettings.save(ctx, com.saicmotor.media.data.SubsonicSettings.Config("https://music.example.com", "demo", "secret"))
        com.saicmotor.media.update.Updater.post(com.saicmotor.media.update.Updater.Status.Downloading("2.0.11", 42))
        val a = launch().apply { show(MainActivity.Destination.SETTINGS) }
        settle()
        a.findViewById<android.view.View>(com.saicmotor.media.R.id.server_heading)?.let { v ->
            (v.parent.parent as? android.widget.ScrollView)?.scrollTo(0, 10_000)
        }
        a.snap("22_settings_update_downloading_night")
        com.saicmotor.media.update.Updater.post(com.saicmotor.media.update.Updater.Status.UpToDate("2.0.10"))
        settle()
        a.snap("23_settings_update_latest_night")
        com.saicmotor.media.update.Updater.post(com.saicmotor.media.update.Updater.Status.Idle)
    }
    @Test fun library_artists() {
        nightMode(true); playing()
        launch().apply {
            show(MainActivity.Destination.LIBRARY); settle()
            findViewById<android.view.View>(com.saicmotor.media.R.id.seg_artists).performClick()
        }.snap("07_library_artists")
    }
    @Test fun library_genres() {
        nightMode(false); playing()
        launch().apply {
            show(MainActivity.Destination.LIBRARY); settle()
            findViewById<android.view.View>(com.saicmotor.media.R.id.seg_genres).performClick()
        }.snap("08_library_genres_day")
    }
    @Test fun search_night() {
        nightMode(true); playing()
        launch().apply {
            show(MainActivity.Destination.SEARCH); settle()
            findViewById<android.widget.EditText>(com.saicmotor.media.R.id.query).setText("light")
            settle()
        }.snap("10_search_night")
    }
    /** The start of a song: the whole progress ball shows at the far left. */
    @Test fun song_start_night() {
        nightMode(true); playing()
        PlayerState.transport.value = PlayerState.transport.value.copy(positionMs = 0)
        launch().snap("24_song_start_night")
    }
    @Test fun queue_night()    { nightMode(true); playing(); launch().apply { show(MainActivity.Destination.QUEUE) }.snap("11_queue_night") }
    @Test fun settings_night() { nightMode(true); playing(); launch().apply { show(MainActivity.Destination.SETTINGS) }.snap("12_settings_night") }
    @Test fun empty_first_run() { nightMode(true); fake.configured = false; launch().snap("13_first_run_night") }
    @Test fun offline_day() {
        nightMode(false)
        fake.failWith = SubsonicException(SubsonicException.Kind.NETWORK, "Couldn't reach the server")
        launch().snap("14_offline_day")
    }
    @Test fun widgets() {
        nightMode(true)
        val a = launch()
        val host = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(40, 40, 40, 40)
        }
        val inflater = android.view.LayoutInflater.from(a)
        fun add(layout: Int, w: Int, h: Int) {
            val v = inflater.inflate(layout, host, false)
            host.addView(v, android.widget.LinearLayout.LayoutParams(w, h).apply { marginEnd = 40 })
            v.findViewById<android.widget.TextView>(com.saicmotor.media.R.id.widget_title)?.text = "Golden Hour Traffic"
            v.findViewById<android.widget.TextView>(com.saicmotor.media.R.id.widget_artist)?.text = "Harbour Lights"
        }
        add(com.saicmotor.media.R.layout.widget_1x1, 213, 213)
        add(com.saicmotor.media.R.layout.widget_2x1, 450, 213)
        add(com.saicmotor.media.R.layout.widget_2x2, 500, 500)
        a.setContentView(host)
        a.snap("18_widgets_night")
    }
    @Test fun nothing_playing_night() { nightMode(true); launch().snap("15_nothing_playing_night") }
    /** The last song of a long album is playing: the album page opens scrolled to it. */
    @Test fun album_follows_playing_night() {
        nightMode(true); playing()
        val last = Sample.songs(21)[10]
        PlayerState.nowPlaying.value = PlayerState.nowPlaying.value!!.copy(mediaId = "sub:${last.id}", title = last.title)
        launch().apply { openAlbum("al21") }.snap("19_album_follows_playing_night")
    }
}
