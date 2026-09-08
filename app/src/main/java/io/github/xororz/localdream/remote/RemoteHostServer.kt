package io.github.xororz.localdream.remote

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Minimal blocking HTTP/1.1 server backing host mode's control API.
 *
 * Scope is deliberately tiny: small JSON requests and responses on a handful
 * of fixed routes, one response per connection (Connection: close). Anything
 * heavy (generation SSE, tokenize, upscale) is served by the native backend
 * on its own port, never through here.
 *
 * When [authToken] is set, every route except /info (discovery) requires the
 * "Authorization: Bearer <token>" header with the pairing code the host
 * displays; the native generation port enforces the same token, so gating
 * only the control API here would still leave the LAN-facing engine open.
 */
class RemoteHostServer(
    private val port: Int,
    private val handler: Handler,
    private val authToken: String? = null,
) {
    interface Handler {
        fun info(): JSONObject
        fun models(): JSONObject
        fun select(body: JSONObject): Response
        fun status(): JSONObject
        fun stop(body: JSONObject): JSONObject
    }

    data class Response(val code: Int, val body: JSONObject)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    @Throws(IOException::class)
    fun start() {
        val socket = ServerSocket(port)
        serverSocket = socket
        running = true
        workers = Executors.newFixedThreadPool(WORKER_COUNT) { r ->
            Thread(r, "remote-host-worker")
        }
        acceptThread = Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                workers?.execute { serve(client) }
            }
        }, "remote-host-accept").apply { start() }
        Log.i(TAG, "control server listening on $port")
    }

    fun shutdown() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        workers?.shutdownNow()
        workers = null
        acceptThread = null
        Log.i(TAG, "control server stopped")
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val request = parseRequest(input)
                val response = if (request == null) {
                    Response(400, errorBody("bad request"))
                } else {
                    route(request)
                }
                writeResponse(socket, response)
            }
        } catch (e: Exception) {
            Log.w(TAG, "request handling failed: ${e.message}")
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val body: JSONObject,
        val authHeader: String? = null,
    )

    private fun parseRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        // Strip any query string; routes don't use one.
        val path = parts[1].substringBefore('?')

        var contentLength = 0
        var authHeader: String? = null
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':').trim()
            if (name == "content-length") {
                contentLength = value.toIntOrNull() ?: 0
            } else if (name == "authorization") {
                authHeader = value
            }
        }
        if (contentLength > MAX_BODY_BYTES) return null

        val body = if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bytes, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            try {
                JSONObject(String(bytes, 0, read, StandardCharsets.UTF_8))
            } catch (_: Exception) {
                // A malformed body must not masquerade as an empty one (an
                // empty body is legitimate for /stop); null makes serve()
                // answer 400 "bad request" instead of routing garbage into
                // the handlers.
                return null
            }
        } else {
            JSONObject()
        }
        return Request(method, path, body, authHeader)
    }

    // Reads one CRLF/LF-terminated header line as ISO-8859-1 (headers are
    // ASCII here; bodies are decoded separately as UTF-8).
    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) buffer.append(c.toChar())
            if (buffer.length > MAX_LINE_LENGTH) return null
        }
        return buffer.toString()
    }

    private fun route(request: Request): Response {
        if (request.path == RemoteProtocol.PATH_INFO) {
            return Response(200, handler.info())
        }
        // Pairing token gate: /info stays open for discovery, everything
        // else requires the code shown on the host device.
        val expected = authToken?.let { RemoteProtocol.bearer(it) }
        if (expected != null && request.authHeader != expected) {
            return Response(401, errorBody("unauthorized"))
        }
        return when {
            request.method == "GET" && request.path == RemoteProtocol.PATH_MODELS ->
                Response(200, handler.models())

            request.method == "POST" && request.path == RemoteProtocol.PATH_SELECT ->
                handler.select(request.body)

            request.method == "GET" && request.path == RemoteProtocol.PATH_STATUS ->
                Response(200, handler.status())

            request.method == "POST" && request.path == RemoteProtocol.PATH_STOP ->
                Response(200, handler.stop(request.body))

            else -> Response(404, errorBody("not found"))
        }
    }

    private fun writeResponse(socket: Socket, response: Response) {
        val body = response.body.toString().toByteArray(StandardCharsets.UTF_8)
        val reason = when (response.code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 ${response.code} $reason\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val output = socket.getOutputStream()
        output.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun errorBody(message: String): JSONObject = JSONObject().put("error", message)

    companion object {
        private const val TAG = "RemoteHostServer"

        // Single worker on purpose: select/stop must be processed in arrival
        // order (a reordered late stop could kill a newer selection), and
        // every route is a fast, small JSON exchange.
        private const val WORKER_COUNT = 1
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_LINE_LENGTH = 8 * 1024
    }
}
