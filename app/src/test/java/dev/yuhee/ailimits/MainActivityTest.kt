package dev.yuhee.ailimits

import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The settings screen is hand-wired rather than generated, so this checks the
 * layout actually inflates, the controls are bound, and toggling one really
 * reaches the stored setting.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MainActivityTest {

    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup()

    @Test
    fun `screen inflates with every control bound`() {
        launch().use { c ->
            val a = c.get()
            assertNotNull(a.findViewById<Spinner>(R.id.spinnerStyle))
            assertNotNull(a.findViewById<Spinner>(R.id.spinnerTheme))
            assertNotNull(a.findViewById<Spinner>(R.id.spinnerInterval))
            assertNotNull(a.findViewById<Spinner>(R.id.spinnerThreshold))
            assertNotNull(a.findViewById<SeekBar>(R.id.seekOpacity))
            assertNotNull(a.findViewById<SeekBar>(R.id.seekPreviewSize))
            assertNotNull(a.findViewById<MaterialSwitch>(R.id.swShowClaude))
            assertNotNull(a.findViewById<MaterialSwitch>(R.id.swShowCodex))
            assertNotNull(a.findViewById<MaterialSwitch>(R.id.swProjection))
            assertNotNull(a.findViewById<MaterialSwitch>(R.id.swSparkline))
            assertNotNull(a.findViewById<MaterialSwitch>(R.id.swNotify))
        }
    }

    @Test
    fun `preview draws the real widget`() {
        launch().use { c ->
            val img = c.get().findViewById<ImageView>(R.id.preview)
            assertNotNull("preview should be populated on resume", img.drawable)
            assertTrue(img.drawable.intrinsicWidth > 0)
        }
    }

    @Test
    fun `spinners are populated`() {
        launch().use { c ->
            val a = c.get()
            assertEquals(WidgetRenderer.Style.entries.size, a.findViewById<Spinner>(R.id.spinnerStyle).adapter.count)
            assertEquals(ThemeMode.entries.size, a.findViewById<Spinner>(R.id.spinnerTheme).adapter.count)
        }
    }

    @Test
    fun `toggling a provider off reaches the setting and cannot blank the widget`() {
        launch().use { c ->
            val a = c.get()
            val claude = a.findViewById<MaterialSwitch>(R.id.swShowClaude)
            val codex = a.findViewById<MaterialSwitch>(R.id.swShowCodex)

            // performClick sets isPressed, which is how the handler tells a user
            // action apart from the programmatic sync on load.
            claude.performClick()
            assertEquals(false, Settings.showClaude(a))
            assertTrue("hiding one must leave the other on", Settings.showCodex(a))
            assertTrue(Settings.solo(a))

            // Turning the remaining one off must flip the first back on.
            codex.performClick()
            assertTrue(Settings.showClaude(a) || Settings.showCodex(a))
        }
    }

    @Test
    fun `opacity slider stores its value`() {
        launch().use { c ->
            val a = c.get()
            val seek = a.findViewById<SeekBar>(R.id.seekOpacity)
            // A drag is what should persist; setProgress alone must not, so the
            // listener is invoked the way a real touch would (fromUser = true).
            // SeekBar spans 40..100, so progress 20 means 60%.
            org.robolectric.Shadows.shadowOf(seek).onSeekBarChangeListener
                ?.onProgressChanged(seek, 20, true)
            assertEquals(60, Settings.opacity(a))
        }
    }
}
