package shots

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaFormat
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.support.v4.media.session.IMediaControllerCallback
import android.support.v4.media.session.IMediaSession
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saicmotor.common.CommandParcelable
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.service.MediaCache
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.service.ResumeStore
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.Collections

/**
 * The steering wheel as it is in the car: the SAIC launcher's own code (its
 * SwcManager and ModeManager, converted from the head unit's firmware — see
 * tools/firmware/README.md) gets the wheel's key reports, decides what they
 * mean and sends the app's session play / pause / skip, or turns the car's
 * volume, exactly as it does on the head unit.  The app gets the same key
 * reports, as it does in the car, and plays songs from a made-up Navidrome.
 *
 * Skipped unless the launcher's code was converted for this run
 * (FIRMWARE_LAUNCHER_APK): it's SAIC's, so it's never in the repo.
 *
 * What the launcher needs from the rest of the car is stood in for: Android's
 * MediaController (its commands reach the app's session callback on the app's
 * main thread; the state it reads is the one the app last set), and SAIC's car
 * audio service (CarAdapterService → ICarAudioService), checked against the
 * firmware's own interface code.  The launcher's work thread is the main
 * thread here, so it takes turns with the app instead of running alongside.
 */
@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi", shadows = [InstantAudioTrack::class])
class LauncherWheelTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private lateinit var launcherCode: LauncherCode
    private lateinit var server: FakeNavidrome
    private lateinit var car: CarAudio
    private lateinit var s: MediaService
    /** What the launcher sent the app's session, in order. */
    private val sent = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        val jar = LauncherCode.jar
        assumeTrue("the launcher's code wasn't converted for this run (FIRMWARE_LAUNCHER_APK)", jar != null)
        if (System.getenv("LAUNCHER_LOG") != null) org.robolectric.shadows.ShadowLog.stream = System.out
        launcherCode = LauncherCode(jar!!, javaClass.classLoader!!)
        freshCache()
        server = FakeNavidrome()
        // As if played before: Navidrome's transcoding cache has them, so they can be skipped through.
        server.transcodeCached = true
        server.addSongs(3, seconds = 120)
        SubsonicSettings.save(app, SubsonicSettings.Config(server.url, "me", "secret"))
        val format = MediaFormat().apply { setString(MediaFormat.KEY_MIME, "audio/opus") }
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName("fake.opus").setIsEncoder(false)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(format).setIsEncoder(false).build())
                .build())
        androidx.media3.exoplayer.mediacodec.MediaCodecUtil.clearDecoderInfoCache()
        ShadowMediaCodec.addDecoder("fake.opus", ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024) { input, output ->
            input.position(input.limit())
            output.put(ByteArray(3840))
        })
        car = CarAudio(app, launcherCode)
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent("com.saicmotor.caradapter.CarAdapterService").setPackage("com.saicmotor.caradapter"),
            ComponentName("com.saicmotor.caradapter", "com.saicmotor.caradapter.service.CarAdapterService"),
            car.adapter)
    }

    @After
    fun tearDown() {
        if (::s.isInitialized) s.onDestroy()
        if (::server.isInitialized) server.close()
    }

    // ── the car ──────────────────────────────────────────────────────────

    /**
     * SAIC's car audio service: the volume the launcher (and the app, for a
     * key the launcher dropped) turns.  The calls are decoded with the
     * firmware's own interface constants, so a wrong code in the app shows.
     * A change is written where the car keeps it (android.car.VOLUME_GROUP/n),
     * which is what the app watches.
     */
    private class CarAudio(private val app: Context, code: LauncherCode) {
        private val audioStub = code.loadClass("com.saicmotor.carapi.audio.ICarAudioService\$Stub")
        private val adapterStub = code.loadClass("com.saicmotor.carapi.ICarAdapterService\$Stub")
        private val audioDescriptor = constant(audioStub, "DESCRIPTOR") as String
        private val volumeUp = constant(audioStub, "TRANSACTION_volumeUp") as Int
        private val volumeDown = constant(audioStub, "TRANSACTION_volumeDown") as Int
        private val audioType = constant(audioStub, "TRANSACTION_getCurrentAudioType") as Int
        private val adapterDescriptor = constant(adapterStub, "DESCRIPTOR") as String
        private val queryClient = constant(adapterStub, "TRANSACTION_queryClient") as Int

        @Volatile var level = 15
        /** The sound in use: 0 = media (music), as the launcher sees it. */
        @Volatile var inUse = 0
        /** Each step and who made it: "launcher up", "app down", … */
        val steps: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** The audio service as one caller sees it (the launcher and the app each have their own connection). */
        fun audioFor(who: String): IBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != volumeUp && code != volumeDown && code != audioType) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(audioDescriptor)
                if (code == audioType) {
                    reply?.writeNoException()
                    reply?.writeInt(inUse)
                    return true
                }
                val flag = data.readInt()
                assertEquals("the hard-key flag (shows the volume bar)", 1024, flag)
                step(who, code == volumeUp)
                reply?.writeNoException()
                return true
            }
        }

        private val forApp = audioFor("app")

        /** CarAdapterService, which the app binds to for the audio service (queryClient(10)). */
        val adapter: IBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != queryClient) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(adapterDescriptor)
                val which = data.readInt()
                reply?.writeNoException()
                reply?.writeStrongBinder(if (which == 10) forApp else null)
                return true
            }
        }

        private fun step(who: String, up: Boolean) {
            steps += "$who ${if (up) "up" else "down"}"
            level = (level + if (up) 1 else -1).coerceIn(0, 39)
            Settings.Global.putInt(app.contentResolver, "android.car.VOLUME_GROUP/0", level)
            app.contentResolver.notifyChange(Settings.Global.getUriFor("android.car.VOLUME_GROUP/0"), null)
        }

        private companion object {
            fun constant(c: Class<*>, name: String): Any? = c.getDeclaredField(name).apply { isAccessible = true }.get(null)
        }
    }

    /**
     * The app's session as the launcher holds it (an Android MediaController).
     * Its commands go to the app's session callback, on the app's main thread,
     * the way Android delivers them; its state is the one the app last set.
     */
    private fun launcherView(s: MediaService): MediaController {
        val session = field(s, "session") as MediaSessionCompat
        val impl = field(session, "mImpl")!!
        val callback = impl.javaClass.getMethod("getCallback").invoke(impl) as MediaSessionCompat.Callback
        // The callback Android calls (MediaSessionCompat's, which passes it on to the app's).
        val android = field(callback, "mCallbackFwk") as MediaSession.Callback
        val main = Handler(Looper.getMainLooper())
        val iface = Class.forName("android.media.session.ISessionController")
        val binder = Binder()
        val controller = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, m, args ->
            fun deliver(what: String, call: () -> Unit): Any? { sent += what; main.post(call); return null }
            when (m.name) {
                "play"             -> deliver("play") { android.onPlay() }
                "pause"            -> deliver("pause") { android.onPause() }
                "next"             -> deliver("next") { android.onSkipToNext() }
                "previous"         -> deliver("previous") { android.onSkipToPrevious() }
                "fastForward"      -> deliver("fastForward") { android.onFastForward() }
                "rewind"           -> deliver("rewind") { android.onRewind() }
                "sendCustomAction" -> (args!![2] as String).let { action ->
                    deliver(action) { android.onCustomAction(action, args[3] as Bundle?) } }
                "getPackageName"   -> "com.saicmotor.media"
                "getTag"           -> "MediaService"
                "asBinder"         -> binder
                "hashCode"         -> System.identityHashCode(proxy)
                "equals"           -> proxy === args?.get(0)
                "toString"         -> "the app's session"
                else               -> throw AssertionError("the launcher asked the session for ${m.name}: not stood in for")
            }
        }
        val token = MediaSession.Token::class.java.getDeclaredConstructor(iface).apply { isAccessible = true }.newInstance(controller)
        val view = MediaController(app, token)
        // Robolectric's MediaController keeps a state of its own: it's given the
        // app's each time the app sets one (as Android passes it on).
        val shadow = shadowOf(view)
        fun show(state: PlaybackStateCompat?) = shadow.setPlaybackState(state?.playbackState as PlaybackState?)
        show(field(impl, "mPlaybackState") as PlaybackStateCompat?)
        val listener = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IMediaControllerCallback::class.java)) { proxy, m, args ->
            when (m.name) {
                "onPlaybackStateChanged" -> { show(args!![0] as PlaybackStateCompat?); null }
                "asBinder" -> binder
                "hashCode" -> System.identityHashCode(proxy)
                "equals"   -> proxy === args?.get(0)
                "toString" -> "the launcher's view"
                else       -> null
            }
        } as IMediaControllerCallback
        (field(impl, "mExtraSession") as IMediaSession).registerCallbackListener(listener)
        return view
    }

    /**
     * The launcher's steering-wheel code, set up as it is in the car once it's
     * running: the screen on, not reversing, the app's session the current
     * source, the wheel keys set to control media (not custom functions).
     */
    private fun startLauncher(s: MediaService) {
        val code = launcherCode
        val mm = code.bare("com.saicmotor.launcher.model.ModeManager")
        val set = { name: String, value: Any? -> LauncherCode.set(mm, name, value) }
        set("mSystemPowerState", true)
        set("mScreenStateMachine", code.bare("com.saicmotor.launcher.model.ScreenStateMachine"))
        val callbackIface = code.loadClass("com.saicmotor.launcher.ILauncherCallback")
        set("mLauncherCallback", Proxy.newProxyInstance(code, arrayOf(callbackIface)) { proxy, m, args ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals"   -> proxy === args?.get(0)
                "toString" -> "launcher UI"
                else       -> defaultFor(m.returnType)
            }
        })
        set("mCurrentController", launcherView(s))
        val workHandler = code.loadClass("com.saicmotor.launcher.model.ModeManager\$WorkHandler")
            .getDeclaredConstructor(mm.javaClass, Looper::class.java).apply { isAccessible = true }
        set("mHandler", workHandler.newInstance(mm, Looper.getMainLooper()))
        val volumeManager = code.loadClass("com.saicmotor.launcher.model.VolumeManager").getConstructor().newInstance()
        val audioClient = code.loadClass("com.saicmotor.carapi.client.CarAudioClient")
            .getConstructor(IBinder::class.java).newInstance(car.audioFor("launcher"))
        volumeManager.javaClass.getMethod("setCarAudioClient", audioClient.javaClass).invoke(volumeManager, audioClient)
        set("volumeManager", volumeManager)
        code.loadClass("com.saicmotor.launcher.model.SwcManager")
            .getConstructor(Context::class.java, mm.javaClass).newInstance(app, mm)
    }

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    // ── the app ──────────────────────────────────────────────────────────

    private fun field(target: Any, name: String): Any? = LauncherCode.get(target, name)

    private fun MediaService.player(): ExoPlayer = field(this, "player") as ExoPlayer

    private val MediaService.fastSeeking get() = (field(this, "fastSeekJob") as Job?)?.isActive == true

    private fun freshCache() {
        val f = MediaCache::class.java.getDeclaredField("cache").apply { isAccessible = true }
        (f.get(null) as? androidx.media3.datasource.cache.SimpleCache)?.release()
        f.set(null, null)
    }

    /** The car comes on: the app plays the album it was playing, and the launcher takes the wheel. */
    private fun carOn() {
        val store = ResumeStore(app)
        val items = server.songs.values.map {
            ResumeStore.SavedItem("sub:${it.id}", it.title, "Artist", "Album", null, "al1", "ar1", it.seconds * 1_000L)
        }
        store.saveQueue(MediaService.ONLINE_ROOT, ResumeStore.QueueKind.BROWSE, MediaService.albumNodeId("al1"))
        store.saveItem(items.first(), 0L)
        store.saveSnapshot(items)
        s = Robolectric.buildService(MediaService::class.java).create().get()
        looper.idle()
        s.onStartCommand(Intent().setComponent(ComponentName(app, MediaService::class.java))
            .putExtra(CommandParcelable.COMMAND_KEY,
                CommandParcelable(CommandParcelable.SWITCH_SOURCE_ONLY_PLAY, "com.saicmotor.launcher", 0, -1)), 0, 1)
        // Everything reaching the session in these tests is the launcher's.
        s.callerForTest = "com.saicmotor.launcher"
        startLauncher(s)
        drive(10_000) { s.player().isPlaying }
        assertTrue("playing once the car's on", s.player().isPlaying)
        drive(1_500)          // a moment after starting, as a driver would be
        sent.clear()
    }

    /** Lets [ms] go by on the car's clock (stopping early once [until]). */
    private fun drive(ms: Long, until: () -> Boolean = { false }) {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < ms && !until()) {
            looper.idleFor(Duration.ofMillis(10))
            Thread.sleep(1)
        }
    }

    /** Waits (in real time) for work done off the main thread, e.g. the app turning the volume. */
    private fun waitFor(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < until) { looper.idle(); Thread.sleep(5) }
        looper.idle()
    }

    // ── the wheel ────────────────────────────────────────────────────────

    private fun wheel(code: Int, down: Boolean, longPress: Boolean = false) {
        app.sendBroadcast(Intent("com.saic.keyevent.hardkey.report")
            .putExtra("android.intent.extra.hardkey.keycode", code)
            .putExtra("android.intent.extra.hardkey.down", down)
            .putExtra("android.intent.extra.hardkey.longpress", longPress))
        looper.idle()
    }

    /** A press, as the wheel reports it: down, then up about 100 ms later. */
    private fun press(code: Int) { wheel(code, true); drive(100); wheel(code, false) }

    /** Held down: down, "long press" after half a second, up when let go. */
    private fun hold(code: Int, ms: Long) {
        wheel(code, true); drive(500)
        wheel(code, true, longPress = true); drive(ms - 500)
        wheel(code, false)
    }

    /** STAR (the driver's Back): only its press is ever reported. */
    private fun star() = wheel(STAR, true)

    private val index get() = s.player().currentMediaItemIndex
    private val playing get() = s.player().playWhenReady

    // ── tests ────────────────────────────────────────────────────────────

    @Test
    fun centreKeyPausesAndPlays() {
        carOn()
        press(CENTRE); drive(600)
        assertEquals(listOf("pause"), sent.toList())
        assertFalse("paused", playing)
        drive(2_000)
        press(CENTRE); drive(600)
        assertEquals(listOf("pause", "play"), sent.toList())
        assertTrue("playing again", playing)
    }

    @Test
    fun nextAndPreviousSkip() {
        carOn()
        press(NEXT); drive(400)
        assertEquals(1, index)
        assertTrue(playing)
        // Paused, so previous comes at the start of a song (here songs play
        // faster than the clock, and previous well into a song goes back to
        // its start).
        press(CENTRE); drive(600)
        press(NEXT); drive(400)
        assertEquals(2, index)
        press(PREVIOUS); drive(400)
        assertEquals(1, index)
        assertEquals(listOf("next", "pause", "next", "previous"), sent.toList())
        assertFalse("still paused", playing)
    }

    @Test
    fun holdingNextFastForwardsUntilLetGo() {
        carOn()
        wheel(NEXT, true); drive(500)
        wheel(NEXT, true, longPress = true); drive(1_000)
        assertTrue("fast-forwarding while held", s.fastSeeking)
        wheel(NEXT, false); drive(300)
        assertFalse("stopped when let go", s.fastSeeking)
        assertEquals("not skipped to the next song as well", 0, index)
        assertEquals(listOf("fastForward", "__STOP_FAST_FORWARD__"), sent.toList())
        assertTrue(playing)
    }

    @Test
    fun holdingPreviousRewindsUntilLetGo() {
        carOn()
        press(NEXT); drive(400)
        hold(PREVIOUS, 1_500); drive(300)
        assertFalse("stopped when let go", s.fastSeeking)
        assertEquals("the same song", 1, index)
        assertEquals(listOf("next", "rewind", "__STOP_FAST_FORWARD__"), sent.toList())
    }

    /** After Back (STAR) the launcher drops the next key: the app does it, once. */
    @Test
    fun centreKeyAfterStarPausesOnce() {
        carOn()
        star(); press(CENTRE); drive(800)
        assertEquals("the launcher sent nothing", emptyList<String>(), sent.toList())
        assertFalse("paused by the app", playing)
        drive(2_000)
        // The next press is the launcher's again.
        press(CENTRE); drive(600)
        assertEquals(listOf("play"), sent.toList())
        assertTrue(playing)
    }

    @Test
    fun nextAfterStarSkipsOnce() {
        carOn()
        star(); press(NEXT); drive(800)
        assertEquals("the launcher only sent 'stop fast forward'", listOf("__STOP_FAST_FORWARD__"), sent.toList())
        assertEquals("skipped once", 1, index)
        press(NEXT); drive(400)
        assertEquals(2, index)
    }

    @Test
    fun volumeAfterStarTurnsItOnce() {
        carOn()
        val before = car.level
        star(); press(VOLUME_UP); drive(400)
        waitFor { car.steps.isNotEmpty() }
        drive(400)
        assertEquals(listOf("app up"), car.steps.toList())
        assertEquals(before + 1, car.level)
        press(VOLUME_UP); drive(400)
        assertEquals(listOf("app up", "launcher up"), car.steps.toList())
        star(); press(VOLUME_DOWN); drive(400)
        waitFor { car.steps.size == 3 }
        drive(400)
        assertEquals(listOf("app up", "launcher up", "app down"), car.steps.toList())
        assertTrue("still playing", playing)
    }

    @Test
    fun volumeWhilePlayingJustTurnsIt() {
        carOn()
        press(VOLUME_UP); drive(600)
        press(VOLUME_DOWN); drive(600)
        press(VOLUME_DOWN); drive(600)
        assertEquals(listOf("launcher up", "launcher down", "launcher down"), car.steps.toList())
        assertEquals("nothing sent to the music", emptyList<String>(), sent.toList())
        assertTrue(playing)
    }

    /** The launcher sends "play" with every volume press while paused: the music stays paused. */
    @Test
    fun volumeWhilePausedStaysPaused() {
        carOn()
        press(CENTRE); drive(2_000)
        assertFalse(playing)
        sent.clear()
        press(VOLUME_UP); drive(1_000)
        assertEquals(listOf("launcher up"), car.steps.toList())
        assertEquals("the launcher's 'play' with the volume", listOf("play"), sent.toList())
        assertFalse("still paused", playing)
        press(VOLUME_DOWN); drive(1_000)
        assertFalse("still paused", playing)
        // And the centre key still plays it.
        press(CENTRE); drive(600)
        assertTrue(playing)
    }

    @Test
    fun holdingVolumeWhilePausedTurnsItAndStaysPaused() {
        carOn()
        press(CENTRE); drive(2_000)
        sent.clear()
        hold(VOLUME_UP, 1_500)
        val held = car.steps.size
        drive(1_000)
        assertEquals("no more once let go", held, car.steps.size)
        assertTrue("turned every 200 ms while held ($held)", held >= 4)
        assertTrue("all by the launcher", car.steps.all { it == "launcher up" })
        assertFalse("still paused", playing)
    }

    @Test
    fun holdingVolumeAfterStarIsTheLaunchersStill() {
        carOn()
        star(); hold(VOLUME_DOWN, 1_500); drive(1_000)
        assertTrue("turned down while held (${car.steps})", car.steps.size >= 4)
        assertTrue("all by the launcher", car.steps.all { it == "launcher down" })
    }

    /**
     * Quick presses of the centre key, playing or paused: each one counts.
     * (The launcher picks play or pause by the state it sees, and a "play"
     * used to wait a moment here first: a second press within ~200 ms sent
     * "play" again and was lost.)
     */
    @Test
    fun quickCentrePressesEachCount() {
        carOn()
        val wrong = mutableListOf<String>()
        for (gap in listOf(50L, 100L, 150L, 400L)) {       // from letting go to the next press
            for (times in 2..3) {
                val wasPlaying = playing
                sent.clear()
                repeat(times) { i -> if (i > 0) drive(gap); press(CENTRE) }
                drive(1_500)
                val expected = if (times % 2 == 0) wasPlaying else !wasPlaying
                val what = "${if (wasPlaying) "playing" else "paused"}, $times presses $gap ms apart: " +
                    "launcher sent $sent → ${if (playing) "playing" else "paused"}"
                println(what)
                if (playing != expected) wrong += what
                drive(2_000)
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /** A volume press just after pausing: the launcher's "play" with it doesn't start the music again. */
    @Test
    fun volumeJustAfterPausingStaysPaused() {
        carOn()
        press(CENTRE); drive(150)
        press(VOLUME_UP); drive(1_000)
        assertEquals(listOf("pause", "play"), sent.toList())
        assertEquals(listOf("launcher up"), car.steps.toList())
        assertFalse("still paused", playing)
    }

    /** The launcher does nothing with a held centre key; nor does the app. */
    @Test
    fun holdingTheCentreKeyDoesNothing() {
        carOn()
        hold(CENTRE, 1_500); drive(1_000)
        assertEquals(emptyList<String>(), sent.toList())
        assertTrue(playing)
    }

    /** Stands in for YouTube: another app with its own player, being watched. */
    private class Watched(var isPlayingNow: Boolean) : MediaService.OtherPlayer {
        val calls = mutableListOf<String>()
        override val packageName = "org.example.videos"
        override val isPlaying get() = isPlayingNow
        override fun play()  { calls += "play";  isPlayingNow = true }
        override fun pause() { calls += "pause"; isPlayingNow = false }
    }

    /**
     * Watching a video, the music paused: the launcher still sends the wheel's
     * keys to the music (its source).  The centre key plays / pauses the
     * video, quick presses included; the others leave the music alone.
     */
    @Test
    fun wheelWhileWatchingAVideo() {
        carOn()
        press(CENTRE); drive(1_500)
        val video = Watched(isPlayingNow = true)
        s.otherPlayerForTest = video
        sent.clear()
        press(CENTRE); drive(600)
        assertEquals(listOf("pause"), video.calls)
        press(CENTRE); drive(600)
        assertEquals(listOf("pause", "play"), video.calls)
        press(CENTRE); drive(100); press(CENTRE); drive(600)
        assertEquals(listOf("pause", "play", "pause", "play"), video.calls)
        press(VOLUME_UP); drive(600)
        press(NEXT); drive(600)
        assertEquals("volume and next leave the video alone", 4, video.calls.size)
        assertEquals(0, index)
        assertFalse("the music stays paused", playing)
        assertEquals(listOf("launcher up"), car.steps.toList())
        // The video closed: the centre key is the music's again.
        s.otherPlayerForTest = null
        press(CENTRE); drive(600)
        assertTrue(playing)
    }

    private companion object {
        const val STAR = 17
        const val VOLUME_UP = 297
        const val VOLUME_DOWN = 298
        const val PREVIOUS = 299
        const val NEXT = 300
        const val CENTRE = 301
    }
}
