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
 * Loopback OAuth catcher for Claude, so signing in no longer means copying a code out of
 * a web page and pasting it back into the app.
 *
 * Unlike [CodexLoginServer] and [GeminiLoginServer], which own fixed ports, this binds an
 * ephemeral one: Claude Code itself calls `listen(0)` and gets a different port on every
 * launch, which is what tells us the client accepts any loopback port. Binding happens in
 * the constructor rather than on the worker thread, because the authorize URL has to carry
 * the port and the token exchange has to echo the identical string — building the URL
 * first, the way the Codex flow does, is not an option here.
 *
 * Construction throws if the socket cannot be opened; the caller falls back to the paste
 * flow, which is exactly what this app did before.
 */
class ClaudeLoginServer(
    private val appCtx: Context,
    private val state: String,
    private val onDone: (String?) -> Unit, // null = success, otherwise error message
) {
    private val server: ServerSocket =
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 15_000 }

    /** The OS-assigned port. Read this before building the authorize URL. */
    val port: Int get() = server.localPort

    @Volatile private var stopped = false
    private val done = AtomicBoolean(false)
    private val thread = Thread(::run, "claude-login").apply { isDaemon = true }

    fun start() = thread.start()

    fun stop() {
        stopped = true
        try { server.close() } catch (_: Exception) {}
    }

    private fun finish(err: String?) {
        if (done.compareAndSet(false, true)) onDone(err)
    }

    private fun run() {
        val deadline = System.currentTimeMillis() + 10 * 60_000L
        try {
            while (!stopped && !done.get() && System.currentTimeMillis() < deadline) {
                val client = try {
                    server.accept()
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
            try { server.close() } catch (_: Exception) {}
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 10_000
        val line = try {
            BufferedReader(InputStreamReader(c.getInputStream())).readLine()
        } catch (_: Exception) { null } ?: return
        val path = line.split(" ").getOrNull(1) ?: return
        // Exactly "/callback" — the path Claude Code registers. Not Codex's /auth/callback.
        if (!path.startsWith("/callback")) {
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
        if (code.isNullOrEmpty()) {
            respond(c, 400, page("Missing code", "No authorization code in the callback. Start the login again from the app."))
            return
        }
        if (q["state"] != state) {
            respond(c, 400, page("State mismatch", "This callback doesn't match the login the app started. Start again from the app."))
            finish("State mismatch — try signing in again")
            return
        }
        // Deferred exchange, for the reason CodexLoginServer documents: with the browser in
        // front this app is backgrounded, and Samsung/Android may refuse it the network.
        Prefs.setClaudePendingCode(appCtx, code)
        respond(c, 200, page("Almost done ✓", "Sign-in caught. Return to Auspex to finish."))
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
