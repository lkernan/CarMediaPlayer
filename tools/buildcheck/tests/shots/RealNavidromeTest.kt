package shots

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.media.MediaFormat
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saicmotor.common.CommandParcelable
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.service.MediaCache
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.service.ResumeStore
import com.saicmotor.media.subsonic.SubsonicClient
import com.saicmotor.media.subsonic.SubsonicSong
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaCodecList
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32

/**
 * The app against a real Navidrome, through a [Relay] that can break the
 * connection.  Skipped unless one is running: see tools/navidrome/README.md
 * (NAVIDROME_URL, a user "me" / "secret", and the made-up library from
 * make-library.sh).  A stand-in decoder notes a checksum of every packet it's
 * given, compared with the songs as the server sends them.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi", shadows = [InstantAudioTrack::class])
class RealNavidromeTest {

    private val serverUrl: String? = System.getenv("NAVIDROME_URL")?.takeIf { it.isNotBlank() }?.trimEnd('/')
    private val user = System.getenv("NAVIDROME_USER") ?: "me"
    private val password = System.getenv("NAVIDROME_PASSWORD") ?: "secret"

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private lateinit var relay: Relay
    private lateinit var direct: SubsonicSettings.Config
    /** Checksums of the packets the decoder was given, in order. */
    private val heard = CopyOnWriteArrayList<Long>()

    @Before
    fun setUp() {
        assumeTrue("no Navidrome to test against (NAVIDROME_URL)", serverUrl != null)
        if (System.getenv("NAVIDROME_LOG") != null) org.robolectric.shadows.ShadowLog.stream = System.out
        val field = MediaCache::class.java.getDeclaredField("cache").apply { isAccessible = true }
        (field.get(null) as? androidx.media3.datasource.cache.SimpleCache)?.release()
        field.set(null, null)
        direct = SubsonicSettings.Config(serverUrl!!, user, password)
        relay = Relay(Uri.parse(serverUrl).port)
        SubsonicSettings.save(app, SubsonicSettings.Config("http://127.0.0.1:${relay.port}", user, password))
        SubsonicClient.invalidateCache()
        val format = MediaFormat().apply { setString(MediaFormat.KEY_MIME, "audio/opus") }
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName("fake.opus").setIsEncoder(false)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(format).setIsEncoder(false).build())
                .build())
        androidx.media3.exoplayer.mediacodec.MediaCodecUtil.clearDecoderInfoCache()
        ShadowMediaCodec.addDecoder("fake.opus", ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024) { input, output ->
            if (input.hasRemaining()) {
                val bytes = ByteArray(input.remaining()).also { input.get(it) }
                heard += CRC32().apply { update(bytes) }.value
                output.put(ByteArray(3840))
            }
        })
    }

    @After
    fun tearDown() { if (::relay.isInitialized) relay.close() }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun MediaService.player(): ExoPlayer =
        MediaService::class.java.getDeclaredField("player").apply { isAccessible = true }.get(this) as ExoPlayer

    private fun album(name: String) =
        SubsonicClient.getAlbum(direct, SubsonicClient.getAllAlbums(direct).first { it.name == name }.id).songs

    /** [songs] as the app's saved queue (an id with no song plays as a song the server doesn't have), then started by the launcher. */
    private fun play(songs: List<SubsonicSong>): MediaService {
        val store = ResumeStore(app)
        val items = songs.map { ResumeStore.SavedItem("sub:${it.id}", it.title, it.artist.orEmpty(), it.album.orEmpty(), null, it.albumId, it.artistId, it.durationMs) }
        store.saveQueue(MediaService.ONLINE_ROOT, ResumeStore.QueueKind.BROWSE, MediaService.albumNodeId(songs.first().albumId ?: "x"))
        store.saveItem(items.first(), 0L)
        store.saveSnapshot(items)
        val s = Robolectric.buildService(MediaService::class.java).create().get()
        looper.idle()
        s.onStartCommand(Intent().setComponent(ComponentName(app, MediaService::class.java))
            .putExtra(CommandParcelable.COMMAND_KEY,
                CommandParcelable(CommandParcelable.SWITCH_SOURCE_ONLY_PLAY, "com.saicmotor.launcher", 0, -1)), 0, 1)
        return s
    }

    private fun drive(s: MediaService, ms: Long, until: () -> Boolean = { false }): List<Pair<Long, String?>> {
        val start = SystemClock.elapsedRealtime()
        val songs = ArrayList<Pair<Long, String?>>()
        while (SystemClock.elapsedRealtime() - start < ms && !until()) {
            looper.idleFor(Duration.ofMillis(20))
            Thread.sleep(2)
            val id = s.player().currentMediaItem?.mediaId
            if (songs.lastOrNull()?.second != id) songs += (SystemClock.elapsedRealtime() - start) to id
        }
        return songs
    }

    /** The song as the server sends the car (Opus), straight from the server. */
    private fun fromServer(song: SubsonicSong): ByteArray {
        val conn = URL(SubsonicClient.streamUrl(direct, song.id)).openConnection() as HttpURLConnection
        return try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }
    }

    private fun packets(ogg: ByteArray): List<Long> {
        val out = ArrayList<Long>()
        var packet = ByteArrayOutputStream()
        var i = 0
        while (i + 27 <= ogg.size) {
            val segments = ogg[i + 26].toInt() and 0xFF
            var at = i + 27 + segments
            for (k in 0 until segments) {
                val length = ogg[i + 27 + k].toInt() and 0xFF
                packet.write(ogg, at, length)
                at += length
                if (length < 255) {
                    out += CRC32().apply { update(packet.toByteArray()) }.value
                    packet = ByteArrayOutputStream()
                }
            }
            i = at
        }
        return out.drop(2)   // OpusHead, OpusTags
    }

    private val expected = HashMap<String, List<Long>>()
    private fun expectedPackets(song: SubsonicSong) = expected.getOrPut(song.id) { packets(fromServer(song)) }

    /**
     * Plays until the app has fetched the last song, and a while longer.  (The
     * test asks the server for the songs only afterwards: asking first would
     * put them in Navidrome's transcoding cache.)
     */
    private fun playThrough(s: MediaService, songs: List<SubsonicSong>): List<Pair<Long, String?>> {
        val last = songs.last().id
        val order = drive(s, 120_000) { relay.streams(last).any { it.replyBytes.get() > 0 } }.toMutableList()
        val playable = songs.filter { relay.streams(it.id).none { e -> e.replyBytes.get() in 1..2_000 } }
        val all = playable.flatMap { expectedPackets(it) }.toHashSet()
        val offset = order.lastOrNull()?.first ?: 0
        order += drive(s, 60_000) { heard.toHashSet().containsAll(all) }.map { (t, id) -> (t + offset) to id }
        return order
    }

    private fun assertHeardWhole(song: SubsonicSong) {
        val mine = expectedPackets(song)
        val set = mine.toHashSet()
        val got = heard.filter { it in set }
        assertEquals("${song.title}: heard once, start to end (${got.size} of ${mine.size} packets)", mine, got)
    }

    /** Zeroes what differs between two transcodes of a song (the Ogg serial number and page checksums). */
    private fun normalised(ogg: ByteArray): ByteArray {
        val b = ogg.copyOf()
        var i = 0
        while (i + 27 <= b.size) {
            val segments = b[i + 26].toInt() and 0xFF
            var length = 27 + segments
            for (k in 0 until segments) length += b[i + 27 + k].toInt() and 0xFF
            for (k in 14..17) b[i + k] = 0
            for (k in 22..25) b[i + k] = 0
            i += length
        }
        return b
    }

    private fun kept(song: SubsonicSong): ByteArray {
        val mediaId = "sub:${song.id}"
        val length = MediaCache.cachedBytesFromStart(app, mediaId)
        if (length == 0L) return ByteArray(0)
        val source = MediaCache.dataSourceFactory(app).createDataSource()
        source.open(DataSpec.Builder().setUri(Uri.parse("http://127.0.0.1:9/")).setKey(MediaCache.keyFor(mediaId)).setLength(length).build())
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        source.close()
        return out.toByteArray()
    }

    private fun assertKeptWhole(song: SubsonicSong) {
        val server = fromServer(song)
        val kept = kept(song)
        assertEquals("${song.title}: kept whole", server.size, kept.size)
        assertArrayEquals("${song.title}: kept as the server sends it", normalised(server), normalised(kept))
        assertEquals(1.0f, MediaCache.cachedFraction(app, "sub:${song.id}"))
    }

    /**
     * What's kept of [song] is the song as the server sends it, as far as it
     * goes from the start (bar the serial number and checksums, which differ
     * between transcodes; where they are is taken from the server's copy, as
     * what's kept may stop part-way through a page).
     */
    private fun assertKeptIsTheSong(song: SubsonicSong) {
        val server = fromServer(song)
        val kept = kept(song)
        System.getenv("NAVIDROME_DUMP")?.let { dir ->
            java.io.File(dir, "${song.id}.server.opus").writeBytes(server)
            java.io.File(dir, "${song.id}.kept.opus").writeBytes(kept)
        }
        assertTrue("${song.title}: ${kept.size} bytes kept of ${server.size}", kept.size <= server.size)
        val skip = HashSet<Int>()
        var i = 0
        while (i + 27 <= server.size) {
            for (k in 14..17) skip += i + k
            for (k in 22..25) skip += i + k
            val segments = server[i + 26].toInt() and 0xFF
            i += 27 + segments + (0 until segments).sumOf { server[i + 27 + it].toInt() and 0xFF }
        }
        for (k in kept.indices) if (k !in skip && kept[k] != server[k]) {
            throw AssertionError("${song.title}: what's kept differs from the server's at byte $k of ${kept.size}")
        }
    }

    private fun report(title: String) {
        println("== $title")
        for (e in relay.exchanges) if (e.endpoint == "stream") {
            println("  stream ${e.param("id")} range=${e.range} ${e.status} reply=${e.replyBytes} broken=${e.broken}")
        }
    }

    // ── tests ────────────────────────────────────────────────────────────

    /** Everything the app reads from the server parses: the lists the car's menus and the screens show. */
    @Test
    fun theLibraryReadsFromTheRealServer() {
        val info = SubsonicClient.ping(direct)
        assertEquals("navidrome", info.type)
        val artists = SubsonicClient.getArtists(direct)
        assertTrue(artists.map { it.name }.containsAll(listOf("Mira Solano", "Northbound Static", "The Paper Lanterns")))
        val albums = SubsonicClient.getAllAlbums(direct)
        assertEquals(3, albums.size)
        assertTrue(albums.all { it.coverArt != null && it.songCount > 0 && it.year > 0 && it.artistId != null })
        for (type in listOf("newest", "recent", "frequent", "random", "starred")) SubsonicClient.getAlbumList(direct, type, 100)
        val genres = SubsonicClient.getGenres(direct)
        assertEquals(setOf("Electronic", "Indie", "Pop"), genres.map { it.name }.toSet())
        assertEquals(1, SubsonicClient.getAllAlbums(direct, "byGenre", "Pop").size)
        val artist = SubsonicClient.getArtist(direct, artists.first { it.name == "Mira Solano" }.id)
        assertEquals(listOf("Glass Orchard"), artist.albums.map { it.name })
        val songs = album("Harbour Lights")
        assertEquals((1..5).toList(), songs.map { it.track })
        assertTrue(songs.all { it.durationMs >= 40_000 && it.coverArt != null && it.artistId != null && it.albumId != null })
        assertEquals(songs[0].title, SubsonicClient.getSong(direct, songs[0].id).title)
        assertEquals(13, SubsonicClient.getRandomSongs(direct, 50).size)
        assertEquals(4, SubsonicClient.getRandomSongs(direct, 50, "Pop").size)
        val found = SubsonicClient.search(direct, "Glass")
        assertTrue(found.albums.any { it.name == "Glass Orchard" } && found.songs.any { it.title == "Glass Orchard" })
        SubsonicClient.scrobble(direct, songs[0].id, submission = false, timeMs = System.currentTimeMillis())
        // A cover comes back as an image.
        val cover = URL(SubsonicClient.coverArtUrl(direct, albums[0].coverArt!!)).openConnection() as HttpURLConnection
        assertEquals(200, cover.responseCode)
        assertTrue(cover.contentType.startsWith("image/"))
        cover.disconnect()
    }

    /** Songs stream from the real server (transcoded to Opus), play right through, and are kept. */
    @Test
    fun anAlbumPlaysThroughAndIsKept() {
        val songs = album("Harbour Lights").take(2)
        val s = play(songs)
        playThrough(s, songs)
        report("album")
        songs.forEach { assertHeardWhole(it) }
        assertKeptWhole(songs[0])
        // The next song may be the look-ahead's and the player's at once
        // here (the test's clock runs fast): a piece of it can go unkept.
        assertKeptIsTheSong(songs[1])
        s.onDestroy()
    }

    /**
     * The next song comes up while the look-ahead is still downloading it (a
     * skip, or a slow signal): the player and the look-ahead both fetch it.
     * What's kept of it must still be the song.
     */
    @Test
    fun aSongStillDownloadingWhenItComesUpIsKeptRight() {
        val songs = album("Glass Orchard").drop(2).take(2)
        // The look-ahead's download of the next song goes slowly.
        relay.pace = { e, attempt -> if (e.endpoint == "stream" && e.param("id") == songs[1].id && attempt == 1) 60_000 else null }
        val s = play(songs)
        drive(s, 60_000) { relay.streams(songs[1].id).isNotEmpty() }
        drive(s, 1_000)
        s.player().seekToNextMediaItem()   // skipped to while it's downloading
        playThrough(s, songs)
        report("skipped to a song still downloading")
        assertHeardWhole(songs[1])
        assertKeptIsTheSong(songs[1])
        s.onDestroy()
    }

    /** The connection breaks part-way through a song: it carries on where it was, and is kept whole. */
    @Test
    fun aBrokenConnectionCarriesOnWithTheRealServer() {
        val songs = album("Harbour Lights").drop(2).take(2)
        relay.breakAfter = { e, attempt -> if (e.endpoint == "stream" && e.param("id") == songs[0].id && attempt == 1) 150_000L else null }
        val s = play(songs)
        playThrough(s, songs)
        report("broken connection")
        assertTrue("the connection was broken", relay.streams(songs[0].id).any { it.broken })
        songs.forEach { assertHeardWhole(it) }
        assertKeptWhole(songs[0])
        s.onDestroy()
    }

    /** A song the server doesn't have (an id from before the library changed) is skipped straight away. */
    @Test
    fun aSongTheServerDoesntHaveIsSkipped() {
        val real = album("Glass Orchard")
        val gone = real[0].copy(id = "gone0000000000000000000", title = "Gone")
        val songs = listOf(real[0], gone, real[1])
        val s = play(songs)
        val order = playThrough(s, songs)
        report("missing song")
        assertHeardWhole(real[0])
        assertHeardWhole(real[1])
        val reachedGone = order.firstOrNull { it.second == "sub:${gone.id}" }?.first
        val reachedNext = order.first { it.second == "sub:${real[1].id}" }.first
        assertTrue("skipped in ${reachedNext - (reachedGone ?: reachedNext)} ms", reachedGone == null || reachedNext - reachedGone < 3_000)
        assertEquals(0L, MediaCache.cachedBytesFromStart(app, "sub:${gone.id}"))
        s.onDestroy()
    }

    /** The password was changed on the server: the car says so, stays on the song, and plays once Settings are fixed. */
    @Test
    fun aRefusedLoginIsShownAndPlaysOnceFixed() {
        val songs = album("Low Tide Radio").take(2)
        SubsonicSettings.save(app, SubsonicSettings.Config("http://127.0.0.1:${relay.port}", user, "an old password"))
        SubsonicClient.invalidateCache()
        val s = play(songs)
        val order = drive(s, 20_000)
        report("refused login")
        assertEquals("stays on the song", listOf("sub:${songs[0].id}"), order.mapNotNull { it.second }.distinct())
        assertTrue("nothing plays", heard.isEmpty())
        val session = MediaService::class.java.getDeclaredField("session").apply { isAccessible = true }
            .get(s) as android.support.v4.media.session.MediaSessionCompat
        assertEquals(app.getString(com.saicmotor.media.R.string.error_auth_title),
            session.controller.playbackState.errorMessage?.toString())
        SubsonicSettings.save(app, SubsonicSettings.Config("http://127.0.0.1:${relay.port}", user, password))
        SubsonicClient.invalidateCache()
        drive(s, 40_000) { relay.streams(songs[0].id).any { it.replyBytes.get() > 10_000 } }
        drive(s, 60_000) { heard.toHashSet().containsAll(expectedPackets(songs[0])) }
        assertHeardWhole(songs[0])
        s.onDestroy()
    }

    /** A song already in Navidrome's transcoding cache comes with its length: it plays and is kept the same. */
    @Test
    fun aSongFromNavidromesOwnCachePlaysAndIsKept() {
        val songs = listOf(album("Harbour Lights")[4], album("Glass Orchard")[2])
        expectedPackets(songs[0])   // asking for it once puts it in Navidrome's cache
        val s = play(songs)
        playThrough(s, songs)
        report("from Navidrome's cache")
        assertTrue("came with its length", relay.streams(songs[0].id).isNotEmpty())
        songs.forEach { assertHeardWhole(it) }
        assertKeptWhole(songs[0])
        s.onDestroy()
    }
}
