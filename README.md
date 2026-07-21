# AI Limits

Standalone Android app + home-screen widget that shows your **Claude** and **Codex (ChatGPT)** usage-limit windows — the same numbers you see at claude.ai → Settings → Usage and chatgpt.com → Codex → Settings → Usage.

**Fully independent by design:** the app signs in to each provider with its own OAuth (PKCE) grant, entirely on the phone. It shares no tokens or sessions with the Claude app, the ChatGPT app, Claude Code, Codex CLI, or any other device — so token rotation elsewhere can never break it.

## Download

Grab `AILimits.apk` from the [latest release](../../releases/latest) and open it on your phone (allow "install unknown apps" if prompted).

## Setup

1. Open **AI Limits**.
2. **Claude → Sign in**: approve access in the browser, copy the code shown, return and tap **Paste code**.
3. **Codex → Sign in with ChatGPT**: log in in the browser; the app catches the redirect automatically (loopback listener on `localhost:1455`, same mechanism Codex CLI uses).
4. Long-press your home screen → **Widgets → AI Limits** to add the widget.

The widget refreshes every 30 minutes (WorkManager); tap it to refresh immediately.

## Widget styles

Four styles, all drawn on a canvas so they scale properly instead of clipping:

| Style | Default size | Shows |
| --- | --- | --- |
| **Detail** | 4×3 | Both providers: the window that is actually limiting you as a large %, its bar, when it resets, and the remaining windows |
| **Slim bars** | 4×1 | One thin row per provider — % used and time to reset |
| **Rings** | 3×2 | Two dial gauges, the tightest limit per provider |
| **History** | 4×3 | 24-hour chart of both providers with axes and a legend |

**Detail** re-lays itself out for the size you drag it to — from a two-column glance at 4×1, through a bar-per-provider at 4×2, to the full hero layout with per-provider 12-hour sparklines and a footer when it is tall enough. Every style is resizable to any cell count the launcher offers and drops its least important element rather than clipping: ring captions disappear before the dials shrink, the chart sheds its axes before the plot does, and dragging a widget taller grows the charts instead of leaving a gap.

## Settings

All of these change the widgets immediately, with a live preview in the app:

- **Which providers to show.** Hide one and the other gets a roomier layout — every window on its own row instead of squeezed into chips.
- **Theme** — follow the system, or force dark/light regardless of it.
- **Background opacity**, 40–100%, for translucency over the wallpaper.
- **Burn-projection warning** and **trend sparklines** can each be turned off.
- **Alerts** — get notified when a window passes 75/80/90/95%. Fires once per window per reset period, not on every refresh.
- **Auto-refresh** every 15 min / 30 min / 1 h / 2 h.
- **Copy diagnostics** puts the current state on the clipboard. No tokens are included.

## What it shows

- **Claude**: 5-hour window, 7-day window, plus Opus/Sonnet 7-day windows when present.
- **Codex**: primary (≈5 h) and weekly windows, plus the plan type as a chip.
- **The binding window** — whichever is fullest — is the headline number, because that is the one that will actually stop you.
- **"USED" prefixes every headline number**, and a companion "N% left" is shown, so there is no confusion between how much is spent and how much remains.
- **Burn rate**: how fast the binding window is climbing right now, in points per hour ("burning 6%/hr", "holding steady", "easing off"), read from recent history.
- **Reset** as both a clock time and time remaining ("resets 18:12 · 3h 40m left").
- **Colour escalates** with pressure: provider colour under 75%, amber to 90%, red above.
- **Burn projection**: if recent history says you will hit the cap before the window resets, the header switches to "on pace to cap 16:52".
- Tall or sparkline-less widgets fill the freed space with the stats line rather than leaving a gap.
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
