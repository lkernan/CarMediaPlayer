package shots

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * A made-up Navidrome for the tests, answering the way the real one does:
 *
 *  - Songs come transcoded to Ogg Opus, sent as they're made: chunked, no
 *    length, ranges ignored (every request is a fresh transcode from the
 *    start, the same bytes but a new Ogg serial number).
 *  - With [transcodeCached] (Navidrome's transcoding cache), a song comes
 *    whole: with its length, and ranges work.
 *  - Errors come as HTTP 200 with a JSON body, like every Subsonic error.
 *
 * Each request for a song can be made to go wrong ([plan]): the connection
 * cut part-way, a stall, a short song, an HTTP error, a Subsonic error.
 * The server can be switched off and on again ([goDown], [comeBack]).
 */
class FakeNavidrome : AutoCloseable {

    class Song(val id: String, val title: String, val seconds: Int, index: Int) {
        /** The song as ffmpeg makes it (before its serial number). */
        val opus: ByteArray = OggOpus.song(seconds, songNumber = index)
    }

    /** How one request for a song is answered. */
    sealed class Reply {
        /** The whole song. */
        object Whole : Reply() { override fun toString() = "Whole" }
        /** The connection breaks after this many bytes of the song (a reset, no clean end). */
        data class CutAfter(val bytes: Int) : Reply()
        /** Sending stops for [ms] after this many bytes, then carries on. */
        data class StallAfter(val bytes: Int, val ms: Long) : Reply()
        /** The song ends cleanly but early (ffmpeg died part-way). */
        data class EndAfter(val bytes: Int) : Reply()
        /** An HTTP error, e.g. 500 or 502 from the proxy in front. */
        data class Status(val code: Int) : Reply()
        /** A Subsonic error (HTTP 200, JSON body), e.g. 70 not found, 40 wrong password. */
        data class SubsonicError(val code: Int, val message: String) : Reply()
    }

    data class Request(val endpoint: String, val params: Map<String, String>, val range: String?) {
        /** Bytes of song sent in reply. */
        val sent = java.util.concurrent.atomic.AtomicLong()
        var reply: String = ""
    }

    val songs = LinkedHashMap<String, Song>()
    val requests = CopyOnWriteArrayList<Request>()
    @Volatile var transcodeCached = false
    /** 0 = as fast as it can; otherwise a slow link, shared by every connection (like one mobile signal). */
    @Volatile var bytesPerSecond = 0
    private val link = Any()
    private var linkFreeAt = 0L
    /** How each request for a song is answered, by the song's place (0, 1, …) and how many times it's been asked for (1, 2, …). */
    @Volatile var plan: (song: Int, attempt: Int) -> Reply = { _, _ -> Reply.Whole }

    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    private val open = CopyOnWriteArrayList<Socket>()
    @Volatile private var server: ServerSocket = bind(0)
    val port = server.localPort
    val url get() = "http://127.0.0.1:$port"

    /** Ids are new each time: the song cache outlives a test. */
    fun addSongs(count: Int, seconds: Int, prefix: String = "t${System.nanoTime() % 1_000_000}s"): List<Song> =
        (0 until count).map { i ->
            Song("$prefix$i", "Song $i", seconds, songs.size).also { songs[it.id] = it }
        }

    fun streamRequests(id: String) = requests.filter { it.endpoint == "stream" && it.params["id"] == id }
    fun scrobbles(submission: Boolean) = requests.filter {
        it.endpoint == "scrobble" && it.params["submission"] == submission.toString()
    }.map { it.params["id"] }

    /** No server: connections are refused (no signal, or the server's down). */
    fun goDown() {
        server.close()
        open.forEach { reset(it) }
    }

    fun comeBack() { server = bind(port) }

    override fun close() = goDown()

    // ── HTTP ────────────────────────────────────────────────────────────

    private fun bind(port: Int): ServerSocket {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
        Thread {
            try {
                while (true) {
                    val socket = s.accept()
                    open += socket
                    Thread { try { serve(socket) } catch (_: Exception) {} finally { open -= socket; socket.close() } }
                        .apply { isDaemon = true }.start()
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
        return s
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        var range: String? = null
        var line = input.readLine()
        while (!line.isNullOrEmpty()) {
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            line = input.readLine()
        }
        val target = requestLine.split(' ')[1]
        val endpoint = target.substringAfter("/rest/").substringBefore('?').removeSuffix(".view")
        val params = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val request = Request(endpoint, params, range)
        requests += request
        val out = socket.getOutputStream()
        when (endpoint) {
            "stream" -> stream(socket, out, params["id"].orEmpty(), range, request)
            "ping", "scrobble" -> json(out, "")
            "getSong" -> songs[params["id"]]?.let { json(out, "\"song\":${songJson(it)}") }
                ?: json(out, error(70, "Song not found"))
            "getRandomSongs" -> json(out, "\"randomSongs\":{\"song\":[${songs.values.joinToString(",") { songJson(it) }}]}")
            "getAlbum" -> json(out, "\"album\":{\"id\":\"al1\",\"name\":\"Album\",\"artist\":\"Artist\",\"song\":[" +
                songs.values.joinToString(",") { songJson(it) } + "]}")
            else -> json(out, error(70, "Not here"))
        }
    }

    private fun songJson(s: Song) =
        """{"id":"${s.id}","title":"${s.title}","artist":"Artist","album":"Album","albumId":"al1","artistId":"ar1","duration":${s.seconds}}"""

    private fun error(code: Int, message: String) = "\"error\":{\"code\":$code,\"message\":\"$message\"}"

    private fun json(out: OutputStream, body: String) {
        val ok = !body.startsWith("\"error\"")
        val text = """{"subsonic-response":{"status":"${if (ok) "ok" else "failed"}","version":"1.16.1","type":"navidrome","serverVersion":"0.64.2","openSubsonic":true${if (body.isEmpty()) "" else ",$body"}}}"""
        val bytes = text.toByteArray()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun stream(socket: Socket, out: OutputStream, id: String, range: String?, request: Request) {
        val song = songs[id] ?: return json(out, error(70, "Song not found"))
        val attempt = attempts.getOrPut(id) { AtomicInteger() }.incrementAndGet()
        val reply = plan(songs.keys.indexOf(id), attempt)
        request.reply = reply.toString()
        when (reply) {
            is Reply.Status -> {
                out.write("HTTP/1.1 ${reply.code} Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return out.flush()
            }
            is Reply.SubsonicError -> return json(out, error(reply.code, reply.message))
            else -> {}
        }
        if (transcodeCached) {
            // From Navidrome's transcoding cache: the file it made the first time.
            val bytes = OggOpus.withSerial(song.opus, 0x5EED)
            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()?.coerceAtMost(bytes.size) ?: 0
            val to = range?.substringAfter('-', "")?.toIntOrNull()?.plus(1)?.coerceAtMost(bytes.size) ?: bytes.size
            val head = if (range != null) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${to - 1}/${bytes.size}\r\n"
                       else "HTTP/1.1 200 OK\r\n"
            out.write((head + "Content-Type: audio/ogg\r\nAccept-Ranges: bytes\r\nContent-Length: ${to - from}\r\nConnection: close\r\n\r\n").toByteArray())
            send(socket, out, bytes.copyOf(to), from, reply, chunked = false, request)
        } else {
            // Transcoded as it goes: from the start whatever was asked for.
            val bytes = OggOpus.withSerial(song.opus, Random.nextInt())
            out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/ogg\r\nAccept-Ranges: none\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
            send(socket, out, bytes, 0, reply, chunked = true, request)
        }
    }

    private fun send(socket: Socket, out: OutputStream, bytes: ByteArray, from: Int, reply: Reply, chunked: Boolean, request: Request) {
        var at = from
        var sent = 0
        val piece = 4096
        var stalled = false
        while (at < bytes.size) {
            when (reply) {
                is Reply.CutAfter -> if (sent >= reply.bytes) return reset(socket)
                is Reply.EndAfter -> if (sent >= reply.bytes) break
                is Reply.StallAfter -> if (sent >= reply.bytes && !stalled) { stalled = true; out.flush(); Thread.sleep(reply.ms) }
                else -> {}
            }
            val limit = when (reply) {
                is Reply.CutAfter -> reply.bytes - sent
                is Reply.EndAfter -> reply.bytes - sent
                is Reply.StallAfter -> if (!stalled) reply.bytes - sent else piece
                else -> piece
            }
            val n = minOf(piece, bytes.size - at, limit.coerceAtLeast(1))
            if (chunked) out.write("${Integer.toHexString(n)}\r\n".toByteArray())
            out.write(bytes, at, n)
            if (chunked) out.write("\r\n".toByteArray())
            at += n; sent += n
            request.sent.addAndGet(n.toLong())
            if (bytesPerSecond > 0) {
                out.flush()
                val wait = synchronized(link) {
                    val now = System.nanoTime()
                    linkFreeAt = maxOf(linkFreeAt, now) + 1_000_000_000L * n / bytesPerSecond
                    (linkFreeAt - now) / 1_000_000
                }
                Thread.sleep(wait)
            }
        }
        if (chunked) out.write("0\r\n\r\n".toByteArray())
        out.flush()
    }

    /** Breaks the connection the way a lost network does: a reset, not a clean end. */
    private fun reset(socket: Socket) {
        try { socket.setSoLinger(true, 0); socket.close() } catch (_: Exception) {}
    }
}

/**
 * Ogg Opus songs like ffmpeg's (Navidrome's transcoder): 20 ms packets of
 * 320 bytes (128 kbit/s), a page per second.  Each packet carries its song
 * and packet number, so a test can tell exactly what was played.
 */
object OggOpus {
    const val PACKET_MS = 20
    private const val PACKET_BYTES = 320
    private const val PACKETS_PER_PAGE = 50
    private const val PRE_SKIP = 312

    fun song(seconds: Int, songNumber: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val le = { n: Int -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array() }
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, (PRE_SKIP and 0xFF).toByte(), (PRE_SKIP shr 8).toByte()) +
            le(48_000) + byteArrayOf(0, 0, 0)
        val tags = "OpusTags".toByteArray() + le(13) + "Lavf60.16.100".toByteArray() + le(0)
        var seq = 0
        out.write(page(2, 0, seq++, listOf(head)))
        out.write(page(0, 0, seq++, listOf(tags)))
        val packets = seconds * 1000 / PACKET_MS
        val random = Random(songNumber)
        var p = 0
        while (p < packets) {
            val count = minOf(PACKETS_PER_PAGE, packets - p)
            val list = (0 until count).map { k -> packet(songNumber, p + k, random) }
            p += count
            out.write(page(if (p == packets) 4 else 0, PRE_SKIP + 960L * p, seq++, list))
        }
        return out.toByteArray()
    }

    /** The song number and packet number in a packet, or null if it isn't one of ours. */
    fun label(packet: ByteBuffer): Pair<Int, Int>? {
        if (packet.remaining() < 9 || packet.get(packet.position()) != 0xFC.toByte()) return null
        val b = packet.duplicate().order(ByteOrder.BIG_ENDIAN)
        b.get()
        return b.int to b.int
    }

    private fun packet(song: Int, index: Int, random: Random): ByteArray {
        val p = ByteArray(PACKET_BYTES).also { random.nextBytes(it) }
        // TOC: CELT full band, 20 ms, stereo, one frame.
        ByteBuffer.wrap(p).put(0xFC.toByte()).putInt(song).putInt(index)
        return p
    }

    private fun page(type: Int, granule: Long, seq: Int, packets: List<ByteArray>): ByteArray {
        val lacing = ByteArrayOutputStream()
        for (p in packets) {
            var left = p.size
            while (left >= 255) { lacing.write(255); left -= 255 }
            lacing.write(left)
        }
        val head = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OggS".toByteArray()).put(0).put(type.toByte()).putLong(granule)
            .putInt(0).putInt(seq).putInt(0).put(lacing.size().toByte())
        val out = ByteArrayOutputStream()
        out.write(head.array()); out.write(lacing.toByteArray()); packets.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** The song with another serial number (and the checksums to match), as a new transcode would be. */
    fun withSerial(song: ByteArray, serial: Int): ByteArray {
        val b = song.copyOf()
        var i = 0
        while (i + 27 <= b.size) {
            val segments = b[i + 26].toInt() and 0xFF
            var length = 27 + segments
            for (k in 0 until segments) length += b[i + 27 + k].toInt() and 0xFF
            ByteBuffer.wrap(b, i + 14, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(serial)
            ByteBuffer.wrap(b, i + 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0)
            ByteBuffer.wrap(b, i + 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc(b, i, length))
            i += length
        }
        return b
    }

    private val table = IntArray(256) { n ->
        var r = n shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    private fun crc(b: ByteArray, from: Int, length: Int): Int {
        var c = 0
        for (k in from until from + length) c = (c shl 8) xor table[((c ushr 24) xor (b[k].toInt() and 0xFF)) and 0xFF]
        return c
    }
}
