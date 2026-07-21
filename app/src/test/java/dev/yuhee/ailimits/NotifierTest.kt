package dev.yuhee.ailimits

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The alert rule that matters: a window over the threshold must interrupt once,
 * not on every refresh, and must be allowed to speak again after it resets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])   // below API 33, so POST_NOTIFICATIONS is granted implicitly
class NotifierTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L

    private fun posted() =
        shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java))
            .allNotifications.size

    /**
     * A repeat alert for the same window reuses its notification id, so counting
     * active notifications cannot tell a fresh post from a suppressed one — the
     * title is what reveals whether it was rewritten.
     */
    private fun currentTitle(): String? =
        shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java))
            .allNotifications.firstOrNull()
            ?.extras?.getString(android.app.Notification.EXTRA_TITLE)

    private fun snapAt(pct: Int, resetsAt: Long) = Snapshot(
        ProviderState(true, listOf(Win("5h", pct, resetsAt)), null),
        ProviderState(false, emptyList(), null),
        now,
    )

    @Before
    fun setUp() {
        Settings.setNotifyEnabled(ctx, true)
        Settings.setNotifyThreshold(ctx, 90)
        Settings.setFiredAlerts(ctx, emptySet())
    }

    @Test
    fun `quiet below the threshold`() {
        Notifier.check(ctx, snapAt(80, now + 2 * hour))
        assertEquals(0, posted())
    }

    @Test
    fun `fires once, then stays quiet for the same window`() {
        val reset = now + 2 * hour
        Notifier.check(ctx, snapAt(92, reset))
        assertEquals(1, posted())
        assertTrue(currentTitle()!!.contains("92%"))

        // Three more refreshes while still over the line must not nag: if any of
        // them re-posted, the title would have moved to 94%.
        repeat(3) { Notifier.check(ctx, snapAt(94, reset)) }
        assertEquals(1, posted())
        assertTrue("re-alerted for a window already announced", currentTitle()!!.contains("92%"))
    }

    @Test
    fun `speaks again once the window has reset`() {
        Notifier.check(ctx, snapAt(92, now + 2 * hour))
        assertTrue(currentTitle()!!.contains("92%"))

        // New period: a later reset time is a different window, so it may speak
        // again — replacing the stale alert rather than stacking a second one.
        Notifier.check(ctx, snapAt(91, now + 7 * hour))
        assertEquals(1, posted())
        assertTrue("new period should re-alert", currentTitle()!!.contains("91%"))
    }

    @Test
    fun `stale keys do not accumulate`() {
        Notifier.check(ctx, snapAt(95, now + hour))
        Notifier.check(ctx, snapAt(95, now + 2 * hour))
        Notifier.check(ctx, snapAt(95, now + 3 * hour))
        // Only the window currently present should still be remembered.
        assertEquals(1, Settings.firedAlerts(ctx).size)
        assertTrue(Settings.firedAlerts(ctx).single().endsWith("${now + 3 * hour}"))
    }

    @Test
    fun `disabled means silent`() {
        Settings.setNotifyEnabled(ctx, false)
        Notifier.check(ctx, snapAt(99, now + hour))
        assertEquals(0, posted())
    }

    @Test
    fun `an unconfigured provider never alerts`() {
        val snap = Snapshot(
            ProviderState(false, listOf(Win("5h", 99, now + hour)), null),
            ProviderState(false, emptyList(), null),
            now,
        )
        Notifier.check(ctx, snap)
        assertEquals(0, posted())
    }
}
