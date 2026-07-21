package dev.yuhee.ailimits

import android.content.Context
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xmlpull.v1.XmlPullParser

/**
 * Guards the rule that made every widget fail to add in v2.2.
 *
 * RemoteViews does not inflate arbitrary layouts. It installs a LayoutInflater.Filter
 * that accepts a class only when it carries the @RemoteViews.RemoteView annotation;
 * anything else throws InflateException in the launcher's process, and all the user
 * ever sees is the toast "Couldn't add widget". A plain <View> — used as an invisible
 * tap target — is not annotated, so the whole widget was unaddable.
 *
 * Rendering tests could never have caught this: the bitmap drew perfectly. Only
 * inflating the RemoteViews does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetLayoutTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** The prefixes LayoutInflater tries for an unqualified tag, in order. */
    private val prefixes = listOf("android.widget.", "android.view.", "android.webkit.")

    private fun resolve(tag: String): Class<*> {
        if (tag.contains('.')) return Class.forName(tag)
        prefixes.forEach { p -> runCatching { return Class.forName(p + tag) } }
        fail("could not resolve view class for tag <$tag>")
        error("unreachable")
    }

    private fun tagsIn(layoutRes: Int): List<String> {
        val tags = mutableListOf<String>()
        ctx.resources.getLayout(layoutRes).use { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) tags += parser.name
                event = parser.next()
            }
        }
        return tags
    }

    private fun <R> android.content.res.XmlResourceParser.use(body: (android.content.res.XmlResourceParser) -> R): R =
        try { body(this) } finally { close() }

    @Test
    fun `every view in the widget layout is one RemoteViews will accept`() {
        val tags = tagsIn(R.layout.widget_canvas)
        assertTrue("expected to find views in the layout", tags.isNotEmpty())
        tags.forEach { tag ->
            val cls = resolve(tag)
            assertTrue(
                "<$tag> (${cls.name}) is not annotated @RemoteView, so RemoteViews will refuse " +
                    "to inflate it and the launcher will report \"Couldn't add widget\"",
                cls.isAnnotationPresent(RemoteViews.RemoteView::class.java),
            )
        }
    }

    /**
     * The end-to-end check: build the RemoteViews each provider actually publishes and
     * inflate it, exactly as the launcher would.
     */
    @Test
    fun `the RemoteViews every style publishes actually inflates`() {
        val now = System.currentTimeMillis()
        val snap = Snapshot(
            ProviderState(true, listOf(Win("5h", 68, now + 3 * 3_600_000L)), null),
            ProviderState(true, listOf(Win("7d", 41, now + 2 * 86_400_000L)), null, "plus"),
            now,
        )
        val hist = (0..40).map { i ->
            Triple(now - (40 - i) * 30 * 60_000L, 10 + i, 5 + i / 2)
        }

        // Sizes chosen to exercise both branches of the footer, since the footer is
        // what toggles the overlay's visibility.
        val sizes = listOf(264f to 72f, 264f to 160f, 264f to 232f, 140f to 40f, 420f to 320f)
        for (style in WidgetRenderer.Style.entries) {
            for ((w, h) in sizes) {
                val rv = WidgetRenderer.compose(ctx, style, w, h, snap, hist)
                val parent = FrameLayout(ctx)
                try {
                    rv.apply(ctx, parent)
                } catch (e: Exception) {
                    fail("$style at ${w}x$h could not be inflated by a launcher: $e")
                }
            }
        }
    }
}
