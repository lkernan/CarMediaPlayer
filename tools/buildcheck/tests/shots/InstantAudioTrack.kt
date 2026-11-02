package shots

import android.media.AudioTrack
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAudioTrack
import java.nio.ByteBuffer

/**
 * An AudioTrack that has played everything the moment it's written.
 * Robolectric's own works out how far it has played with a method Android 9
 * doesn't have (AudioFormat.getFrameSizeInBytes), which stops playback dead.
 */
@Implements(AudioTrack::class)
class InstantAudioTrack : ShadowAudioTrack() {

    @field:RealObject
    private lateinit var track: AudioTrack
    private var bytesWritten = 0L

    @Implementation
    override fun write(buffer: ByteBuffer, size: Int, writeMode: Int): Int =
        super.write(buffer, size, writeMode).also { if (it > 0) bytesWritten += it }

    @Implementation
    override fun getPlaybackHeadPosition(): Int = (bytesWritten / (track.channelCount * 2)).toInt()

    @Implementation
    override fun flush() {
        super.flush()
        bytesWritten = 0
    }
}
