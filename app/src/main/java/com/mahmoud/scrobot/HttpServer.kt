package com.mahmoud.scrobot

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names are lower-cased. */
    val headers: Map<String, String>,
    val body: String,
)

class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
)

/**
 * Minimal HTTP/1.1 server: one request per connection, Content-Length bodies only.
 * Binds to the loopback address only, never to 0.0.0.0.
 * Has no Android dependencies so it can be tested on a plain JVM.
 */
class HttpServer(
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
    private val host: String = "127.0.0.1",
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (isRunning) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(host), port), 50)
        serverSocket = socket
        val pool = Executors.newFixedThreadPool(6)
        workers = pool
        acceptThread = Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break
                } catch (e: IOException) {
                    continue
                }
                try {
                    pool.execute { serve(client) }
                } catch (e: Exception) {
                    closeQuietly(client)
                }
            }
        }, "hands-http-accept").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        workers?.shutdownNow()
        workers?.awaitTermination(500, TimeUnit.MILLISECONDS)
        workers = null
        acceptThread = null
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val response = try {
                val request = readRequest(input, output)
                if (request == null) null else handler(request)
            } catch (e: BadRequest) {
                textResponse(e.status, e.message ?: "bad request")
            }
            if (response != null) writeResponse(output, response)
        } catch (_: Exception) {
        } finally {
            closeQuietly(client)
        }
    }

    private class BadRequest(val status: Int, message: String) : Exception(message)

    private fun readRequest(input: InputStream, output: OutputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) throw BadRequest(400, "bad request line")
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            if (headers.size > 100) throw BadRequest(431, "too many headers")
        }

        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            throw BadRequest(411, "chunked bodies are not supported, send Content-Length")
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) throw BadRequest(413, "body too large")
        if (length > 0 && headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw BadRequest(400, "truncated body")
            read += n
        }

        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
        return HttpRequest(method, urlDecode(path), query, headers, String(body, Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 16 * 1024) throw BadRequest(431, "line too long")
        }
        return buf.toString("ISO-8859-1")
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            .append("Content-Type: ").append(response.contentType).append("\r\n")
            .append("Content-Length: ").append(response.body.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }

    companion object {
        const val MAX_BODY = 8 * 1024 * 1024

        fun textResponse(status: Int, text: String) =
            HttpResponse(status, "text/plain; charset=utf-8", (text + "\n").toByteArray(Charsets.UTF_8))

        fun parseQuery(raw: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) out[urlDecode(pair)] = "1"
                else out[urlDecode(pair.substring(0, eq))] = urlDecode(pair.substring(eq + 1))
            }
            return out
        }

        private fun urlDecode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Status"
        }

        private fun closeQuietly(socket: Socket) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }
}
