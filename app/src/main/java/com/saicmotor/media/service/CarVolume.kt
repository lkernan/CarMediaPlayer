package com.saicmotor.media.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/** Turns the car's volume a step (a stand-in in tests). */
internal fun interface VolumeStepper {
    /** One step up or down.  False if it couldn't be done.  Blocking. */
    fun step(up: Boolean): Boolean
}

/**
 * Turns the car's volume up or down a step, the way the launcher does for the
 * steering wheel's volume keys: through the head unit's CarAdapterService
 * (its audio client's volumeUp / volumeDown, with the hard-key flag).  That
 * changes whichever volume is in use (music, a phone call, navigation) and
 * shows the volume bar, just like a press the launcher acted on.
 *
 * The calls are the ones in the launcher's copy of SAIC's car API
 * (com.saicmotor.carapi): CarAdapterClient binds to the service, then
 * ICarAdapterService.queryClient(10) gives ICarAudioService.
 */
internal class CarVolume(private val context: Context) : VolumeStepper {

    @Volatile private var adapter: IBinder? = null
    @Volatile private var audio: IBinder? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            adapter = service
            audio = null
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            adapter = null
            audio = null
        }
    }

    /** Binds to the car's service, ready for the first [step]. */
    fun connect() {
        if (bound) return
        bound = try {
            context.bindService(Intent(ACTION).setPackage(PACKAGE), connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            false
        }
        if (!bound) Log.w(TAG, "The car's audio service isn't there")
    }

    fun disconnect() {
        if (bound) try { context.unbindService(connection) } catch (_: Exception) {}
        bound = false
        adapter = null
        audio = null
    }

    override fun step(up: Boolean): Boolean {
        val client = audioClient() ?: return false
        val done = call(client, AUDIO_DESCRIPTOR, if (up) VOLUME_UP else VOLUME_DOWN, FLAG_HARD_KEY) { true }
        if (done == null) audio = null
        return done == true
    }

    private fun audioClient(): IBinder? {
        audio?.takeIf { it.isBinderAlive }?.let { return it }
        val service = adapter ?: return null
        return call(service, ADAPTER_DESCRIPTOR, QUERY_CLIENT, CLIENT_AUDIO) { it.readStrongBinder() }
            .also { audio = it }
    }

    /** One call to [binder] with a single int argument; what [read] makes of the reply, or null if it failed. */
    private fun <T> call(binder: IBinder, descriptor: String, code: Int, arg: Int, read: (Parcel) -> T?): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeInt(arg)
            if (!binder.transact(code, data, reply, 0)) return null
            reply.readException()
            read(reply)
        } catch (e: Exception) {
            Log.w(TAG, "Car audio call $code failed: ${e.javaClass.simpleName}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val TAG = "CarMedia.Keys"
        const val PACKAGE = "com.saicmotor.caradapter"
        const val ACTION  = "com.saicmotor.caradapter.CarAdapterService"
        const val ADAPTER_DESCRIPTOR = "com.saicmotor.carapi.ICarAdapterService"
        const val QUERY_CLIENT = 1          // ICarAdapterService.queryClient(int)
        const val CLIENT_AUDIO = 10         // → CarAudioHelper (ICarAudioService)
        const val AUDIO_DESCRIPTOR = "com.saicmotor.carapi.audio.ICarAudioService"
        const val VOLUME_UP   = 42          // ICarAudioService.volumeUp(int flags)
        const val VOLUME_DOWN = 43          // ICarAudioService.volumeDown(int flags)
        const val FLAG_HARD_KEY = 1024      // what the launcher passes: shows the volume bar
    }
}
