# AI Limits

Standalone Android app + home-screen widget that shows your **Claude** and **Codex (ChatGPT)** usage-limit windows — the same numbers you see at claude.ai → Settings → Usage and chatgpt.com → Codex → Settings → Usage.

**Fully independent by design:** the app signs in to each provider with its own OAuth (PKCE) grant, entirely on the phone. It shares no tokens or sessions with the Claude app, the ChatGPT app, Claude Code, Codex CLI, or any other device — so token rotation elsewhere can never break it.

## Download

Grab `AILimits.apk` from the [latest release](../../releases/latest) and open it on your phone (allow "install unknown apps" if prompted).

## Setup

1. Open **Auspex**.
2. **Claude → Sign in**: approve access in the browser and you come straight back — the app catches the redirect itself on a loopback listener, the same way Claude Code does (an OS-assigned port, path `/callback`).
3. **Codex → Sign in with ChatGPT**: log in in the browser; the app catches the redirect automatically (loopback listener on `localhost:1455`, same mechanism Codex CLI uses).
4. Long-press your home screen → **Widgets → Auspex** to add a widget.

No provider needs a copy-and-paste step any more. Each still has a **Paste** button as a
fallback for the case where the browser reaches the listener but Android has killed the app
behind it — paste the address-bar URL and the sign-in completes.

The widget refreshes every 30 minutes (WorkManager); tap it to refresh immediately.

## Widget styles

Ten styles, all drawn on a canvas so they scale properly instead of clipping:

| Style | Default size | Shows |
| --- | --- | --- |
| **Detail** | 4×3 | Every provider: the window that is actually limiting you as a large %, its bar, when it resets, and the remaining windows |
| **Slim bars** | 4×1 | One thin row per provider — % used and time to reset |
| **Rings** | 3×2 | Dial gauges, the tightest limit per provider |
| **History** | 4×3 | 24-hour chart with axes and a legend |
| **Battery** | 3×1 | The inverse framing: each AI as a battery showing how much is *left* |
| **Countdown** | 4×1 | Time-first: when each limit resets, with a bar of how far through the window you are |
| **Ticker** | 4×1 | One dense line of text, every provider — the smallest footprint there is |
| **Pick** | 1×1 | One verdict: which AI has the most room right now, in the biggest type that fits |
| **Horizon** | 4×2 | Every upcoming reset on one forward timeline — the only view of the windows that are *not* binding |
| **Runway** | 4×2 | Whether you run dry before each limit refills: the bar is where your burn rate lands, the notch is the reset |

**Pick** is the only style that needs no history at all, so it is fully populated one fetch
after signing in — and the only one that reads at a single cell. **Horizon** is the only
style that shows Claude's 7-day/Opus/Sonnet windows and a Codex secondary window; every
other style keeps just the tightest one. **Runway** stays empty
until there is enough recent history to measure a burn rate, rather than drawing a
projection it cannot support.

**Detail** re-lays itself out for the size you drag it to — from a two-column glance at 4×1, through a bar-per-provider at 4×2, to the full hero layout with per-provider 12-hour sparklines and a footer when it is tall enough. Every style is resizable to any cell count the launcher offers and drops its least important element rather than clipping: ring captions disappear before the dials shrink, the chart sheds its axes before the plot does, and dragging a widget taller grows the charts instead of leaving a gap.

## One widget per limit

Each limit can be its own widget, and every widget says which limit it is.

Open a widget's settings — tap it under **Your widgets** in the app, or long-press it on
the home screen (Android 12+) — and pick from **This widget shows**:

    Everything
    Claude — all limits
    Claude · 5-hour window
    Claude · 7-day window
    Claude · Opus · 7-day
    Codex — all limits
    Codex · 5-hour window
    Codex · weekly window

The list is built from the limits your account actually reports, so it can never offer one
that does not exist. Pick a single limit and the widget's heading becomes **"Claude 5h"**
rather than just "Claude" — which is what makes two widgets side by side readable, since
"Claude 68%" next to "Claude 31%" tells you nothing about which limit either is.

Choosing a limit is a shortcut, not a separate mode: it writes the same provider and
window fields the checkboxes below it do, so there is one source of truth for what a
widget shows and no second code path that could disagree with the first.

## Per-widget settings

Every setting is an app-wide **default**; any single placed widget can override it. So a
Ticker pinned to Claude's weekly window can sit next to a Runway showing both providers.

A widget you have not configured has no stored record at all, and keeps following the app
defaults — including after an update, which is asserted by a test rather than assumed.

## Settings

All of these change the widgets immediately, with a live preview in the app:

- **Which providers to show** — Claude, Codex, or either alone. With one provider on it gets a roomier layout (a full row per window).
- **Exact counts over percentages**, where a provider publishes one. Neither Claude nor Codex does today — they expose percentages only, and a count is never invented from one. The rendering path and the field-name diagnostics both stay, so the day one of them starts publishing a count it shows up rather than going unnoticed.
- **Pace against the clock** — "1.8x pace" means the window is being spent nearly twice as fast as it refills. Shown only for windows whose length is known, and only once enough of the window has elapsed for the ratio to mean anything.
- **Which limit windows to show, per provider and per widget** — the 5-hour limit, the weekly one, or both. Uncheck any window (say, hide Claude's 5-hour and keep only the weekly) and the widget's headline becomes the fullest of what's left. New windows from the API appear automatically. When hiding windows changes which one leads, trend-derived extras (burn rate, projection, sparkline) go quiet rather than describing the wrong window.
- **Theme** — follow the system, or force dark/light regardless of it.
- **Background opacity**, 40–100%, for translucency over the wallpaper.
- **Burn-projection warning** and **trend sparklines** can each be turned off.
- **Alerts** — get notified when a window passes 75/80/90/95%. Fires once per window per reset period, not on every refresh.
- **Auto-refresh** every 15 min / 30 min / 1 h / 2 h.
- **Copy diagnostics** puts the current state on the clipboard, including the *field names* each provider last returned. Names only, with anything that does not look like a field name redacted — a payload keyed by account id or email must not put that identifier into text you are invited to paste into a bug report. Error text is summarised rather than raw, because a provider error can embed a server response body. Usage figures and plan tier are included; credentials never are.

## What it shows

- **Claude**: 5-hour window, 7-day window, plus Opus/Sonnet 7-day windows when present.
- **Codex**: primary (≈5 h) and weekly windows, plus the plan type as a chip.
- **Aligned across providers**: every bar starts at a shared column and the type is sized for a large phone, so both panels read as one even grid. Widgets resize across the full One UI cell range and degrade gracefully when narrow (dropping the least important element rather than clipping).
- **The binding window** — whichever is fullest — is the headline number, because that is the one that will actually stop you.
- **"USED" prefixes every headline number**, and a companion "N% left" is shown, so there is no confusion between how much is spent and how much remains.
- **Burn rate**: how fast the binding window is climbing right now, in points per hour ("burning 6%/hr", "holding steady", "easing off"), read from recent history.
- **Reset** as both a clock time and time remaining ("resets 18:12 · 3h 40m left").
- **Every style, at every size, carries one line**: how long until the tightest limit comes back, and how old the numbers are ("resets 1h 11m   ⟳ 4m"). It holds down to each style's smallest draggable size, which is asserted by a test rather than assumed — a percentage with no reset time and no age is a number you can neither act on nor trust. That line is also the "open the app" tap target.
- **Colour escalates** with pressure: provider colour under 75%, amber to 90%, red above.
- **Burn projection**: if recent history says you will hit the cap before the window resets, the header switches to "on pace to cap 16:52".
- Tall or sparkline-less widgets fill the freed space with the stats line rather than leaving a gap.
- **Per-provider staleness**: if one provider stops answering, its name and dot turn amber in every style. A failed refresh keeps the last good numbers, so without this a revoked token looked identical to healthy data.
- A stale-data dot appears if the last successful fetch is over an hour old.

## How it works

- Claude: `claude.ai/oauth/authorize` PKCE flow → `api.anthropic.com/api/oauth/usage`.
- Codex: `auth.openai.com/oauth/authorize` PKCE flow with an in-app loopback catcher on `http://localhost:1455/auth/callback` → `chatgpt.com/backend-api/wham/usage`. The refresh token rotates on every use; the app persists the new one each time.
- Tokens live only in the app's private `SharedPreferences`.

## Building

Requires JDK 17 and the Android SDK (platform 35).

```
gradle assembleRelease
```

The signing keystore is not committed; without one the release build is unsigned — add your own `keystore.jks` at the repo root (alias `aiusage`, password `aiusage-local`) or adjust `app/build.gradle`.

### Tests

`gradle testReleaseUnitTest` runs the lot:

- **Rendering.** The widget is drawn on a `Canvas`, so it can be rendered off-device. Every style is rendered at every layout tier, at both resize extremes, under each display setting, and in both themes through Robolectric's native (real Skia) graphics — the same pixels the launcher would draw. PNGs land in `app/build/widget-shots/` for eyeballing.
- **Layout invariants.** The chosen tier must never need more height than it was given, must never get poorer as the widget grows, and every height in the resizable range must draw.
- **Logic.** Window naming, plan prettifying, time-remaining formatting, which window binds, and when a burn projection is warranted.
- **Alerts.** That a window over the threshold interrupts once and not on every refresh, and speaks again after it resets.
- **The settings screen** inflates with every control bound, toggling one reaches the stored setting, and system-bar insets become padding.
- **Widget inflation.** Every tag in the widget layout is checked for the `@RemoteView` annotation, and the RemoteViews each style publishes is actually inflated. RemoteViews only accepts annotated view classes; anything else throws in the launcher and surfaces as nothing more than "Couldn't add widget", which no amount of rendering tests will catch.
- **Parsing.** That an unrecognised 200 is treated as a failure rather than as "no limits", so a schema change cannot silently erase good data. These run under Robolectric because the stock JVM `org.json` is a stub that throws on every call — enough to make a "this should throw" assertion pass without the parser running at all.
