package shots

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saicmotor.common.CommandParcelable
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.service.ResumeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * The real MediaService: the SAIC launcher's start-up commands, restoring the
 * saved queue without the network, steering-wheel skip / hold, previous, and
 * ignoring "play" while another app is playing.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class ServiceTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val looper get() = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() {
        // Port 9 on this machine: refuses straight away, like no network.
        SubsonicSettings.save(app, SubsonicSettings.Config("http://127.0.0.1:9", "me", "secret"))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun service(): MediaService {
        val s = Robolectric.buildService(MediaService::class.java).create().get()
        looper.idle()
        return s
    }

    private fun MediaService.player(): ExoPlayer =
        MediaService::class.java.getDeclaredField("player").apply { isAccessible = true }.get(this) as ExoPlayer

    private fun MediaService.call(name: String, vararg args: Any?): Any? {
        val m = MediaService::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        return m.invoke(this, *args)
    }

    private fun idle(ms: Long) = looper.idleFor(Duration.ofMillis(ms))

    /** Runs the main thread until [done] (the restore reads its file on a background thread). */
    private fun waitFor(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < until) { looper.idle(); Thread.sleep(5) }
        looper.idle()
    }

    private fun MediaService.restoredByLauncher(event: Int = CommandParcelable.SWITCH_SOURCE_ONLY_PLAY) {
        onStartCommand(launcherCommand(event), 0, 1)
        waitFor { player().mediaItemCount > 0 }
    }

    private fun item(i: Int) = ResumeStore.SavedItem("sub:s$i", "Song $i", "Artist", "Album", null, "al1", "ar1", 200_000L)

    /** What the app saves while playing: song 3 of 8, 42 s in. */
    private fun savePlayingState() {
        val store = ResumeStore(app)
        store.saveQueue(MediaService.ONLINE_ROOT, ResumeStore.QueueKind.BROWSE, MediaService.albumNodeId("al1"))
        store.saveItem(item(3), 42_000L)
        store.saveSnapshot((0 until 8).map(::item))
    }

    private fun launcherCommand(event: Int) = Intent()
        .setComponent(ComponentName(app, MediaService::class.java))
        .putExtra(CommandParcelable.COMMAND_KEY, CommandParcelable(event, "com.saicmotor.launcher", 0, -1))

    private fun key(code: Int, action: Int, downTime: Long, eventTime: Long) =
        KeyEvent(downTime, eventTime, action, code, 0)

    // ── tests ────────────────────────────────────────────────────────────

    @Test
    fun settingsMoveToDeviceStorageOnce() {
        // As an older version left them: normal (credential-protected) storage.
        app.getSharedPreferences("subsonic_prefs", 0).edit()
            .putString("url", "https://old.example.com").putString("username", "me").putString("password", "pw").commit()
        app.getSharedPreferences("playback_settings", 0).edit().putString("startup_mode", "SHUFFLE_LIBRARY").commit()
        java.io.File(app.filesDir, "queue_snapshot.json").writeText("[]")
        com.saicmotor.media.data.Storage.context(app).getSharedPreferences("storage", 0).edit().clear().commit()
        com.saicmotor.media.data.Storage.migrate(app)
        assertEquals("https://old.example.com", SubsonicSettings.load(app).url)
        assertEquals(com.saicmotor.media.data.PlaybackSettings.StartupMode.SHUFFLE_LIBRARY,
            com.saicmotor.media.data.PlaybackSettings.startupMode(app))
        assertTrue(java.io.File(com.saicmotor.media.data.Storage.context(app).filesDir, "queue_snapshot.json").exists())
        assertFalse(java.io.File(app.filesDir, "queue_snapshot.json").exists())
        // Only once: later changes stay put.
        SubsonicSettings.save(app, SubsonicSettings.Config("https://new.example.com", "me", "pw"))
        com.saicmotor.media.data.Storage.migrate(app)
        assertEquals("https://new.example.com", SubsonicSettings.load(app).url)
        println("device storage: ${com.saicmotor.media.data.Storage.context(app).filesDir}")
    }

    /**
     * Navidrome picks the transcoding (Opus 128k for the car) by "player" =
     * client name + User-Agent.  Song downloads must look like the same player
     * as the app's other requests, or Navidrome sends the original FLAC.
     */
    @Test
    fun songsAreFetchedAsTheSamePlayerAsTheLibrary() {
        val server = java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val agents = java.util.Collections.synchronizedList(mutableListOf<String>())
        val serving = Thread {
            repeat(2) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    var line = reader.readLine()
                    val isStream = line.contains("/rest/stream")
                    while (!line.isNullOrEmpty()) {
                        if (line.startsWith("User-Agent:", ignoreCase = true)) agents += line.substringAfter(':').trim()
                        line = reader.readLine()
                    }
                    val body = if (isStream) "0123456789".toByteArray()
                               else """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome"}}""".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            }
        }.apply { start() }
        val cfg = SubsonicSettings.Config("http://127.0.0.1:${server.localPort}", "me", "secret")
        com.saicmotor.media.subsonic.SubsonicClient.ping(cfg)
        val uri = android.net.Uri.parse(com.saicmotor.media.subsonic.SubsonicClient.streamUrl(cfg, "x"))
        com.saicmotor.media.service.MediaCache.precache(app, "sub:x", uri) { }
        serving.join(5_000); server.close()
        println("User-Agents: $agents")
        assertEquals(2, agents.size)
        assertEquals("API and song requests must match", agents[0], agents[1])
        assertTrue(com.saicmotor.media.service.MediaCache.isCached(app, "sub:x"))
    }

    /** Paused, then the volume changed: the launcher's "play" that comes with it is ignored. */
    @Test
    fun volumeChangeDoesNotUnpause() {
        val s = service()
        fun hardKey(code: Int) {
            app.sendBroadcast(Intent("com.saic.keyevent.hardkey.report").putExtra("android.intent.extra.hardkey.keycode", code))
            looper.idle()
        }
        assertFalse(s.call("isVolumeChangePlay") as Boolean)
        hardKey(297)                       // steering-wheel volume up
        assertTrue(s.call("isVolumeChangePlay") as Boolean)
        idle(1_200)
        assertFalse("a play long after is a real play", s.call("isVolumeChangePlay") as Boolean)
        hardKey(298); hardKey(301)         // volume, then the play/pause key
        assertFalse("play/pause pressed: play", s.call("isVolumeChangePlay") as Boolean)
    }

    /** The launcher's tile must use the cover bitmap, so the art URL isn't published as ALBUM_ART_URI. */
    @Test
    fun launcherGetsTheCoverBitmapNotAUrl() {
        savePlayingState()
        val s = service()
        waitFor { s.player().mediaItemCount > 0 }
        val meta = s.call("buildMetadata", s.player().currentMediaItem, null) as android.support.v4.media.MediaMetadataCompat
        assertEquals(null, meta.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI))
        assertEquals("Song 3", meta.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE))
    }

    /** Plays that couldn't be reported (no signal) are kept, and sent once the server answers. */
    @Test
    fun scrobblesWaitForTheSignal() {
        val pending = com.saicmotor.media.service.PendingScrobbles(app)
        pending.add(com.saicmotor.media.service.PendingScrobbles.Play("a", 1L))
        pending.add(com.saicmotor.media.service.PendingScrobbles.Play("b", 2L))
        pending.add(com.saicmotor.media.service.PendingScrobbles.Play("a", 1L))   // same play twice: kept once
        assertEquals(listOf("a", "b"), com.saicmotor.media.service.PendingScrobbles(app).all().map { it.songId })
        val server = java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val serving = Thread {
            repeat(2) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val first = reader.readLine()
                    Regex("[?&]id=([^&]+).*[?&]time=(\\d+)|[?&]time=(\\d+).*[?&]id=([^&]+)").find(first)?.let { seen += first }
                    var line = first
                    while (!line.isNullOrEmpty()) line = reader.readLine()
                    val body = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                    }
                }
            }
        }.apply { start() }
        SubsonicSettings.save(app, SubsonicSettings.Config("http://127.0.0.1:${server.localPort}", "me", "secret"))
        val s = service()
        s.call("sendPendingScrobbles")
        serving.join(5_000); server.close()
        assertEquals(2, seen.size)
        assertTrue(seen[0].contains("id=a") && seen[0].contains("time=1") && seen[0].contains("submission=true"))
        assertTrue(com.saicmotor.media.service.PendingScrobbles(app).all().isEmpty())
    }

    @Test
    fun commandParcelMatchesTheFirmware() {
        // Written exactly as com.saicmotor.common.CommandParcelable in the launcher writes it.
        val p = Parcel.obtain()
        p.writeInt(32773); p.writeString("com.saicmotor.launcher"); p.writeInt(0); p.writeInt(-1); p.writeBundle(Bundle())
        p.setDataPosition(0)
        val c = CommandParcelable.CREATOR.createFromParcel(p)
        assertEquals(32773, c.event)
        assertEquals("com.saicmotor.launcher", c.targetPackage)
        assertEquals(0, c.targetType)
        assertEquals(-1, c.targetState)
        // And ours reads back the same way.
        val q = Parcel.obtain()
        CommandParcelable(16417, "com.saicmotor.media", 0, 1).writeToParcel(q, 0)
        q.setDataPosition(0)
        assertEquals(16417, q.readInt()); assertEquals("com.saicmotor.media", q.readString())
        assertEquals(0, q.readInt()); assertEquals(1, q.readInt())
        println("command parcel layout: OK")
    }

    @Test
    fun reportsMusicAvailableToTheLauncher() {
        service()
        assertEquals(1, Settings.System.getInt(app.contentResolver, "usb1AvailableStatus", -1))
        assertEquals(0, Settings.System.getInt(app.contentResolver, "onlineAvailableStatus", -1))
        val sent = generateSequence { shadowOf(app).nextStartedService }.toList()
        val toLauncher = sent.filter { it.component?.className == "com.saicmotor.launcher.model.LauncherService" }
            .mapNotNull {
                @Suppress("DEPRECATION")
                it.getParcelableExtra<CommandParcelable>(CommandParcelable.COMMAND_KEY)
            }
        val usb1 = toLauncher.first { it.event == 16417 && it.targetType == 0 }
        assertEquals(1, usb1.targetState)
        assertTrue(sent.any { it.component?.className == "com.saicmotor.hardkey.HardKeyService" })
        println("availability sent: ${toLauncher.joinToString { "type ${it.targetType}=${it.targetState}" }}")
    }

    @Test
    fun launcherStartupPlayRestoresInstantlyWithoutNetwork() {
        savePlayingState()
        val s = service()
        s.restoredByLauncher()
        val p = s.player()
        assertEquals(8, p.mediaItemCount)
        assertEquals("sub:s3", p.currentMediaItem?.mediaId)
        assertEquals(42_000L, p.currentPosition)
        assertTrue("should be trying to play", p.playWhenReady)
        // The server can't be reached: we wait ("buffering"), not skip or error.
        idle(3_000)
        assertEquals("sub:s3", p.currentMediaItem?.mediaId)
        val state = s.call("buildPlaybackState") as PlaybackStateCompat
        println("state while the server is unreachable: ${state.state} speed ${state.playbackSpeed}, error=${p.playerError?.errorCodeName}")
        // Shown as playing (the car's tile shows anything else as paused), but not moving.
        assertEquals(PlaybackStateCompat.STATE_PLAYING, state.state)
        assertEquals(0f, state.playbackSpeed)
        println("launcher start-up restore: OK")
    }

    @Test
    fun queueIsReadyBeforeTheLauncherAsks() {
        savePlayingState()
        val s = service()                  // started by the launcher as the head unit comes up
        waitFor { s.player().mediaItemCount > 0 }
        val p = s.player()
        assertEquals("sub:s3", p.currentMediaItem?.mediaId)
        assertFalse("ready, but waiting for the launcher", p.playWhenReady)
        s.onStartCommand(launcherCommand(CommandParcelable.SWITCH_SOURCE_ONLY_PLAY), 0, 1)
        looper.idle()
        assertTrue(p.playWhenReady)
        assertEquals(42_000L, p.currentPosition)
        // The timing logged: the queue was back before the launcher asked.
        fun field(name: String) = MediaService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(s)
        assertTrue((field("traceQueueAt") as Long) in 0..(field("traceLauncherAt") as Long))
        assertEquals(0, field("traceOnDiskPct"))
        s.call("finishTrace")
    }

    @Test
    fun bootFallbackPlaysAPreparedQueueIfTheLauncherNeverAsks() {
        savePlayingState()
        val s = service()
        waitFor { s.player().mediaItemCount > 0 }
        s.onStartCommand(Intent(app, MediaService::class.java).setAction(MediaService.ACTION_BOOT_START), 0, 1)
        idle(24_000)
        assertFalse(s.player().playWhenReady)
        idle(2_000)
        assertTrue(s.player().playWhenReady)
    }

    @Test
    fun networkProblemsAreWaitedOutOtherErrorsAreNot() {
        val policy = com.saicmotor.media.service.WaitForNetworkPolicy()
        val spec = androidx.media3.datasource.DataSpec(android.net.Uri.parse("http://x/rest/stream"))
        fun delay(e: java.io.IOException, count: Int = 1) = policy.getRetryDelayMsFor(
            androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo(
                androidx.media3.exoplayer.source.LoadEventInfo(1, spec, 0),
                androidx.media3.exoplayer.source.MediaLoadData(androidx.media3.common.C.DATA_TYPE_MEDIA),
                e, count))
        val noNetwork = androidx.media3.datasource.HttpDataSource.HttpDataSourceException(
            java.net.UnknownHostException("music.example.com"), spec,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            androidx.media3.datasource.HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        fun http(code: Int) = androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
            code, null, null, emptyMap(), spec, ByteArray(0))
        assertEquals(1_000L, delay(noNetwork))
        assertEquals(1_000L, delay(noNetwork, 200))
        assertEquals(androidx.media3.common.C.TIME_UNSET, delay(noNetwork, 601))
        assertEquals(3_000L, delay(http(503)))
        assertEquals(androidx.media3.common.C.TIME_UNSET, delay(http(404)))
        assertEquals(androidx.media3.common.C.TIME_UNSET, delay(java.io.IOException("bad file"), 4))
        assertEquals(Int.MAX_VALUE, policy.getMinimumLoadableRetryCount(androidx.media3.common.C.DATA_TYPE_MEDIA))
    }

    /** The launcher asks for "paused" when it last saw the music paused: start-up plays anyway. */
    @Test
    fun launcherStartupPauseStillPlays() {
        savePlayingState()
        val s = service()
        s.restoredByLauncher(CommandParcelable.SWITCH_SOURCE_ONLY_PAUSE)
        assertEquals(8, s.player().mediaItemCount)
        assertTrue(s.player().playWhenReady)
    }

    @Test
    fun startupStaysQuietWhenTheSettingIsNothing() {
        savePlayingState()
        com.saicmotor.media.data.PlaybackSettings.setStartupMode(app, com.saicmotor.media.data.PlaybackSettings.StartupMode.NOTHING)
        val s = service()
        s.restoredByLauncher(CommandParcelable.SWITCH_SOURCE_ONLY_PAUSE)
        assertEquals("loaded, ready to go", 8, s.player().mediaItemCount)
        assertFalse(s.player().playWhenReady)
    }

    @Test
    fun updaterReadsTheLatestReleaseAndComparesVersions() {
        val json = """{"tag_name":"v2.0.10","name":"2.0.10","assets":[
            {"name":"notes.txt","browser_download_url":"https://example.com/notes.txt","size":10},
            {"name":"CarMediaPlayer-2.0.10-MG4-platform-signed.apk","browser_download_url":"https://example.com/app.apk","size":8059106}]}"""
        val r = com.saicmotor.media.update.Updater.parseRelease(json)
        assertEquals("2.0.10", r.version)
        assertEquals("https://example.com/app.apk", r.apkUrl)
        assertEquals(8059106L, r.apkSize)
        assertEquals(null, com.saicmotor.media.update.Updater.parseRelease("""{"tag_name":"2.1","assets":[]}""").apkUrl)

        val u = com.saicmotor.media.update.Updater
        assertTrue(u.isNewer("2.0.10", "2.0.9"))
        assertTrue(u.isNewer("2.1", "2.0.9"))
        assertTrue(u.isNewer("3.0.0", "2.9.99"))
        assertFalse(u.isNewer("2.0.9", "2.0.9"))
        assertFalse(u.isNewer("2.0.9", "2.0.10"))
        assertFalse(u.isNewer("2.0", "2.0.0"))
    }

    @Test
    fun afterAnUpdateTheAppComesBackOnlyIfTheUpdaterDidIt() {
        val receiver = com.saicmotor.media.boot.BootReceiver()
        val replaced = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        val shadowApp = shadowOf(app)

        // Installed some other way (e.g. adb): nothing happens.
        receiver.onReceive(app, replaced)
        assertEquals(null, shadowApp.nextStartedActivity)

        // Installed by "Check for updates" while music was playing.
        com.saicmotor.media.data.Storage.prefs(app, "update").edit()
            .putLong("installing_at", System.currentTimeMillis() - 20_000)
            .putBoolean("was_playing", true).commit()
        receiver.onReceive(app, replaced)
        assertEquals("com.saicmotor.media.ui.activity.MainActivity", shadowApp.nextStartedActivity?.component?.className)
        assertEquals(MediaService.ACTION_RESUME_AFTER_UPDATE, shadowApp.nextStartedService?.action)
        // …once.
        receiver.onReceive(app, replaced)
        assertEquals(null, shadowApp.nextStartedActivity)
    }

    @Test
    fun playingCarriesOnAfterAnUpdate() {
        savePlayingState()
        val s = service()
        s.onStartCommand(Intent(app, MediaService::class.java).setAction(MediaService.ACTION_RESUME_AFTER_UPDATE), 0, 1)
        waitFor { s.player().mediaItemCount > 0 && s.player().playWhenReady }
        assertEquals(8, s.player().mediaItemCount)
        assertEquals(3, s.player().currentMediaItemIndex)
        assertTrue(s.player().playWhenReady)
    }

    private fun wheelKey(code: Int, down: Boolean) {
        app.sendBroadcast(Intent("com.saic.keyevent.hardkey.report")
            .putExtra("android.intent.extra.hardkey.keycode", code)
            .putExtra("android.intent.extra.hardkey.down", down)
            .putExtra("android.intent.extra.hardkey.longpress", false))
        looper.idle()
    }

    @Test
    fun wheelKeyTheLauncherIgnoresAfterStarIsDoneHere() {
        savePlayingState()
        val s = service()
        s.restoredByLauncher(CommandParcelable.SWITCH_SOURCE_ONLY_PAUSE)
        s.call("userPause")
        assertFalse(s.player().playWhenReady)

        // A centre press on its own: the launcher acts on it (it isn't here), so we don't.
        wheelKey(301, true); wheelKey(301, false)
        idle(700)
        assertFalse("left to the launcher", s.player().playWhenReady)

        // STAR (its release never comes), then centre: the launcher ignores
        // that one, so the app plays.
        wheelKey(17, true)
        wheelKey(301, true); wheelKey(301, false)
        idle(700)
        assertTrue("played by the app", s.player().playWhenReady)

        // The launcher counts afresh after that: the next press is its own again.
        wheelKey(301, true); wheelKey(301, false)
        idle(700)
        assertTrue(s.player().playWhenReady)
    }

    /** Stands in for YouTube: another app with its own player. */
    private class FakeOtherPlayer(var playing: Boolean = true) : MediaService.OtherPlayer {
        val calls = mutableListOf<String>()
        override val packageName = "org.smarttube.stable"
        override val isPlaying get() = playing
        override fun play()  { calls += "play";  playing = true }
        override fun pause() { calls += "pause"; playing = false }
    }

    @Test
    fun centreKeyControlsTheAppBeingWatchedThenTheMusicOnceItsClosed() {
        savePlayingState()
        val s = service()
        s.restoredByLauncher(CommandParcelable.SWITCH_SOURCE_ONLY_PAUSE)
        s.call("userPause")
        val youtube = FakeOtherPlayer()
        s.otherPlayerForTest = youtube
        idle(1_000)

        // Back (STAR), then centre: the launcher ignores it, the app passes it to YouTube.
        wheelKey(17, true); wheelKey(301, true); wheelKey(301, false)
        idle(700)
        assertEquals(listOf("pause"), youtube.calls)
        wheelKey(17, true); wheelKey(301, true); wheelKey(301, false)
        idle(700)
        assertEquals(listOf("pause", "play"), youtube.calls)
        assertFalse("the music stays paused", s.player().playWhenReady)

        // YouTube closed: the centre key is the music's again.
        s.otherPlayerForTest = null
        wheelKey(17, true); wheelKey(301, true); wheelKey(301, false)
        idle(700)
        assertTrue(s.player().playWhenReady)
    }

    @Test
    fun skipKeysLeaveThePausedQueueAloneWhileAnotherAppIsWatched() {
        savePlayingState()
        val s = service()
        s.restoredByLauncher(CommandParcelable.SWITCH_SOURCE_ONLY_PAUSE)
        s.call("userPause")
        s.otherPlayerForTest = FakeOtherPlayer()
        idle(1_000)
        val index = s.player().currentMediaItemIndex
        wheelKey(17, true); wheelKey(300, true); wheelKey(300, false)
        idle(700)
        assertEquals(index, s.player().currentMediaItemIndex)
        assertFalse(s.player().playWhenReady)
    }

    @Test
    fun wheelKeyAfterStarIsLeftAloneIfTheLauncherActs() {
        savePlayingState()
        val s = service()
        s.restoredByLauncher(CommandParcelable.SWITCH_SOURCE_ONLY_PAUSE)
        wheelKey(17, true)
        wheelKey(300, true); wheelKey(300, false)
        // The launcher skipped after all (a command arrives in time).
        s.onStartCommand(launcherCommand(CommandParcelable.SWITCH_SOURCE_ONLY_PLAY), 0, 1)
        val index = s.player().currentMediaItemIndex
        idle(700)
        assertEquals("not skipped twice", index, s.player().currentMediaItemIndex)
    }

    @Test
    fun skipKeysPressSkipsHoldSeeksAndPreviousRestartsTheSong() {
        savePlayingState()
        val s = service()
        s.restoredByLauncher()
        val p = s.player()
        val handle = { e: KeyEvent -> s.call("handleMediaKey", e); idle(1) }

        // Short press of next: next song.
        var t = SystemClock.uptimeMillis()
        handle(key(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN, t, t))
        idle(150)
        handle(key(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_UP, t, t + 150))
        assertEquals("sub:s4", p.currentMediaItem?.mediaId)

        // Hold next for ~1.7 s: fast forward 5 s every half second, then stop.
        t = SystemClock.uptimeMillis()
        handle(key(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN, t, t))
        idle(1_700)
        val held = p.currentPosition
        handle(key(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_UP, t, t + 1_700))
        idle(2_000)
        assertEquals("still the same song", "sub:s4", p.currentMediaItem?.mediaId)
        assertTrue("fast-forwarded ($held ms)", held >= 15_000)
        assertEquals("stopped when let go", held, p.currentPosition)
        println("held next for 1.7 s → ${held / 1000} s in")

        // Press and release arriving together, but held 1 s: one 10 s jump.
        t = SystemClock.uptimeMillis()
        handle(key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.ACTION_UP, t - 1_000, t))
        assertEquals(held - 10_000, p.currentPosition)

        // Previous, well into the song: back to its start.
        t = SystemClock.uptimeMillis()
        handle(key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.ACTION_DOWN, t, t))
        handle(key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.ACTION_UP, t, t + 100))
        assertEquals("sub:s4", p.currentMediaItem?.mediaId)
        assertEquals(0L, p.currentPosition)
        // Previous again, at the start: the song before.
        t = SystemClock.uptimeMillis()
        handle(key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.ACTION_DOWN, t, t))
        handle(key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.ACTION_UP, t, t + 100))
        assertEquals("sub:s3", p.currentMediaItem?.mediaId)
        println("skip / hold / previous: OK")
    }

    @Test
    fun launcherFastForwardRunsUntilStopped() {
        savePlayingState()
        val s = service()
        s.restoredByLauncher()
        val p = s.player()
        val start = p.currentPosition
        s.call("startFastSeek", true)      // what onFastForward does
        idle(1_200)
        s.call("handleCustomAction", MediaService.ACTION_STOP_FAST_FORWARD, null)
        val stopped = p.currentPosition
        idle(2_000)
        assertTrue(stopped - start >= 10_000)
        assertEquals(stopped, p.currentPosition)
    }

    @Test
    fun playFromOtherAppsIsIgnoredWhileSomethingElsePlays() {
        val s = service()
        val audio = shadowOf(app.getSystemService(android.media.AudioManager::class.java))
        audio.setIsMusicActive(true)
        assertTrue("launcher's volume-key play while YouTube plays", s.call("externalPlayBlocked", "com.saicmotor.launcher") as Boolean)
        assertFalse("our own screen can always play", s.call("externalPlayBlocked", app.packageName) as Boolean)
        audio.setIsMusicActive(false)
        assertFalse(s.call("externalPlayBlocked", "com.saicmotor.launcher") as Boolean)
    }

    @Test
    fun repeatStaysOffAndTheQueueIsSaved() {
        savePlayingState()
        val s = service()
        s.restoredByLauncher()
        val p = s.player()
        p.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
        idle(10)
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, p.repeatMode)
        // Skip on, and the saved queue follows.
        p.seekToNextMediaItem()
        idle(1_500)
        val snapshot = ResumeStore(app).loadSnapshot()
        assertEquals(8, snapshot.size)
        assertNotNull(snapshot.firstOrNull { it.mediaId == "sub:s4" })
        assertEquals(200_000L, snapshot[0].durationMs)
    }
}
