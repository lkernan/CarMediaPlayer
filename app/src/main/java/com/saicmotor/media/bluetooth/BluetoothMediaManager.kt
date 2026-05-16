package com.saicmotor.media.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.PlaybackState

/**
 * Manages Bluetooth A2DP sink state and AVRCP metadata for the car head unit.
 *
 * Responsibilities:
 *  - Listen for A2DP sink connection/disconnection broadcasts
 *  - Listen for AVRCP track-event broadcasts (title, artist, album, playback state)
 *  - Forward changes to [Listener] so MediaService can update its MediaSession
 *  - Send AVRCP 1.3 PassThrough commands to the connected phone (play, pause, next, prev)
 *
 * All intent action / extra key strings are kept as literals to avoid depending on
 * hidden-API class imports; the values are stable AOSP constants.
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

    // Cached AVRCP controller proxy — kept open after first connection so that
    // sendPassThrough() can dispatch commands immediately without an async round-trip.
    private var avrcpProxy: BluetoothProfile? = null
    private val avrcpServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            avrcpProxy = proxy
        }
        override fun onServiceDisconnected(profile: Int) {
            avrcpProxy = null
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

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_A2DP_SINK_STATE_CHANGED)
            addAction(ACTION_AVRCP_TRACK_EVENT)
        }
        context.registerReceiver(receiver, filter)

        // Open the AVRCP controller proxy once so passthrough commands are instant
        BluetoothAdapter.getDefaultAdapter()
            ?.getProfileProxy(context, avrcpServiceListener, PROFILE_AVRCP_CONTROLLER)

        // Check for a device that was already connected before we started
        checkInitialConnection()
    }

    fun unregister() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        avrcpProxy?.let {
            BluetoothAdapter.getDefaultAdapter()
                ?.closeProfileProxy(PROFILE_AVRCP_CONTROLLER, it)
        }
        avrcpProxy = null
    }

    // ── Transport control ──────────────────────────────────────────────────────

    /**
     * Sends an AVRCP PassThrough press+release to the connected A2DP source device.
     * Uses reflection to reach [BluetoothAvrcpController.sendPassThroughCmd] which is
     * hidden in the SDK but accessible to system-UID apps at runtime.
     */
    fun sendPassThrough(avrcpKeyCode: Int) {
        val device = connectedDevice ?: return
        val proxy  = avrcpProxy
        if (proxy != null) {
            dispatchPassThrough(proxy, device, avrcpKeyCode)
        } else {
            // Proxy not yet open — connect and dispatch in the callback
            BluetoothAdapter.getDefaultAdapter()?.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                        avrcpProxy = p
                        dispatchPassThrough(p, device, avrcpKeyCode)
                    }
                    override fun onServiceDisconnected(profile: Int) { avrcpProxy = null }
                },
                PROFILE_AVRCP_CONTROLLER
            )
        }
    }

    private fun dispatchPassThrough(proxy: BluetoothProfile, device: BluetoothDevice, keyCode: Int) {
        try {
            val m = proxy.javaClass.getMethod(
                "sendPassThroughCmd",
                BluetoothDevice::class.java, Int::class.java, Int::class.java
            )
            m.invoke(proxy, device, keyCode, KEY_STATE_PRESSED)
            m.invoke(proxy, device, keyCode, KEY_STATE_RELEASED)
        } catch (_: Exception) {
            // Hidden API unavailable on this build — no-op
        }
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    /** Query the A2DP sink profile for already-connected devices at startup. */
    private fun checkInitialConnection() {
        BluetoothAdapter.getDefaultAdapter()?.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val devices = proxy.connectedDevices
                    if (devices.isNotEmpty()) {
                        val device = devices.first()
                        isConnected        = true
                        connectedDevice    = device
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

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
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

        // AVRCP 1.3 PassThrough operation IDs (Bluetooth SIG spec §25.3)
        const val PASSTHRU_PLAY     = 0x44
        const val PASSTHRU_PAUSE    = 0x46
        const val PASSTHRU_STOP     = 0x45
        const val PASSTHRU_NEXT     = 0x4B
        const val PASSTHRU_PREVIOUS = 0x4C

        private const val KEY_STATE_PRESSED  = 0
        private const val KEY_STATE_RELEASED = 1

        // BluetoothProfile integer constants (public values, class sometimes hidden)
        private const val PROFILE_A2DP_SINK        = 11
        private const val PROFILE_AVRCP_CONTROLLER = 12
    }
}
