package dev.yuhee.ailimits

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLEncoder

/**
 * The loopback sign-in that removes the copy-and-paste step.
 *
 * The property that actually matters here is that the redirect_uri in the authorize URL
 * and the one later sent to the token endpoint are the *same string* — OAuth compares
 * them literally, and a mismatch is the single most likely way this breaks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClaudeLoginTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a loopback sign-in advertises the port it is listening on`() {
        val url = ClaudeApi.beginLogin(ctx, port = 54321, fixedState = "abc123")
        val expected = URLEncoder.encode("http://localhost:54321/callback", "UTF-8")
        assertTrue("authorize URL must carry the loopback redirect, was: $url",
            url.contains("redirect_uri=$expected"))
        assertTrue(url.contains("state=abc123"))
        assertTrue(url.contains("code_challenge_method=S256"))
    }

    /**
     * The host must be the literal "localhost" even though the socket binds 127.0.0.1 —
     * that is the form the OAuth client is registered with, and matching is a string
     * comparison.
     */
    @Test
    fun `the loopback redirect names localhost, not 127_0_0_1`() {
        assertEquals("http://localhost:9/callback", ClaudeApi.loopbackRedirect(9))
    }

    @Test
    fun `the redirect used to authorize is the one stored for the exchange`() {
        ClaudeApi.beginLogin(ctx, port = 4111, fixedState = "s1")
        val (verifier, state, redirect) = Prefs.claudeFlow(ctx)
        assertTrue(!verifier.isNullOrEmpty())
        assertEquals("s1", state)
        assertEquals("http://localhost:4111/callback", redirect)
    }

    @Test
    fun `the paste flow still authorizes against the console page`() {
        ClaudeApi.beginLogin(ctx, port = null)
        assertEquals(ClaudeApi.MANUAL_REDIRECT, Prefs.claudeFlow(ctx).third)
    }

    /**
     * A sign-in already in flight when the app updated has no stored redirect. Reading
     * that absence as anything but the console URL would fail the exchange — the "new
     * persisted field defaults to zero" shape that has bitten this app before.
     */
    @Test
    fun `a flow stored before redirects were recorded reads as the console redirect`() {
        Prefs.clearClaude(ctx)
        ctx.getSharedPreferences("ailimits", Context.MODE_PRIVATE).edit()
            .putString("cl_verifier", "v").putString("cl_state", "s").apply()
        val (verifier, _, redirect) = Prefs.claudeFlow(ctx)
        assertEquals("v", verifier)
        assertNull("absent must be distinguishable, so the caller can default it", redirect)
    }

    @Test
    fun `signing out clears the in-flight loopback state too`() {
        ClaudeApi.beginLogin(ctx, port = 4111, fixedState = "s1")
        Prefs.setClaudePendingCode(ctx, "code-1")
        Prefs.clearClaude(ctx)
        assertNull(Prefs.claudeFlow(ctx).third)
        assertNull(Prefs.claudePendingCode(ctx))
    }

    // --- what the user may paste when the automatic catch does not happen ---

    @Test
    fun `a full localhost callback url is accepted`() {
        val (code, state) = ClaudeApi.parseCallback(
            "http://localhost:4111/callback?code=abc%2Fdef&state=xyz", "xyz"
        )
        assertEquals("abc/def", code)
        assertEquals("xyz", state)
    }

    @Test
    fun `the console page's code hash state is still accepted`() {
        val (code, state) = ClaudeApi.parseCallback("thecode#thestate", null)
        assertEquals("thecode", code)
        assertEquals("thestate", state)
    }

    @Test
    fun `a bare code falls back to the state we started with`() {
        val (code, state) = ClaudeApi.parseCallback("  justacode  ", "stored")
        assertEquals("justacode", code)
        assertEquals("stored", state)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a paste with no code is refused`() {
        ClaudeApi.parseCallback("http://localhost:4111/callback?error=access_denied", "s")
    }

    @Test
    fun `pending codes are per provider`() {
        Prefs.setClaudePendingCode(ctx, "c-claude")
        Prefs.setCodexPendingCode(ctx, "c-codex")
        assertEquals("c-claude", Prefs.claudePendingCode(ctx))
        Prefs.clearClaudePending(ctx)
        assertNull(Prefs.claudePendingCode(ctx))
        assertEquals("c-codex", Prefs.codexPendingCode(ctx))
    }
}
