package dev.yuhee.ailimits

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Per-widget settings: which style, which providers, which windows — for one placed
 * widget rather than for all of them.
 *
 * Reached two ways: from the launcher when a widget is added or long-press-reconfigured
 * (API 31+), and from the app's own widget list. The second route is not a convenience —
 * `reconfigurable` needs API 31 and this app supports 26, so for older devices it is the
 * only way to edit a widget that is already on the home screen.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var fromApp = false
    private var draft = WidgetConfig()
    private lateinit var preview: ImageView
    private lateinit var sizeLabel: TextView
    private lateinit var limitList: LinearLayout

    /** What this widget resolves to today for every field the user can override. */
    private val global get() = WidgetRenderer.optsFrom(this)
    private val defaultStyle by lazy { WidgetRenderer.defaultStyleForWidget(this, widgetId) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        fromApp = intent?.getBooleanExtra(EXTRA_FROM_APP, false) == true

        // Set before anything can fail: backing out of this screen must leave the launcher
        // holding a cancelled placement, not a half-configured widget.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        draft = WidgetConfigStore.load(this, widgetId) ?: WidgetConfig()
        setContentView(buildUi())
        renderPreview()
    }

    // ------------------------------------------------------------------ ui

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    private fun heading(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        alpha = .7f
        setPadding(0, dp(16f), 0, dp(4f))
    }

    private fun check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        CheckBox(this).apply {
            text = label
            // Detached before the programmatic set, so syncing a value can never be
            // mistaken for the user tapping it.
            setOnCheckedChangeListener(null)
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(24f))
        }

        col.addView(TextView(this).apply {
            text = "This widget"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })
        col.addView(TextView(this).apply {
            text = "Changes apply to this one widget. Anything you leave alone follows the app's settings."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = .65f
            setPadding(0, dp(4f), 0, dp(12f))
        })

        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        col.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        sizeLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = .6f
            gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, 0)
        }
        col.addView(sizeLabel)

        // --- style
        col.addView(heading("Style"))
        val styles = WidgetRenderer.Style.entries
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(
                this@WidgetConfigActivity,
                android.R.layout.simple_spinner_dropdown_item,
                styles.map { it.label },
            )
            setSelection(styles.indexOf(draft.style ?: defaultStyle).coerceAtLeast(0))
            var first = true
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (first) { first = false; return }
                    draft = draft.copy(style = styles[pos])
                    renderPreview()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        col.addView(TextView(this).apply {
            text = "Changing the style does not change how small this widget can be dragged — " +
                "that is fixed by the one you picked in the launcher."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = .55f
            setPadding(0, dp(4f), 0, 0)
        })

        // --- which limits, as ONE flat list ------------------------------------
        // This replaced a spinner of preset combinations plus separate provider and
        // window sections. Those could express any combination, but only by composing
        // three controls in different places — and the one combination people actually
        // wanted (Claude 5-hour + Claude weekly + a Codex limit) was impossible to see.
        // A checklist over every limit the accounts report makes every permutation one
        // glance and N taps.
        col.addView(heading("Limits on this widget"))
        col.addView(TextView(this).apply {
            text = "Tick any combination."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = .55f
        })
        limitList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(limitList)
        renderLimitChecklist()

        // --- how the ticked limits become rows
        col.addView(heading("Rows"))
        val perProv = RadioButton(this).apply {
            id = View.generateViewId()
            text = "One row per provider — its fullest ticked limit leads"
        }
        val perLim = RadioButton(this).apply {
            id = View.generateViewId()
            text = "One row per limit — every ticked limit is its own row"
        }
        val rowGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(perProv)
            addView(perLim)
        }
        (if (draft.perWindow ?: global.perWindow) perLim else perProv).isChecked = true
        rowGroup.setOnCheckedChangeListener { _, checkedId ->
            draft = draft.copy(perWindow = checkedId == perLim.id)
            renderPreview()
        }
        col.addView(rowGroup)

        // --- appearance
        col.addView(heading("Details"))
        col.addView(check("Projection", draft.projection ?: global.projection) {
            draft = draft.copy(projection = it); renderPreview()
        })
        col.addView(check("Sparkline", draft.sparkline ?: global.sparkline) {
            draft = draft.copy(sparkline = it); renderPreview()
        })
        col.addView(check("Token counts where available", draft.tokens ?: global.tokens) {
            draft = draft.copy(tokens = it); renderPreview()
        })
        col.addView(check("Pace vs the clock", draft.pace ?: global.pace) {
            draft = draft.copy(pace = it); renderPreview()
        })

        val opacityLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(14f), 0, 0)
        }
        fun setOpacityText(v: Int) { opacityLabel.text = "Background opacity — $v%" }
        setOpacityText(draft.opacity ?: global.opacity)
        col.addView(opacityLabel)
        col.addView(SeekBar(this).apply {
            max = 60                                    // 40..100
            progress = (draft.opacity ?: global.opacity).coerceIn(40, 100) - 40
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = value + 40
                    setOpacityText(v)
                    draft = draft.copy(opacity = v)
                    renderPreview()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // --- actions
        col.addView(Button(this).apply {
            text = "Save"
            setPadding(0, dp(8f), 0, dp(8f))
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20f) })

        col.addView(Button(this).apply {
            text = "Use the app's settings"
            setOnClickListener {
                WidgetConfigStore.delete(this@WidgetConfigActivity, widgetId)
                draft = WidgetConfig()
                WidgetRenderer.updateOne(this@WidgetConfigActivity, widgetId)
                finishOk()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val scroll = ScrollView(this).apply { addView(col) }
        // Same edge-to-edge treatment MainActivity needs under targetSdk 35.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return scroll
    }

    /** What this widget currently resolves to, draft over global. */
    private fun merged() = WidgetRenderer.optsFor(draft, global)

    private fun note(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        alpha = .55f
        setPadding(0, dp(8f), 0, 0)
    }

    /** The labels a provider is actually showing: none when it is off, else all minus hidden. */
    private fun checkedLabels(state: ProviderState, shown: Boolean, hidden: Set<String>): Set<String> =
        if (!shown) emptySet()
        else state.windows.map { it.label }.filter { it !in hidden }.toSet()

    /**
     * One checkbox per limit either account reports, both providers in one list. Built
     * from the live snapshot, so it can never offer a limit that does not exist — and a
     * provider that has not signed in says so instead of silently having no entries.
     */
    private fun renderLimitChecklist() {
        limitList.removeAllViews()
        val snap = UsageRepo.load(this)
        val m = merged()

        fun provider(name: String, isClaude: Boolean, state: ProviderState, shown: Boolean, hidden: Set<String>) {
            if (!state.configured || state.windows.isEmpty()) {
                limitList.addView(note("$name — sign in and refresh once, and its limits appear here"))
                return
            }
            val checked = checkedLabels(state, shown, hidden)
            state.windows.forEach { win ->
                val label = "$name · ${WidgetRenderer.windowName(win.label)}  —  ${win.pct}% used"
                limitList.addView(check(label, win.label in checked) { on ->
                    toggleLimit(isClaude, state, win.label, on)
                })
            }
        }
        provider("Claude", true, snap.claude, m.showClaude, m.hiddenClaude)
        provider("Codex", false, snap.codex, m.showCodex, m.hiddenCodex)
    }

    /**
     * Rewrites the provider-visibility and hidden-window fields from one tick. The
     * checklist is a VIEW over those fields, not a new kind of state: unticking every
     * limit of a provider turns the provider off, ticking any turns it back on with
     * exactly the ticked set visible.
     */
    private fun toggleLimit(isClaude: Boolean, state: ProviderState, label: String, on: Boolean) {
        val m = merged()
        val all = state.windows.map { it.label }.toSet()
        val checked = checkedLabels(
            state,
            if (isClaude) m.showClaude else m.showCodex,
            if (isClaude) m.hiddenClaude else m.hiddenCodex,
        ).toMutableSet()
        if (on) checked.add(label) else checked.remove(label)

        if (checked.isEmpty()) {
            // The last ticked limit overall cannot be unticked — a widget showing nothing
            // is not a state worth supporting, and the renderer would fall back to the
            // app defaults, which would contradict every box on this screen.
            val snap = UsageRepo.load(this)
            val otherHasAny = if (isClaude) {
                checkedLabels(snap.codex, m.showCodex, m.hiddenCodex).isNotEmpty()
            } else {
                checkedLabels(snap.claude, m.showClaude, m.hiddenClaude).isNotEmpty()
            }
            if (!otherHasAny) {
                android.widget.Toast.makeText(this, "A widget has to show at least one limit", android.widget.Toast.LENGTH_SHORT).show()
                renderLimitChecklist()   // snap the box back on
                return
            }
            draft = if (isClaude) draft.copy(showClaude = false) else draft.copy(showCodex = false)
        } else {
            draft = if (isClaude) draft.copy(showClaude = true, hiddenClaude = all - checked)
                    else draft.copy(showCodex = true, hiddenCodex = all - checked)
        }
        renderPreview()
    }

    private fun renderPreview() {
        val (w, h) = WidgetRenderer.sizeOf(this, widgetId)
        sizeLabel.text = "${w.toInt()} × ${h.toInt()} dp — this widget's actual size"
        runCatching {
            val (bmp, _) = WidgetRenderer.render(
                this, draft.style ?: defaultStyle, w, h,
                UsageRepo.load(this), UsageRepo.history(this),
                o = WidgetRenderer.optsFor(draft, global),
            )
            preview.setImageBitmap(bmp)
        }
    }

    // ---------------------------------------------------------------- save

    /**
     * Anything equal to the app-wide setting is stored as "inherit" rather than as an
     * override, so a widget the user did not actually customise keeps following the app
     * settings instead of silently freezing today's values.
     */
    private fun normalised(): WidgetConfig {
        val g = global
        fun <T> orNull(v: T?, same: T): T? = if (v == null || v == same) null else v
        return WidgetConfig(
            style = draft.style?.takeIf { it != defaultStyle },
            showClaude = orNull(draft.showClaude, g.showClaude),
            showCodex = orNull(draft.showCodex, g.showCodex),
            opacity = orNull(draft.opacity, g.opacity),
            projection = orNull(draft.projection, g.projection),
            sparkline = orNull(draft.sparkline, g.sparkline),
            tokens = orNull(draft.tokens, g.tokens),
            pace = orNull(draft.pace, g.pace),
            perWindow = orNull(draft.perWindow, g.perWindow),
            hiddenClaude = orNull(draft.hiddenClaude, g.hiddenClaude),
            hiddenCodex = orNull(draft.hiddenCodex, g.hiddenCodex),
        )
    }

    private fun save() {
        WidgetConfigStore.save(this, widgetId, normalised())
        // Nothing after the store may take the save down with it: the record is written,
        // and redraw/scheduling are best-effort follow-ups, not conditions of it.
        runCatching { WidgetRenderer.updateOne(this, widgetId) }
        // A widget added through a config activity may never see onUpdate, and that is the
        // only place the periodic refresh is scheduled. Both calls are idempotent.
        runCatching {
            RefreshWorker.schedulePeriodic(this)
            if (RefreshWorker.isDue(this)) RefreshWorker.refreshNow(this)
        }
        finishOk()
    }

    private fun finishOk() {
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    companion object {
        /** Set when the app opened this itself, rather than the launcher placing a widget. */
        const val EXTRA_FROM_APP = "dev.yuhee.ailimits.FROM_APP"

        fun intentFor(ctx: android.content.Context, widgetId: Int) =
            Intent(ctx, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(EXTRA_FROM_APP, true)
    }
}
