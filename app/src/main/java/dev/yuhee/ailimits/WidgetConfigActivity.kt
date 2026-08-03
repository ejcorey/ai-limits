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
    private lateinit var windowBoxes: LinearLayout
    private lateinit var focusSpinner: Spinner
    private lateinit var claudeBox: CheckBox
    private lateinit var codexBox: CheckBox

    /**
     * One entry per limit the account actually reports, plus "Everything".
     *
     * Choosing one is a shortcut, not a new kind of state: it writes the same
     * provider-visibility and hidden-window fields the checkboxes below do. That keeps a
     * single source of truth for what a widget shows, so there is no second code path
     * that could disagree with the first — and no migration to get wrong.
     */
    private class Focus(val label: String, val apply: (WidgetConfig) -> WidgetConfig)

    private var focusChoices: List<Focus> = emptyList()

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

        // --- what this widget is about
        col.addView(heading("This widget shows"))
        focusSpinner = Spinner(this)
        col.addView(focusSpinner)
        col.addView(TextView(this).apply {
            text = "Pick one limit and this widget is about that limit alone — its heading says so, " +
                "and you can place another widget for a different one."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = .55f
            setPadding(0, dp(4f), 0, 0)
        })
        renderFocusChoices()

        // --- providers
        col.addView(heading("Providers"))
        claudeBox = check("Claude", draft.showClaude ?: global.showClaude) {
            draft = draft.copy(showClaude = it); afterProviderChange()
        }
        codexBox = check("Codex", draft.showCodex ?: global.showCodex) {
            draft = draft.copy(showCodex = it); afterProviderChange()
        }
        col.addView(claudeBox)
        col.addView(codexBox)

        // --- windows
        windowBoxes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(windowBoxes)
        renderWindowToggles()

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

    /**
     * Turning every provider off leaves nothing to draw, so the renderer falls back to the
     * app-wide set. Saying so beats letting the preview quietly contradict the checkboxes.
     */
    private fun afterProviderChange() {
        renderWindowToggles()
        renderPreview()
    }

    /**
     * Builds the "this widget shows" list from the windows the account is really
     * reporting, so it can never offer a limit that does not exist.
     */
    private fun renderFocusChoices() {
        val snap = UsageRepo.load(this)
        val choices = mutableListOf(
            Focus("Everything") { c ->
                c.copy(showClaude = null, showCodex = null, hiddenClaude = null, hiddenCodex = null)
            }
        )
        fun addProvider(name: String, state: ProviderState, isClaude: Boolean) {
            if (!state.configured || state.windows.isEmpty()) return
            choices.add(
                Focus("$name — all limits") { c ->
                    if (isClaude) c.copy(showClaude = true, showCodex = false, hiddenClaude = emptySet())
                    else c.copy(showClaude = false, showCodex = true, hiddenCodex = emptySet())
                }
            )
            if (state.windows.size < 2) return
            state.windows.forEach { win ->
                // Everything except this one is hidden — that is what makes the widget
                // about a single limit, and what makes its heading name that limit.
                val others = state.windows.map { it.label }.filter { it != win.label }.toSet()
                choices.add(
                    Focus("$name · ${WidgetRenderer.windowName(win.label)}") { c ->
                        if (isClaude) c.copy(showClaude = true, showCodex = false, hiddenClaude = others)
                        else c.copy(showClaude = false, showCodex = true, hiddenCodex = others)
                    }
                )
            }
        }
        addProvider("Claude", snap.claude, true)
        addProvider("Codex", snap.codex, false)
        focusChoices = choices

        focusSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, choices.map { it.label }
        )
        focusSpinner.setSelection(currentFocusIndex().coerceAtLeast(0))
        var first = true
        focusSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; return }
                draft = focusChoices[pos].apply(draft)
                syncProviderBoxes()
                renderWindowToggles()
                renderPreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** Which entry the draft currently matches, or 0 ("Everything") when it matches none. */
    private fun currentFocusIndex(): Int {
        val target = WidgetRenderer.optsFor(draft, global)
        val snap = UsageRepo.load(this)
        focusChoices.forEachIndexed { i, f ->
            val opts = WidgetRenderer.optsFor(f.apply(WidgetConfig()), global)
            if (i > 0 &&
                opts.showClaude == target.showClaude &&
                opts.showCodex == target.showCodex &&
                opts.hiddenClaude == target.hiddenClaude &&
                opts.hiddenCodex == target.hiddenCodex
            ) return i
        }
        // Unused today beyond the guard above, but reading the snapshot keeps this honest
        // if a provider stops reporting a window the draft still hides.
        if (snap.claude.windows.isEmpty() && snap.codex.windows.isEmpty()) return 0
        return 0
    }

    /** Keeps the provider checkboxes agreeing with a choice made in the spinner. */
    private fun syncProviderBoxes() {
        val opts = WidgetRenderer.optsFor(draft, global)
        listOf(claudeBox to opts.showClaude, codexBox to opts.showCodex).forEach { (box, on) ->
            box.setOnCheckedChangeListener(null)
            box.isChecked = on
            box.setOnCheckedChangeListener { _, v ->
                draft = if (box === claudeBox) draft.copy(showClaude = v) else draft.copy(showCodex = v)
                afterProviderChange()
            }
        }
    }

    private fun renderWindowToggles() {
        windowBoxes.removeAllViews()
        val snap = UsageRepo.load(this)
        val opts = WidgetRenderer.optsFor(draft, global)
        data class P(val name: String, val key: String, val state: ProviderState, val shown: Boolean)
        val provs = listOf(
            P("Claude", "cl", snap.claude, opts.showClaude),
            P("Codex", "cx", snap.codex, opts.showCodex),
        )
        // The heading is unconditional. Hiding the whole section whenever there was nothing
        // to tick made the feature invisible: a user who has not signed in, or whose first
        // fetch has not landed, saw no sign that choosing between the 5-hour and the weekly
        // limit was possible at all.
        windowBoxes.addView(heading("Limit windows"))
        windowBoxes.addView(TextView(this).apply {
            text = "Show the 5-hour limit, the weekly one, or both — per provider, for this widget."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = .55f
            setPadding(0, 0, 0, dp(2f))
        })

        fun note(s: String) = windowBoxes.addView(TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = .55f
            setPadding(0, dp(8f), 0, 0)
        })

        val visible = provs.filter { it.shown }
        if (visible.none { it.state.configured }) {
            note("Sign in to a provider and refresh once — its windows appear here.")
        } else if (visible.none { it.state.windows.size >= 2 }) {
            note("Only one window is being reported right now, so there is nothing to choose yet.")
        }

        provs.forEach { p ->
            // A single-window provider has no choice to offer; the notes above cover it.
            if (!p.shown || !p.state.configured || p.state.windows.size < 2) return@forEach
            windowBoxes.addView(TextView(this).apply {
                text = p.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = .7f
                setPadding(0, dp(8f), 0, 0)
            })
            val hidden = hiddenFor(p.key).toMutableSet()
            p.state.windows.forEach { win ->
                windowBoxes.addView(check(WidgetRenderer.windowName(win.label), win.label !in hidden) { on ->
                    if (on) hidden.remove(win.label) else hidden.add(win.label)
                    draft = if (p.key == "cl") draft.copy(hiddenClaude = hidden.toSet())
                            else draft.copy(hiddenCodex = hidden.toSet())
                    renderPreview()
                })
            }
        }
    }

    private fun hiddenFor(key: String): Set<String> =
        if (key == "cl") draft.hiddenClaude ?: global.hiddenClaude
        else draft.hiddenCodex ?: global.hiddenCodex

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
            hiddenClaude = orNull(draft.hiddenClaude, g.hiddenClaude),
            hiddenCodex = orNull(draft.hiddenCodex, g.hiddenCodex),
        )
    }

    private fun save() {
        WidgetConfigStore.save(this, widgetId, normalised())
        WidgetRenderer.updateOne(this, widgetId)
        // A widget added through a config activity may never see onUpdate, and that is the
        // only place the periodic refresh is scheduled. Both calls are idempotent.
        RefreshWorker.schedulePeriodic(this)
        if (RefreshWorker.isDue(this)) RefreshWorker.refreshNow(this)
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
