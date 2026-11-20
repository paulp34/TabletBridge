package com.vetspantry.tabletbridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Callbacks into the rest of the app (implemented by [BridgeService]).
 */
interface BridgeSink {
    /** Handle a JSON command from a host; returns the JSON reply. */
    fun onCommand(cmd: JSONObject): JSONObject

    /** Called whenever the number of connected host sessions changes. */
    fun onSessionsChanged(count: Int)

    /** Read a bundled asset (the host web UI). */
    fun assetBytes(path: String): ByteArray?

    /** Server-level error (e.g. port busy). */
    fun onError(message: String)
}

/**
 * Minimal single-port server:
 *  - GET /            -> serves the host web UI from assets
 *  - GET /health      -> liveness probe
 *  - GET /ws?key=...  -> WebSocket for frames (server->client) and commands (client->server)
 *
 * Binary frames from server to client carry a 1-byte channel prefix:
 *   0x01 = camera JPEG, 0x02 = screen JPEG.
 * Text frames are plain JSON (events, command replies).
 */
class BridgeServer(
    private val port: Int,
    private val passcode: String,
    private val sink: BridgeSink
) : Thread("bridge-server") {

    companion object {
        private const val WS_MAGIC = "258EAFA9-E914-47DA-95CA-C5AB0DC85B11"
        const val CHANNEL_CAMERA = 1
        const val CHANNEL_SCREEN = 2
    }

    @Volatile private var running = true
    private var serverSocket: ServerSocket? = null
    private val sessions = CopyOnWriteArraySet<WsSession>()
    private val sessionCounter = AtomicInteger(0)

    override fun run() {
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            serverSocket = ss

            // Keep-alive pinger: detects dead connections and keeps adb forward alive.
            Thread({
                while (running) {
                    try {
                        Thread.sleep(25000)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                    sessions.forEach { it.sendPing() }
                }
            }, "bridge-pinger").apply { isDaemon = true; start() }

            while (running) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                socket.tcpNoDelay = true
                try {
                    handle(socket)
                } catch (e: Exception) {
                    closeQuietly(socket)
                }
            }
        } catch (e: Exception) {
            sink.onError("Could not start bridge server on port $port: $e")
        } finally {
            try {
                ss.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val requestLine = input.readLine() ?: return closeQuietly(socket)
        val parts = requestLine.split(" ")
        if (parts.size < 2) return closeQuietly(socket)
        val path = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        if (headers["upgrade"]?.lowercase() == "websocket") {
            val key = headers["sec-websocket-key"]
            if (key == null || queryParam(path, "key") != passcode) {
                respond(output, "401 Unauthorized", "text/plain", "unauthorized".toByteArray())
                closeQuietly(socket)
                return
            }
            startWebSocket(socket, input, output, key)
            return
        }

        when {
            path == "/" || path.startsWith("/index") -> {
                val bytes = sink.assetBytes("index.html")
                if (bytes == null) {
                    respond(output, "404 Not Found", "text/plain", "missing web UI".toByteArray())
                } else {
                    // Inject the passcode so the console auto-connects with no user input.
                    val html = String(bytes, Charsets.UTF_8)
                        .replace("__TB_KEY__", passcode)
                    respond(output, "200 OK", "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
                }
                closeQuietly(socket)
            }
            path.startsWith("/health") -> {
                respond(output, "200 OK", "text/plain", "ok".toByteArray())
                closeQuietly(socket)
            }
            path.startsWith("/diag") -> {
                val ok = queryParam(path, "key") == passcode
                if (ok) respond(output, "200 OK", "text/plain", "AUTH OK".toByteArray())
                else respond(output, "401 Unauthorized", "text/plain", "AUTH FAIL - passcode mismatch".toByteArray())
                closeQuietly(socket)
            }
            else -> {
                respond(output, "404 Not Found", "text/plain", "not found".toByteArray())
                closeQuietly(socket)
            }
        }
    }

    private fun startWebSocket(socket: Socket, input: DataInputStream, output: OutputStream, key: String) {
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_MAGIC).toByteArray(Charsets.UTF_8))
        )
        val handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(handshake.toByteArray(Charsets.US_ASCII))
        output.flush()

        val session = WsSession(sessionCounter.incrementAndGet(), socket, input, output)
        sessions.add(session)
        sink.onSessionsChanged(sessions.size)
        Thread({ session.readLoop() }, "ws-${session.id}").apply {
            isDaemon = true
            start()
        }
    }

    fun broadcastText(json: String) {
        sessions.forEach { it.sendText(json) }
    }

    fun broadcastBinary(channel: Int, payload: ByteArray) {
        sessions.forEach { it.sendBinary(channel, payload) }
    }

    fun clientCount(): Int = sessions.size

    fun shutdown() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        sessions.forEach { it.kill() }
    }

    // ---------------------------------------------------------------- internals

    private inner class WsSession(
        val id: Int,
        val socket: Socket,
        val input: DataInputStream,
        val output: OutputStream
    ) {
        private val writeLock = Any()
        @Volatile var alive = true
            private set

        fun sendText(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

        fun sendBinary(channel: Int, payload: ByteArray) {
            val data = ByteArray(payload.size + 1)
            data[0] = channel.toByte()
            payload.copyInto(data, 1)
            sendFrame(0x2, data)
        }

        fun sendPing() = sendFrame(0x9, ByteArray(0))

        private fun sendFrame(opcode: Int, payload: ByteArray) {
            if (!alive) return
            synchronized(writeLock) {
                try {
                    output.write(0x80 or opcode)
                    val len = payload.size
                    when {
                        len < 126 -> output.write(len)
                        len < 65536 -> {
                            output.write(126)
                            output.write(len ushr 8)
                            output.write(len and 0xFF)
                        }
                        else -> {
                            output.write(127)
                            var shift = 56
                            while (shift >= 0) {
                                output.write((len ushr shift) and 0xFF)
                                shift -= 8
                            }
                        }
                    }
                    output.write(payload)
                    output.flush()
                } catch (e: IOException) {
                    kill()
                }
            }
        }

        fun readLoop() {
            val acc = ByteArrayOutputStream()
            var accOpcode = 0
            try {
                while (alive) {
                    val b0 = input.readUnsignedByte()
                    val b1 = input.readUnsignedByte()
                    val fin = b0 and 0x80 != 0
                    val opcode = b0 and 0x0F
                    val masked = b1 and 0x80 != 0
                    var len = (b1 and 0x7F).toLong()
                    if (len == 126L) {
                        len = ((input.readUnsignedByte() shl 8) or input.readUnsignedByte()).toLong()
                    } else if (len == 127L) {
                        len = 0
                        repeat(8) { len = (len shl 8) or input.readUnsignedByte().toLong() }
                    }
                    if (len > 1_000_000) {
                        kill()
                        break
                    }
                    val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
                    val payload = ByteArray(len.toInt())
                    if (len > 0) input.readFully(payload)
                    if (mask != null) {
                        for (i in payload.indices) {
                            payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                        }
                    }

                    when (opcode) {
                        0x8 -> { // close
                            kill()
                            break
                        }
                        0x9 -> sendFrame(0xA, payload) // ping -> pong
                        0xA -> {} // pong
                        else -> {
                            if (opcode == 0x1 || opcode == 0x2) {
                                accOpcode = opcode
                                acc.reset()
                            }
                            acc.write(payload)
                            if (fin) {
                                val data = acc.toByteArray()
                                acc.reset()
                                if (accOpcode == 0x1) {
                                    val reply = try {
                                        sink.onCommand(JSONObject(String(data, Charsets.UTF_8)))
                                    } catch (e: Exception) {
                                        JSONObject()
                                            .put("type", "error")
                                            .put("message", "bad command: $e")
                                    }
                                    sendText(reply.toString())
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // connection dropped
            }
            kill()
        }

        fun kill() {
            if (alive) {
                alive = false
                closeQuietly(socket)
                sessions.remove(this)
                sink.onSessionsChanged(sessions.size)
            }
        }
    }

    private fun respond(output: OutputStream, status: String, contentType: String, body: ByteArray) {
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun queryParam(path: String, name: String): String? {
        val query = path.substringAfter('?', "")
        for (pair in query.split('&')) {
            val kv = pair.split('=', limit = 2)
            if (kv[0] == name) return URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
        }
        return null
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
