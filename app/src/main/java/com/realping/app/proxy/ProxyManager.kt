package com.realping.app.proxy

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.*
import java.net.*
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ProxyManager(
    private val port: Int = 8080
) {
    fun setProtector(protector: ((Int) -> Boolean)?) {
        protectFd = protector
    }

    fun isRunning(): Boolean = running.get()

    /** اطلاعات فعلی bind برای نمایش در UI */
    fun currentBindInfo(): String? = boundHost?.hostAddress?.let { "$it:$boundPort" }

    /**
     * راه‌اندازی پراکسی. روی خطا کرش نمی‌دهد و false برمی‌گرداند.
     * اگر پورت مشغول باشد و اتصال به آن برقرار شود، فرض می‌کنیم نمونهٔ دیگری درحال اجراست و true برمی‌گردانیم.
     */
    @Synchronized
    fun start(bindHost: InetAddress? = null): Boolean {
        if (running.get()) return true

        // استخر ترد Daemon (تا عمر پردازش را نگه ندارد)
        workerPool = Executors.newCachedThreadPool { r ->
            Thread(r, "rp-worker").apply { isDaemon = true }
        }

        val host = bindHost ?: InetAddress.getByName("0.0.0.0")
        try {
            val srv = ServerSocket()
            srv.reuseAddress = true
            srv.bind(InetSocketAddress(host, port), /*backlog=*/50)
            server = srv
            boundHost = host
            boundPort = port
            running.set(true)
            Log.i(TAG, "Proxy started on ${host.hostAddress}:$port")
        } catch (t: Throwable) {
            // اگر پورت مشغول است، بررسی کن آیا چیزی (احتمالاً نمونه قبلی) گوش می‌دهد؟
            val inUse =
                (t is BindException) || (t.message?.contains("EADDRINUSE", ignoreCase = true) == true)
            if (inUse) {
                val reachable = try {
                    Socket().use { s ->
                        s.soTimeout = 1000
                        s.connect(InetSocketAddress(host, port), 800)
                    }
                    true
                } catch (_: Throwable) { false }

                if (reachable) {
                    // فرض می‌کنیم پراکسی قبلی درحال اجراست؛ state را هم‌راستا می‌کنیم
                    running.set(true)
                    boundHost = host
                    boundPort = port
                    Log.w(TAG, "Port in use on ${host.hostAddress}:$port → assuming proxy already running.")
                    return true
                }
            }

            Log.e(TAG, "Failed to bind ${host.hostAddress}:$port → ${t.message}")
            // clean up
            try { server?.close() } catch (_: Throwable) {}
            server = null
            workerPool?.shutdownNow()
            workerPool = null
            running.set(false)
            return false
        }

        acceptThread = thread(name = "rp-accept", isDaemon = true) {
            try {
                while (running.get()) {
                    val srv = server ?: break
                    val client = try {
                        srv.accept()
                    } catch (t: Throwable) {
                        if (running.get()) Log.w(TAG, "Accept failed", t)
                        break
                    }

                    val pool = workerPool
                    if (pool == null || pool.isShutdown || pool.isTerminated) {
                        try { client.close() } catch (_: Throwable) {}
                        Log.w(TAG, "Worker pool not available; stopping accept loop")
                        break
                    }
                    try {
                        pool.execute { handleClient(client) }
                    } catch (_: RejectedExecutionException) {
                        try { client.close() } catch (_: Throwable) {}
                        break
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "Accept loop error", t)
                else Log.w(TAG, "Accept loop ended: ${t.message}")
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return

        try { server?.close() } catch (_: Throwable) {}
        server = null

        acceptThread?.interrupt()
        acceptThread = null

        workerPool?.shutdownNow()
        workerPool = null

        boundHost = null
        Log.i(TAG, "Proxy stopped")
    }

    // ------------------- client handling -------------------

    private fun handleClient(client: Socket) {
        client.soTimeout = 15000
        val cin = BufferedInputStream(client.getInputStream())
        val cout = BufferedOutputStream(client.getOutputStream())
        try {
            val headerBytes = readHttpHeader(cin) ?: run { client.safeClose(); return }
            val headerStr = headerBytes.toString(UTF8)
            val firstLine = headerStr.lineSequence().firstOrNull()?.trim().orEmpty()
            val (method, uri, _) = parseRequestLine(firstLine) ?: run {
                writeBadRequest(cout); client.safeClose(); return
            }

            if (method.equals("CONNECT", true)) {
                val (host, port) = parseHostPortFromConnect(uri) ?: run {
                    writeBadRequest(cout); client.safeClose(); return
                }
                handleConnect(client, cin, cout, host, port)
            } else {
                val hostHeader = headerStr.lines()
                    .firstOrNull { it.lowercase(Locale.US).startsWith("host:") }
                    ?.substringAfter(':')?.trim()
                val (host, port, path) = parseHttpTarget(method, uri, hostHeader) ?: run {
                    writeBadRequest(cout); client.safeClose(); return
                }
                handleHttpForward(client, headerBytes, cin, cout, host, port, method, path)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Client error: ${t.message}")
        } finally {
            client.safeClose()
        }
    }

    private fun handleConnect(
        client: Socket,
        cin: InputStream,
        cout: OutputStream,
        host: String,
        port: Int
    ) {
        val upstream = Socket()
        try {
            protectIfPossible(upstream)
            upstream.soTimeout = 20000
            upstream.connect(InetSocketAddress(host, port), 10000)

            cout.write(b("HTTP/1.1 200 Connection Established\r\n\r\n"))
            cout.flush()

            val uin = BufferedInputStream(upstream.getInputStream())
            val uout = BufferedOutputStream(upstream.getOutputStream())
            pumpBothWays(cin, cout, uin, uout)
        } catch (t: Throwable) {
            Log.w(TAG, "CONNECT to $host:$port failed: ${t.message}")
            try {
                cout.write(b("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
                cout.flush()
            } catch (_: Throwable) {}
        } finally {
            upstream.safeClose()
        }
    }

    private fun handleHttpForward(
        client: Socket,
        firstHeader: ByteArray,
        cin: InputStream,
        cout: OutputStream,
        host: String,
        port: Int,
        method: String,
        path: String
    ) {
        val upstream = Socket()
        try {
            protectIfPossible(upstream)
            upstream.soTimeout = 20000
            upstream.connect(InetSocketAddress(host, port), 10000)

            val uin = BufferedInputStream(upstream.getInputStream())
            val uout = BufferedOutputStream(upstream.getOutputStream())

            val rewritten = rewriteRequestToOriginForm(firstHeader, method, path)
            uout.write(rewritten)
            uout.flush()

            pumpBothWays(cin, cout, uin, uout)
        } catch (t: Throwable) {
            Log.w(TAG, "HTTP forward $host:$port failed: ${t.message}")
            try {
                cout.write(b("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
                cout.flush()
            } catch (_: Throwable) {}
        } finally {
            upstream.safeClose()
        }
    }

    // ------------------- helpers -------------------

    private fun protectIfPossible(sock: Socket) {
        try {
            val p = protectFd ?: return
            val pfd = ParcelFileDescriptor.fromSocket(sock)
            val fd = pfd.fd
            val ok = p(fd)
            try { pfd.close() } catch (_: Throwable) {}
            if (!ok) Log.w(TAG, "protect(fd=$fd) returned false")
        } catch (t: Throwable) {
            Log.w(TAG, "protect failed: ${t.message}")
        }
    }

    private fun pumpBothWays(
        cin: InputStream,
        cout: OutputStream,
        uin: InputStream,
        uout: OutputStream
    ) {
        val t1 = thread(name = "rp-client->up", isDaemon = true) {
            pipe(cin, uout)
            try { uout.flush() } catch (_: Throwable) {}
            try { uout.close() } catch (_: Throwable) {}
        }
        val t2 = thread(name = "rp-up->client", isDaemon = true) {
            pipe(uin, cout)
            try { cout.flush() } catch (_: Throwable) {}
            try { cout.close() } catch (_: Throwable) {}
        }
        try { t1.join(); t2.join() } catch (_: Throwable) {}
    }

    private fun pipe(`in`: InputStream, out: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            var wrote = false
            while (true) {
                val n = `in`.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                wrote = true
            }
            if (wrote) {
                try { out.flush() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) { /* normal */ }
    }

    private fun readHttpHeader(`in`: InputStream): ByteArray? {
        val baos = ByteArrayOutputStream(4096)
        var s3 = 0; var s2 = 0; var s1 = 0; var s0 = 0
        while (true) {
            val b = `in`.read()
            if (b == -1) break
            baos.write(b)
            s3 = s2; s2 = s1; s1 = s0; s0 = b and 0xFF
            if (s3 == 0x0D && s2 == 0x0A && s1 == 0x0D && s0 == 0x0A) break
            if (baos.size() > 64 * 1024) return null
        }
        return if (baos.size() == 0) null else baos.toByteArray()
    }

    private fun parseRequestLine(line: String): Triple<String, String, String>? {
        val parts = line.split(' ')
        if (parts.size < 3) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    private fun parseHostPortFromConnect(target: String): Pair<String, Int>? {
        val idx = target.lastIndexOf(':')
        if (idx <= 0 || idx == target.length - 1) return null
        val host = target.substring(0, idx)
        val port = target.substring(idx + 1).toIntOrNull() ?: return null
        return host to port
    }

    private fun parseHttpTarget(method: String, uri: String, hostHeader: String?): Triple<String, Int, String>? {
        if (uri.startsWith("http://", true)) {
            val after = uri.substring(7)
            val slash = after.indexOf('/')
            val authority = if (slash >= 0) after.substring(0, slash) else after
            val path = if (slash >= 0) after.substring(slash) else "/"
            val (h, p) = splitHostPort(authority, 80)
            return Triple(h, p, path)
        }
        if (uri.startsWith("https://", true)) {
            val after = uri.substring(8)
            val slash = after.indexOf('/')
            val authority = if (slash >= 0) after.substring(0, slash) else after
            val path = if (slash >= 0) after.substring(slash) else "/"
            val (h, p) = splitHostPort(authority, 443)
            return Triple(h, p, path)
        }
        val (h, p) = splitHostPort(hostHeader ?: return null, 80)
        val path = if (uri.isBlank()) "/" else uri
        return Triple(h, p, path)
    }

    private fun splitHostPort(authority: String, defaultPort: Int): Pair<String, Int> {
        val idx = authority.lastIndexOf(':')
        return if (idx > 0 && idx < authority.length - 1) {
            val h = authority.substring(0, idx)
            val p = authority.substring(idx + 1).toIntOrNull() ?: defaultPort
            h to p
        } else authority to defaultPort
    }

    private fun rewriteRequestToOriginForm(firstHeader: ByteArray, method: String, path: String): ByteArray {
        val header = firstHeader.toString(UTF8)
        val lines = header.split("\r\n")
        if (lines.isEmpty()) return firstHeader

        val parts = lines[0].split(' ')
        val newRequestLine = if (parts.size >= 3) "${parts[0]} $path ${parts[2]}" else lines[0]

        val rebuilt = StringBuilder(newRequestLine).append("\r\n")
        for (i in 1 until lines.size) {
            val ln = lines[i]
            if (ln.lowercase(Locale.US).startsWith("proxy-connection:")) continue
            rebuilt.append(ln).append("\r\n")
        }
        return rebuilt.toString().toByteArray(UTF8)
    }

    private fun writeBadRequest(out: OutputStream) {
        try {
            out.write(b("HTTP/1.1 400 Bad Request\r\n\r\n"))
            out.flush()
        } catch (_: Throwable) {}
    }

    private fun Socket.safeClose() { try { close() } catch (_: Throwable) {} }

    companion object {
        private const val TAG = "RealPingProxy"
        private val UTF8: Charset = Charsets.UTF_8
        private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)

        // --- Singleton state (فرایندی) ---
        private val running = AtomicBoolean(false)
        @Volatile private var server: ServerSocket? = null
        @Volatile private var acceptThread: Thread? = null
        @Volatile private var workerPool: ExecutorService? = null
        @Volatile private var protectFd: ((Int) -> Boolean)? = null
        @Volatile private var boundHost: InetAddress? = null
        @Volatile private var boundPort: Int = 8080
    }
}
