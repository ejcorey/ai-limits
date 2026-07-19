package dev.yuhee.ailimits

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback OAuth catcher: listens on http://127.0.0.1:1455 for the redirect from
 * auth.openai.com (the browser and this app are the same device, so "localhost"
 * lands here). Performs the token exchange itself and persists the result, so the
 * login completes even if Android pauses the activity while the browser is open.
 */
class CodexLoginServer(
    private val appCtx: Context,
    private val flow: CodexApi.Flow,
    private val onDone: (String?) -> Unit, // null = success, otherwise error message
) {
    @Volatile private var stopped = false
    private var server: ServerSocket? = null
    private val done = AtomicBoolean(false)
    private val thread = Thread(::run, "codex-login").apply { isDaemon = true }

    fun start() = thread.start()

    fun stop() {
        stopped = true
        try { server?.close() } catch (_: Exception) {}
    }

    private fun finish(err: String?) {
        if (done.compareAndSet(false, true)) onDone(err)
    }

    private fun run() {
        val srv = try {
            ServerSocket(1455, 4, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            finish("Couldn't open the login listener (port 1455): ${e.message}")
            return
        }
        server = srv
        srv.soTimeout = 15_000
        val deadline = System.currentTimeMillis() + 10 * 60_000L
        try {
            while (!stopped && !done.get() && System.currentTimeMillis() < deadline) {
                val client = try {
                    srv.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (!stopped) finish("Login listener error: ${e.message}")
                    return
                }
                client.use { handle(it) }
            }
            if (!stopped && !done.get()) finish("Login timed out — tap Sign in to try again")
        } finally {
            try { srv.close() } catch (_: Exception) {}
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 10_000
        val line = try {
            BufferedReader(InputStreamReader(c.getInputStream())).readLine()
        } catch (_: Exception) { null } ?: return
        val path = line.split(" ").getOrNull(1) ?: return
        if (!path.startsWith("/auth/callback")) {
            respond(c, 404, "Not found")
            return
        }
        val q = parseQuery(path)
        val denied = q["error"]
        if (denied != null) {
            respond(c, 200, page("Login not completed", "The login was cancelled or denied ($denied). You can close this tab."))
            finish("Login denied: $denied")
            return
        }
        val code = q["code"]
        val state = q["state"]
        if (code.isNullOrEmpty()) {
            respond(c, 400, page("Missing code", "No authorization code in the callback. Start the login again from the app."))
            return
        }
        if (state != flow.state) {
            respond(c, 400, page("State mismatch", "This callback doesn't match the login the app started. Start again from the app."))
            finish("State mismatch — try signing in again")
            return
        }
        // Don't exchange here: with the browser in front this app is backgrounded and
        // Samsung/Android may block its network (seen as "Unable to resolve host").
        // Store the code; MainActivity.onResume completes the exchange in the foreground.
        Prefs.setCodexPendingCode(appCtx, code)
        respond(c, 200, page("Almost done ✓", "Sign-in caught. Return to AI Limits to finish."))
        finish(null)
    }

    private fun parseQuery(path: String): Map<String, String> {
        val q = path.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null
            else URLDecoder.decode(kv.substring(0, i), "UTF-8") to URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }.toMap()
    }

    private fun respond(c: Socket, status: Int, html: String) {
        try {
            val body = html.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            c.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(body)
                flush()
            }
        } catch (_: Exception) {}
    }

    private fun page(title: String, msg: String) =
        "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
        "<body style='background:#141414;color:#ECEAE5;font-family:sans-serif;display:flex;" +
        "align-items:center;justify-content:center;height:100vh;margin:0;text-align:center'>" +
        "<div><h2>$title</h2><p style='color:#9A9A9A'>$msg</p></div></body>"
}
