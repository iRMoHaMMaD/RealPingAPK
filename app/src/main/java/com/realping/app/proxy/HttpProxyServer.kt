package com.realping.app.proxy

import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.util.Locale
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import kotlin.math.min

/**
 * یک پروکسی HTTP ساده با پشتیبانی از:
 *  - CONNECT برای HTTPS
 *  - درخواست‌های absolute-form مثل: GET http://host/path HTTP/1.1
 * هدف: کنسول/دستگاه‌های متصل به هات‌اسپات با تنظیم Proxy از تونل استفاده کنند.
 */
class HttpProxyServer(
    private val port: Int = 8080,
    private val bindHost: String = "0.0.0.0",
) {
    private val TAG = "RealPingProxy"
    @Volatile private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (server != null) return
        server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindHost, port))
            soTimeout = 0
        }
        Log.i(TAG, "Proxy started on $bindHost:$port")
        val srv = server!!
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !srv.isClosed) {
                    val client = srv.accept()
                    Log.i(TAG, "Client connected from ${client.inetAddress?.hostAddress}:${client.port}")
                    launch(Dispatchers.IO) { handleClient(client) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Accept loop ended: ${t.message}")
            }
        }
    }

    fun stop() {
        runCatching { acceptJob?.cancel() }
        acceptJob = null
        runCatching { server?.close() }
        server = null
        Log.i(TAG, "Proxy stopped")
    }

    fun isRunning(): Boolean = server != null && !server!!.isClosed

    private fun handleClient(client: Socket) {
        client.soTimeout = 30_000
        client.tcpNoDelay = true
        val cin = BufferedInputStream(client.getInputStream())
        val cout = BufferedOutputStream(client.getOutputStream())
        try {
            val requestLine = readLine(cin) ?: return client.close()
            Log.i(TAG, "Request: $requestLine")
            val parts = requestLine.split(' ')
            if (parts.size < 3) return writeBadRequest(cout)

            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]
            val headers = readHeaders(cin)

            when (method) {
                "CONNECT" -> {
                    val (host, port) = splitHostPort(target, 443)
                    Log.i(TAG, "CONNECT to $host:$port")
                    tunnelTcp(client, cout, cin, host, port)
                }
                "GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH" -> {
                    if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
                        val url = URL(target)
                        val port = if (url.port != -1) url.port else (if (url.protocol.equals("https", true)) 443 else 80)
                        Log.i(TAG, "$method $target → ${url.host}:$port")
                        forwardHttp(client, cout, cin, method, url, port, headers)
                    } else {
                        val hostHeader = headers["host"] ?: return writeBadRequest(cout)
                        val (host, port) = splitHostPort(hostHeader, 80)
                        Log.i(TAG, "$method host=$host:$port path=$target")
                        val url = URL("http", host, port, target)
                        forwardHttp(client, cout, cin, method, url, port, headers)
                    }
                }
                else -> writeMethodNotAllowed(cout)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Handle client error: ${t.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun tunnelTcp(
        client: Socket,
        cout: OutputStream,
        cin: InputStream,
        host: String,
        port: Int
    ) {
        var remote: Socket? = null
        try {
            remote = Socket()
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(host, port), 20_000)
            val rin = BufferedInputStream(remote.getInputStream())
            val rout = BufferedOutputStream(remote.getOutputStream())

            // پاسخ تایید تونل
            cout.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: RealPing-Proxy\r\n\r\n".toByteArray())
            cout.flush()

            // دو طرفه pipe
            val t1 = Thread { pipe(cin, rout) }
            val t2 = Thread { pipe(rin, cout) }
            t1.start(); t2.start()
            t1.join(); t2.join()
        } catch (_: Throwable) {
            runCatching {
                cout.write("HTTP/1.1 502 Bad Gateway\r\nProxy-Agent: RealPing-Proxy\r\nContent-Length:0\r\n\r\n".toByteArray())
                cout.flush()
            }
        } finally {
            runCatching { remote?.close() }
        }
    }

    private fun forwardHttp(
        client: Socket,
        cout: OutputStream,
        cin: InputStream,
        method: String,
        url: URL,
        port: Int,
        headers: Map<String, String>
    ) {
        var remote: Socket? = null
        try {
            remote = Socket()
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(url.host, port), 20_000)
            val rin = BufferedInputStream(remote.getInputStream())
            val rout = BufferedOutputStream(remote.getOutputStream())

            // خط اول باید origin-form باشد (مسیر/کوئری)، نه absolute-form
            val path = if (url.file.isNullOrEmpty()) "/" else url.file

            // بازنویسی Host/Connection
            val outHeaders = headers.toMutableMap()
            outHeaders["host"] = if (url.port == -1) url.host else "${url.host}:${port}"
            outHeaders["connection"] = "close"
            outHeaders.remove("proxy-connection")

            // ارسال درخواست به سرور مقصد
            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            for ((k, v) in outHeaders) {
                sb.append(k.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() })
                    .append(": ").append(v).append("\r\n")
            }
            sb.append("\r\n")
            rout.write(sb.toString().toByteArray())
            rout.flush()

            // اگر بادی‌ای از کلاینت باقی مانده (مثلاً POST)، همون را مستقیم pipe کن
            // (در این پیاده‌سازی ساده، فرض می‌کنیم بدنه کوچک است یا کلاینت بعد از هدر ارسال می‌کند)
            // اگر نیاز به پردازش دقیق Content-Length/Chunked بود، اینجا باید تقویت شود.

            // پاسخ را بدون دست‌کاری برگردان
            pipe(rin, cout)
        } catch (_: Throwable) {
            runCatching {
                cout.write("HTTP/1.1 502 Bad Gateway\r\nProxy-Agent: RealPing-Proxy\r\nContent-Length:0\r\n\r\n".toByteArray())
                cout.flush()
            }
        } finally {
            runCatching { remote?.close() }
        }
    }

    // --- utils ---

    private fun splitHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        val hp = hostPort.trim()
        val idx = hp.lastIndexOf(':')
        return if (idx > 0 && idx < hp.length - 1 && hp.indexOf(']') == -1) {
            val h = hp.substring(0, idx)
            val p = hp.substring(idx + 1).toIntOrNull() ?: defaultPort
            h to p
        } else {
            hp to defaultPort
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.append(b.toChar())
            if (buf.length > 8192) return null
        }
        return buf.toString()
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) {
                val k = line.substring(0, i).trim().lowercase(Locale.US)
                val v = line.substring(i + 1).trim()
                map[k] = v
            }
        }
        return map
    }

    private fun writeBadRequest(out: OutputStream) {
        out.write("HTTP/1.1 400 Bad Request\r\nProxy-Agent: RealPing-Proxy\r\nContent-Length:0\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun writeMethodNotAllowed(out: OutputStream) {
        out.write("HTTP/1.1 405 Method Not Allowed\r\nProxy-Agent: RealPing-Proxy\r\nContent-Length:0\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(64 * 1024)
        var wrote = false
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            wrote = true
        }
        if (wrote) {
            try { output.flush() } catch (_: Throwable) {}
        }
    }
}

/** کمکی: فهرست IPهای لوکال قابل‌نمایش برای کاربر (برای تنظیم روی کنسول) */
fun getLocalPrivateIps(): List<String> {
    val out = ArrayList<String>()
    val ifs = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
    for (ni in ifs) {
        if (!ni.isUp || ni.isLoopback) continue
        for (addr in ni.inetAddresses) {
            if (addr is Inet4Address && addr.isSiteLocalAddress) {
                out.add(addr.hostAddress)
            }
        }
    }
    return out.distinct()
}
