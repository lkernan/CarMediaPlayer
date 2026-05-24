package com.saicmotor.media.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.util.Log

/**
 * Manages Bluetooth A2DP sink state and AVRCP control / metadata for the
 * car head unit.
 *
 * Responsibilities:
 *  - Listen for A2DP sink connection / disconnection broadcasts
 *  - Listen for AVRCP track-event broadcasts (title, artist, album,
 *    playback state) and forward them to [Listener]
 *  - Forward play / pause / next / previous transport commands to the
 *    connected phone
 *
 * ## Transport-command path
 *
 * On this AOSP build the system's [android.bluetooth.BluetoothAvrcpController]
 * does **not** expose `sendPassThroughCmd` at all — only `sendGroupNavigationCmd`.
 * Instead we connect (as a MediaBrowser client) to the bundled
 * `com.android.bluetooth/A2dpMediaBrowserService`, grab the [MediaSession] token
 * it publishes for the active A2DP source device, and drive transport commands
 * through that session's [MediaControllerCompat.TransportControls].  The BT
 * stack does the actual AVRCP plumbing internally, so this works without any
 * @hide reflection.
 */
class BluetoothMediaManager(private val context: Context) {

    interface Listener {
        fun onBtConnectionChanged(connected: Boolean, deviceName: String?)
        fun onBtMetadataChanged(title: String, artist: String, album: String, artUri: String?)
        fun onBtPlaybackChanged(isPlaying: Boolean)
    }

    var listener: Listener? = null

    var isConnected: Boolean = false
        private set
    var connectedDevice: BluetoothDevice? = null
        private set
    var connectedDeviceName: String = ""
        private set

    // ── MediaBrowser → MediaController bridge to the BT stack ────────────────
    private var mediaBrowser: MediaBrowserCompat? = null
    private var btController: MediaControllerCompat? = null

    private val browserConnectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val token = mediaBrowser?.sessionToken ?: return
            btController = MediaControllerCompat(context, token)
            Log.d(TAG, "Connected to com.android.bluetooth MediaBrowserService; controller acquired")
        }
        override fun onConnectionSuspended() {
            Log.d(TAG, "BT MediaBrowserService connection suspended")
            btController = null
        }
        override fun onConnectionFailed() {
            Log.w(TAG, "BT MediaBrowserService connection failed")
            btController = null
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_A2DP_SINK_STATE_CHANGED -> handleA2dpState(intent)
                ACTION_AVRCP_TRACK_EVENT       -> handleTrackEvent(intent)
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_A2DP_SINK_STATE_CHANGED)
            addAction(ACTION_AVRCP_TRACK_EVENT)
        }
        context.registerReceiver(receiver, filter)

        // Discover the BT stack's MediaBrowserService component name and connect.
        // We do this lazily on register() instead of on first BT connect because
        // the service stays up across pair/unpair cycles — opening it once and
        // keeping it open means transport commands are dispatched without any
        // round-trip the first time the user taps a button.
        connectToBtMediaBrowser()

        // Check for a device that was already connected before we started.
        checkInitialConnection()
    }

    fun unregister() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        mediaBrowser?.disconnect()
        mediaBrowser  = null
        btController  = null
    }

    private fun connectToBtMediaBrowser() {
        val intent = Intent("android.media.browse.MediaBrowserService")
            .setPackage(BT_PACKAGE)
        val resolveInfo = context.packageManager.queryIntentServices(intent, 0)
            .firstOrNull()
        if (resolveInfo == null) {
            Log.w(TAG, "No MediaBrowserService in $BT_PACKAGE — transport control unavailable")
            return
        }
        val component = ComponentName(
            resolveInfo.serviceInfo.packageName,
            resolveInfo.serviceInfo.name
        )
        Log.d(TAG, "Binding BT MediaBrowserService: $component")
        mediaBrowser = MediaBrowserCompat(context, component, browserConnectionCallback, null)
            .also { it.connect() }
    }

    // ── Transport control ────────────────────────────────────────────────────

    /**
     * Sends an AVRCP-style transport command to the connected phone.  Keep the
     * AVRCP key-code vocabulary for the call site's convenience even though
     * internally we route to the BT stack's MediaController.
     */
    fun sendPassThrough(avrcpKeyCode: Int) {
        val tc = btController?.transportControls
        if (tc == null) {
            Log.w(TAG, "sendPassThrough(0x${avrcpKeyCode.toString(16)}) dropped: no BT MediaController yet")
            return
        }
        when (avrcpKeyCode) {
            PASSTHRU_PLAY     -> tc.play()
            PASSTHRU_PAUSE    -> tc.pause()
            PASSTHRU_STOP     -> tc.stop()
            PASSTHRU_NEXT     -> tc.skipToNext()
            PASSTHRU_PREVIOUS -> tc.skipToPrevious()
            else              -> Log.w(TAG, "Unknown AVRCP key 0x${avrcpKeyCode.toString(16)}")
        }
        Log.d(TAG, "Dispatched AVRCP 0x${avrcpKeyCode.toString(16)} via BT MediaController")
    }

    // ── Connection state ─────────────────────────────────────────────────────

    /** Query the A2DP sink profile for already-connected devices at startup. */
    private fun checkInitialConnection() {
        BluetoothAdapter.getDefaultAdapter()?.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val devices = proxy.connectedDevices
                    if (devices.isNotEmpty()) {
                        val device = devices.first()
                        isConnected         = true
                        connectedDevice     = device
                        connectedDeviceName = device.name ?: "Bluetooth"
                        listener?.onBtConnectionChanged(true, connectedDeviceName)
                    }
                    BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(profile, proxy)
                }
                override fun onServiceDisconnected(profile: Int) {}
            },
            PROFILE_A2DP_SINK
        )
    }

    private fun handleA2dpState(intent: Intent) {
        val state = intent.getIntExtra(
            BluetoothProfile.EXTRA_STATE,
            BluetoothProfile.STATE_DISCONNECTED
        )
        @Suppress("DEPRECATION")
        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                isConnected         = true
                connectedDevice     = device
                connectedDeviceName = device?.name ?: "Bluetooth"
                listener?.onBtConnectionChanged(true, connectedDeviceName)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                isConnected         = false
                connectedDevice     = null
                connectedDeviceName = ""
                listener?.onBtConnectionChanged(false, null)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleTrackEvent(intent: Intent) {
        val meta: MediaMetadata?  = intent.getParcelableExtra(EXTRA_METADATA)
        val pb:   PlaybackState?  = intent.getParcelableExtra(EXTRA_PLAYBACK)

        meta?.let {
            val title  = it.getString(MediaMetadata.METADATA_KEY_TITLE)  ?: ""
            val artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val album  = it.getString(MediaMetadata.METADATA_KEY_ALBUM)  ?: ""
            // Some phones send art URI; fall back to album art URI key
            val artUri = it.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            listener?.onBtMetadataChanged(title, artist, album, artUri)
        }

        pb?.let {
            listener?.onBtPlaybackChanged(it.state == PlaybackState.STATE_PLAYING)
        }
    }

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        // BT stack package — owns A2dpMediaBrowserService and the AVRCP MediaSession
        private const val BT_PACKAGE = "com.android.bluetooth"

        // Intent action strings — stable AOSP values, safe to use as literals
        private const val ACTION_A2DP_SINK_STATE_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
        private const val ACTION_AVRCP_TRACK_EVENT =
            "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT"

        // Parcelable extra keys embedded in AVRCP track-event broadcasts
        private const val EXTRA_METADATA =
            "android.bluetooth.avrcp-controller.profile.extra.METADATA"
        private const val EXTRA_PLAYBACK =
            "android.bluetooth.avrcp-controller.profile.extra.PLAYBACK"

        // AVRCP 1.3 PassThrough operation IDs (Bluetooth SIG spec §25.3).
        // Kept as the call-site vocabulary even though internally we route
        // to MediaController.TransportControls — the key codes are the
        // standard way to talk about these commands and the mapping is 1:1.
        const val PASSTHRU_PLAY     = 0x44
        const val PASSTHRU_PAUSE    = 0x46
        const val PASSTHRU_STOP     = 0x45
        const val PASSTHRU_NEXT     = 0x4B
        const val PASSTHRU_PREVIOUS = 0x4C

        // BluetoothProfile integer constant (class sometimes hidden)
        private const val PROFILE_A2DP_SINK = 11

        private const val TAG = "BluetoothMediaManager"
    }
}
