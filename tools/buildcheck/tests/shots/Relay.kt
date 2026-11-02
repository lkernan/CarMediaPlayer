package shots

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Stands between the app and a real server, like the car's connection: it
 * can break a reply part-way (a reset, as a lost network does), or stop
 * answering altogether ([goDown], [comeBack]).  One request per connection,
 * so each can be noted and broken on its own.
 */
class Relay(private val targetPort: Int) : AutoCloseable {

    class Exchange(val request: String) {
        val replyBytes = AtomicLong()
        @Volatile var broken = false
        /** The reply's status line, e.g. "HTTP/1.1 200 OK". */
        @Volatile var status = ""
        val path: String get() = request.substringAfter(' ').substringBefore(' ')
        val endpoint: String get() = path.substringAfter("/rest/").substringBefore('?')
        fun param(name: String): String? =
            path.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
        val range: String? get() = request.lines().firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
    }

    val exchanges = CopyOnWriteArrayList<Exchange>()
    /** For a request (and how many times its song has been asked for), the reply bytes to let through before breaking it, or null. */
    @Volatile var breakAfter: (exchange: Exchange, attempt: Int) -> Long? = { _, _ -> null }
    /** For a request, the speed to send its reply at (bytes a second), or null for as fast as it comes. */
    @Volatile var pace: (exchange: Exchange, attempt: Int) -> Int? = { _, _ -> null }

    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    private val open = CopyOnWriteArrayList<Socket>()
    @Volatile private var server = bind(0)
    val port = server.localPort

    fun streams(songId: String) = exchanges.filter { it.endpoint == "stream" && it.param("id") == songId }

    fun goDown() { server.close(); open.forEach(::reset) }
    fun comeBack() { server = bind(port) }
    override fun close() = goDown()

    private fun bind(port: Int): ServerSocket {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
        Thread {
            try {
                while (true) {
                    val client = s.accept()
                    open += client
                    Thread { try { relay(client) } catch (_: Exception) {} finally { open -= client; client.close() } }
                        .apply { isDaemon = true }.start()
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
        return s
    }

    private fun relay(client: Socket) {
        val head = readHead(client.getInputStream()) ?: return
        // One request per connection: the server closes after replying.
        val lines = head.split("\r\n").filter { it.isNotEmpty() && !it.startsWith("Connection:", true) }
        val request = (lines + "Connection: close").joinToString("\r\n") + "\r\n\r\n"
        val exchange = Exchange(lines.joinToString("\n"))
        exchanges += exchange
        val key = exchange.param("id")?.let { "${exchange.endpoint}:$it" } ?: exchange.endpoint
        val attempt = attempts.getOrPut(key) { AtomicInteger() }.incrementAndGet()
        val limit = breakAfter(exchange, attempt)
        val speed = pace(exchange, attempt)
        Socket("127.0.0.1", targetPort).use { upstream ->
            open += upstream
            try {
                upstream.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
                val from = upstream.getInputStream()
                val to = client.getOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val room = if (limit == null) buffer.size.toLong() else minOf(buffer.size.toLong(), limit - exchange.replyBytes.get())
                    if (room <= 0) { exchange.broken = true; reset(client); return }
                    val n = from.read(buffer, 0, room.toInt())
                    if (n < 0) break
                    to.write(buffer, 0, n)
                    if (exchange.replyBytes.get() == 0L) exchange.status = String(buffer, 0, n, Charsets.ISO_8859_1).substringBefore("\r\n")
                    exchange.replyBytes.addAndGet(n.toLong())
                    if (speed != null) { to.flush(); Thread.sleep(1_000L * n / speed) }
                }
                to.flush()
            } finally {
                open -= upstream
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return out.toString("ISO-8859-1").trimEnd()
        }
    }

    private fun reset(socket: Socket) {
        try { socket.setSoLinger(true, 0); socket.close() } catch (_: Exception) {}
    }
}
