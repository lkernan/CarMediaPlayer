package shots

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.media.MediaFormat
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saicmotor.common.CommandParcelable
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.service.MediaCache
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.service.ResumeStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Songs really playing, from a made-up Navidrome (see [FakeNavidrome]) that
 * misbehaves the way a car's connection does.  A stand-in Opus decoder notes
 * every packet it's given, so the tests can tell exactly what was heard.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi", shadows = [InstantAudioTrack::class])
class StreamingTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private lateinit var server: FakeNavidrome
    /** (song number, packet number) of everything the decoder was given, in order. */
    private val heard = CopyOnWriteArrayList<Pair<Int, Int>>()

    @Before
    fun setUp() {
        freshCache()
        server = FakeNavidrome()
        SubsonicSettings.save(app, SubsonicSettings.Config(server.url, "me", "secret"))
        val format = MediaFormat().apply { setString(MediaFormat.KEY_MIME, "audio/opus") }
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName("fake.opus").setIsEncoder(false)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(format).setIsEncoder(false).build())
                .build())
        // Media3 remembers the decoders it found: earlier tests looked before this one was there.
        androidx.media3.exoplayer.mediacodec.MediaCodecUtil.clearDecoderInfoCache()
        ShadowMediaCodec.addDecoder("fake.opus", ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024) { input, output ->
            OggOpus.label(input)?.let { heard += it }
            input.position(input.limit())
            output.put(ByteArray(3840))   // 20 ms of 48 kHz stereo
        })
    }

    @After
    fun tearDown() = server.close()

    // ── helpers ──────────────────────────────────────────────────────────

    private fun MediaService.player(): ExoPlayer =
        MediaService::class.java.getDeclaredField("player").apply { isAccessible = true }.get(this) as ExoPlayer

    /** A new song cache, as a new process opens it (the app keeps one for as long as it runs). */
    private fun freshCache() {
        val field = MediaCache::class.java.getDeclaredField("cache").apply { isAccessible = true }
        (field.get(null) as? androidx.media3.datasource.cache.SimpleCache)?.release()
        field.set(null, null)
    }

    /** The service, started by the launcher as the car comes on, playing the queue last saved. */
    private fun startService(): MediaService {
        val s = Robolectric.buildService(MediaService::class.java).create().get()
        looper.idle()
        s.onStartCommand(Intent().setComponent(ComponentName(app, MediaService::class.java))
            .putExtra(CommandParcelable.COMMAND_KEY,
                CommandParcelable(CommandParcelable.SWITCH_SOURCE_ONLY_PLAY, "com.saicmotor.launcher", 0, -1)), 0, 1)
        return s
    }

    /** The server's songs as an album queue, saved as the app saves it, then played from the first. */
    private fun startPlaying(): MediaService {
        val store = ResumeStore(app)
        val items = server.songs.values.map {
            ResumeStore.SavedItem("sub:${it.id}", it.title, "Artist", "Album", null, "al1", "ar1", it.seconds * 1_000L)
        }
        store.saveQueue(MediaService.ONLINE_ROOT, ResumeStore.QueueKind.BROWSE, MediaService.albumNodeId("al1"))
        store.saveItem(items.first(), 0L)
        store.saveSnapshot(items)
        return startService()
    }

    private fun packets(song: Int) = server.songs.values.elementAt(song).seconds * 1000 / OggOpus.PACKET_MS

    /** Song [song] played right to its end. */
    private fun heardAll(song: Int) = heard.any { it.first == song && it.second == packets(song) - 1 }

    /**
     * Lets the car run for up to [ms] on its clock (stopping early once [until]),
     * noting which song was playing when; [during] gets the time so far.
     */
    private fun drive(s: MediaService, ms: Long, until: () -> Boolean = { false },
                      during: (Long) -> Unit = {}): List<Pair<Long, String?>> {
        val start = SystemClock.elapsedRealtime()
        val songs = ArrayList<Pair<Long, String?>>()
        while (SystemClock.elapsedRealtime() - start < ms && !until()) {
            looper.idleFor(Duration.ofMillis(20))
            Thread.sleep(2)
            val t = SystemClock.elapsedRealtime() - start
            during(t)
            val id = s.player().currentMediaItem?.mediaId
            if (songs.lastOrNull()?.second != id) songs += t to id
        }
        return songs
    }

    /** What the decoder got of [song], in order: every packet once, from the first, is the whole song. */
    private fun assertHeardWhole(song: Int) =
        assertEquals("song $song heard once, start to end, nothing missed or repeated",
            (0 until packets(song)).toList(), heard.filter { it.first == song }.map { it.second })

    private fun streamRequests(song: Int) = server.streamRequests(server.songs.keys.elementAt(song))

    /** The song as it is in the song cache. */
    private fun kept(song: Int): ByteArray {
        val mediaId = "sub:" + server.songs.keys.elementAt(song)
        val length = MediaCache.cachedBytesFromStart(app, mediaId)
        if (length == 0L) return ByteArray(0)
        val source = MediaCache.dataSourceFactory(app).createDataSource()
        source.open(DataSpec.Builder().setUri(Uri.parse("http://127.0.0.1:9/")).setKey(MediaCache.keyFor(mediaId))
            .setLength(length).build())
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        source.close()
        return out.toByteArray()
    }

    /** The whole song is in the cache, the same as the server's (but for the serial number each transcode gets). */
    private fun assertKeptWhole(song: Int) {
        val original = server.songs.values.elementAt(song).opus
        assertEquals("song $song kept whole", original.size, kept(song).size)
        assertArrayEquals("song $song kept as sent", OggOpus.withSerial(original, 0), OggOpus.withSerial(kept(song), 0))
        assertEquals(1.0f, MediaCache.cachedFraction(app, "sub:" + server.songs.keys.elementAt(song)))
    }

    /**
     * What's in the song cache for [song] is the song (as far as it goes): a
     * piece the player and the look-ahead both wanted at once may be missing,
     * but nothing in it is anything else.
     */
    private fun assertKeptIsTheSong(song: Int) {
        val original = server.songs.values.elementAt(song).opus
        val kept = kept(song)
        assertTrue("song $song: ${kept.size} bytes kept of ${original.size}", kept.size <= original.size)
        val serialAndChecksum = HashSet<Int>()
        var i = 0
        while (i + 27 <= original.size) {
            for (k in 14..17) serialAndChecksum += i + k
            for (k in 22..25) serialAndChecksum += i + k
            val segments = original[i + 26].toInt() and 0xFF
            i += 27 + segments + (0 until segments).sumOf { original[i + 27 + it].toInt() and 0xFF }
        }
        for (k in kept.indices) if (k !in serialAndChecksum && kept[k] != original[k]) {
            throw AssertionError("song $song: what's kept differs from the song at byte $k")
        }
    }

    // ── tests ────────────────────────────────────────────────────────────

    /**
     * Every song is kept as it plays, whether the look-ahead downloaded it or
     * the player streamed it, and none is downloaded twice (bar a piece where
     * the two overlap).  (The player's
     * songs weren't kept — ExoPlayer doesn't keep a stream of unknown length
     * — so the look-ahead then downloaded each next song a second time.)
     */
    @Test
    fun everySongIsKeptAndNoneDownloadedTwice() {
        server.addSongs(3, seconds = 10)
        val s = startPlaying()
        drive(s, 60_000, until = { heardAll(2) })
        assertKeptWhole(0)
        for (song in 0..2) {
            assertHeardWhole(song)
            assertKeptIsTheSong(song)
            val size = server.songs.values.elementAt(song).opus.size
            val sent = streamRequests(song).sumOf { it.sent.get() }
            assertTrue("song $song: $sent bytes sent for $size", sent < size * 3 / 2)
        }
        s.onDestroy()
    }

    /**
     * The connection drops part-way through a song still streaming (Wi-Fi to
     * mobile data leaving home): it carries on from where it was, not from
     * the start, and the song is still kept whole.
     */
    @Test
    fun aDroppedConnectionCarriesOnWhereItWas() {
        server.addSongs(2, seconds = 10)
        server.plan = { song, attempt -> if (song == 0 && attempt == 1) FakeNavidrome.Reply.CutAfter(100_000) else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        drive(s, 60_000, until = { heardAll(1) })
        assertHeardWhole(0)
        assertHeardWhole(1)
        val again = streamRequests(0)[1]
        assertTrue("asked to carry on from part-way: ${again.range}", again.range!!.removePrefix("bytes=").substringBefore('-').toInt() > 50_000)
        assertKeptWhole(0)
        s.onDestroy()
    }

    /** No server for a while mid-song (a dead spot): the song waits, then carries on. */
    @Test
    fun aDeadSpotIsWaitedOut() {
        server.addSongs(2, seconds = 10)
        server.plan = { song, attempt -> if (song == 0 && attempt == 1) FakeNavidrome.Reply.StallAfter(60_000, 3_000) else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        drive(s, 60_000, until = { heardAll(1) }, during = { t ->
            if (t in 1_000 until 1_020) server.goDown()
            if (t in 11_000 until 11_020) server.comeBack()
        })
        assertHeardWhole(0)
        assertHeardWhole(1)
        assertKeptWhole(0)
        s.onDestroy()
    }

    /**
     * Navidrome sends an error message instead of the song (as Subsonic
     * errors come: HTTP 200, JSON).  It's never kept as the song: the song
     * plays once the server sends it.  (It used to be kept, and the song then
     * failed every time, after half a minute of silence.)
     */
    @Test
    fun anErrorMessageIsNeverKeptAsTheSong() {
        server.addSongs(3, seconds = 10)
        server.plan = { song, attempt -> if (song == 1 && attempt == 1) FakeNavidrome.Reply.SubsonicError(0, "Error transcoding") else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        drive(s, 60_000, until = { heardAll(2) })
        assertHeardWhole(1)
        assertKeptIsTheSong(1)
        s.onDestroy()
    }

    /** A song the server doesn't have (a queue saved before the library changed) is skipped straight away. */
    @Test
    fun aSongTheServerDoesntHaveIsSkippedStraightAway() {
        server.addSongs(3, seconds = 10)
        server.plan = { song, _ -> if (song == 1) FakeNavidrome.Reply.SubsonicError(70, "Song not found") else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        val songs = drive(s, 60_000, until = { heardAll(2) })
        val ids = server.songs.keys.map { "sub:$it" }
        // Song 0 ends at 10 s; song 2 follows it straight away, not after
        // half a minute of trying song 1.
        val reached2 = songs.first { it.second == ids[2] }.first
        assertTrue("song 2 at $reached2 ms", reached2 < 13_000)
        assertHeardWhole(0)
        assertHeardWhole(2)
        assertTrue("not asked for again and again: ${streamRequests(1).size}", streamRequests(1).size <= 2)
        assertEquals("nothing kept for it", 0L, MediaCache.cachedBytesFromStart(app, ids[1]))
        s.onDestroy()
    }

    /**
     * The same, when the error comes for a later song (loaded ahead) while
     * one plays: that one plays on, and the missing song leaves the queue.
     */
    @Test
    fun aLaterSongTheServerDoesntHaveLeavesTheQueue() {
        server.addSongs(3, seconds = 10)
        val s = startPlaying()
        drive(s, 1_000)
        val ids = server.songs.keys.map { "sub:$it" }
        val error = androidx.media3.exoplayer.ExoPlaybackException.createForSource(
            com.saicmotor.media.service.SongRefused(server.songs.keys.elementAt(2), 70, "error 70: Song not found"),
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        MediaService::class.java.getDeclaredMethod("recoverFromError", androidx.media3.common.PlaybackException::class.java)
            .apply { isAccessible = true }.invoke(s, error)
        val p = s.player()
        assertEquals(listOf(ids[0], ids[1]), (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId })
        assertEquals("song 0 plays on", ids[0], p.currentMediaItem?.mediaId)
        s.onDestroy()
    }

    /**
     * The server refuses the login (the password was changed on it): the car
     * says so and stays on the song — not skipping through the queue — and
     * plays it once the new password is put in Settings.
     */
    @Test
    fun aRefusedLoginIsShownAndPlaysOnceFixed() {
        server.addSongs(2, seconds = 10)
        server.password = "changed"
        val s = startPlaying()
        val first = "sub:" + server.songs.keys.first()
        val songs = drive(s, 20_000)
        assertEquals("stays on the song", listOf(first), songs.mapNotNull { it.second }.distinct())
        assertTrue("nothing plays", heard.isEmpty())
        val session = MediaService::class.java.getDeclaredField("session").apply { isAccessible = true }
            .get(s) as android.support.v4.media.session.MediaSessionCompat
        assertEquals(app.getString(com.saicmotor.media.R.string.error_auth_title),
            session.controller.playbackState.errorMessage?.toString())
        // Fixed in Settings (as the settings screen saves it).
        SubsonicSettings.save(app, SubsonicSettings.Config(server.url, "me", "changed"))
        com.saicmotor.media.subsonic.SubsonicClient.invalidateCache()
        drive(s, 30_000, until = { heardAll(0) })
        assertHeardWhole(0)
        s.onDestroy()
    }

    /** Navidrome at its limit of transcodes ("429, try again in 5 s"): the song waits, then plays; nothing's skipped. */
    @Test
    fun aBusyServerIsWaitedFor() {
        server.addSongs(2, seconds = 10)
        server.plan = { song, attempt -> if (song == 0 && attempt <= 2) FakeNavidrome.Reply.Busy else FakeNavidrome.Reply.Whole }
        val s = startPlaying()
        drive(s, 60_000, until = { heardAll(1) })
        assertHeardWhole(0)
        assertHeardWhole(1)
        assertEquals(3, streamRequests(0).size)
        s.onDestroy()
    }

    /** No server when the car starts: the song starts as soon as it's back. */
    @Test
    fun startsAsSoonAsTheServerIsBack() {
        server.addSongs(1, seconds = 10)
        server.goDown()
        val s = startPlaying()
        val start = SystemClock.elapsedRealtime()
        drive(s, 30_000, until = { heard.isNotEmpty() }, during = { t -> if (t in 8_000 until 8_020) server.comeBack() })
        val startedAt = SystemClock.elapsedRealtime() - start
        assertTrue("started ${startedAt - 8_000} ms after the server was back", startedAt in 8_000..11_000)
        drive(s, 30_000, until = { heardAll(0) })
        assertHeardWhole(0)
        s.onDestroy()
    }

    /**
     * The car's switched off part-way through a song that streamed (not
     * downloaded ahead), and starts again with no signal: the song carries on
     * from the song cache.
     */
    @Test
    fun theSongPlayingIsKeptForWhenTheCarStartsAgain() {
        server.addSongs(2, seconds = 30)
        val first = startPlaying()
        drive(first, 3_000)
        first.player().pause()
        drive(first, 500)
        val at = ResumeStore(app).load()!!.positionMs   // where the app noted it was
        assertTrue("part-way through song 0 ($at ms)", at > 1_000)
        first.onDestroy()
        freshCache()
        server.goDown()
        heard.clear()
        val s = startService()
        drive(s, 20_000, until = { heardAll(0) })
        val song0 = heard.filter { it.first == 0 }.map { it.second }
        assertTrue("played without the server", song0.isNotEmpty())
        assertTrue("from where it was (${at} ms), not the start: packet ${song0.first()}", song0.first() * OggOpus.PACKET_MS >= at - 1_000)
        assertEquals("to the end", packets(0) - 1, song0.last())
        s.onDestroy()
    }
}
