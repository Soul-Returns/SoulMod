package com.soulreturns.devbridge

import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

/**
 * Minimal HTTP/1.1 server on 127.0.0.1 (raw sockets: the game may run on a JRE without
 * `jdk.httpserver`). Every request needs `Authorization: Bearer <token>`, GET only. References
 * nothing from Soul itself: while the bridge is disabled it serves only [status]; [routes]
 * (set when enabled) handles the rest. No route can make the game do anything.
 */
class BridgeServer(port: Int, private val token: String, private val status: () -> String) {
    private val logger = LoggerFactory.getLogger("Soul/DevBridge")
    private val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16) }
    val port: Int = socket.localPort
    private val ids = AtomicInteger()

    /** Returns false for an unknown path. */
    @Volatile
    var routes: ((Request, Socket, OutputStream) -> Boolean)? = null

    fun start() {
        Thread({ acceptLoop() }, "Soul devbridge accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val client =
                try {
                    socket.accept()
                } catch (_: IOException) {
                    return
                }
            Thread({ serve(client) }, "Soul devbridge conn ${ids.incrementAndGet()}").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = 10_000
                val req = readRequest(s.getInputStream()) ?: return
                s.soTimeout = 0
                val out = BufferedOutputStream(s.getOutputStream())
                if (!authorized(req)) return respond(out, 401, "unauthorized")
                if (req.method != "GET") return respond(out, 405, "GET only")
                if (req.path == "/status") return respond(out, 200, status(), "application/json")
                val r = routes ?: return respond(out, 503, "bridge disabled: see /status")
                if (!r(req, s, out)) {
                    respond(out, 404, "routes: /status /events /snapshot /providers /record/start /record/stop /record/status /mark /screenshot /world/start /world/stop /world/status")
                }
            } catch (_: IOException) {
                // Client went away.
            } catch (e: LinkageError) {
                Gate.fail(e)
            } catch (t: Throwable) {
                logger.warn("[Soul/DevBridge] request failed: $t")
            }
        }
    }

    private fun authorized(req: Request): Boolean {
        val given = req.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    }

    private fun readRequest(input: InputStream): Request? {
        val buf = StringBuilder()
        while (!buf.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0 || buf.length > 8192) return null
            buf.append(b.toChar())
        }
        val lines = buf.split("\r\n")
        val parts = lines[0].split(' ')
        if (parts.size < 2) return null
        val target = parts[1]
        val path = target.substringBefore('?')
        val query =
            target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to
                    URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
            }
        val headers =
            lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return Request(parts[0], path, query, headers)
    }

    companion object {
        fun respond(out: OutputStream, status: Int, body: String, type: String = "text/plain") {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val reason = if (status == 200) "OK" else if (status == 401) "Unauthorized" else if (status == 404) "Not Found" else "Error"
            out.write(
                ("HTTP/1.1 $status $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
            )
            out.write(bytes)
            out.flush()
        }

        fun startStream(out: OutputStream) {
            out.write(
                "HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII),
            )
        }

        fun line(text: String): ByteArray = (text + "\n").toByteArray(StandardCharsets.UTF_8)

        /** Interrupts [writer] once the peer hangs up (the client never sends after the request). */
        fun watchEof(input: InputStream, writer: Thread) {
            Thread({
                try {
                    while (input.read() >= 0) Unit
                } catch (_: IOException) {
                }
                writer.interrupt()
            }, "${writer.name} eof").apply { isDaemon = true }.start()
        }
    }
}
