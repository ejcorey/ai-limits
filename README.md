# AI Limits

Standalone Android app + home-screen widget that shows your **Claude**, **Codex (ChatGPT)** and **Gemini** usage-limit windows — the same numbers you see at claude.ai → Settings → Usage, chatgpt.com → Codex → Settings → Usage, and the Code Assist quota Gemini CLI runs against.

**Fully independent by design:** the app signs in to each provider with its own OAuth (PKCE) grant, entirely on the phone. It shares no tokens or sessions with the Claude app, the ChatGPT app, Claude Code, Codex CLI, or any other device — so token rotation elsewhere can never break it.

## Download

Grab `AILimits.apk` from the [latest release](../../releases/latest) and open it on your phone (allow "install unknown apps" if prompted).

## Setup

1. Open **AI Limits**.
2. **Claude → Sign in**: approve access in the browser, copy the code shown, return and tap **Paste code**.
3. **Codex → Sign in with ChatGPT**: log in in the browser; the app catches the redirect automatically (loopback listener on `localhost:1455`, same mechanism Codex CLI uses).
4. **Gemini → Sign in**: log in with Google; the app catches the redirect the same way (loopback on `localhost:7856`, the mechanism Gemini CLI uses). What's shown is the per-model Code Assist quota — the pool Gemini CLI draws from — with your tier as a chip.
5. Long-press your home screen → **Widgets → AI Limits** to add the widget.

The widget refreshes every 30 minutes (WorkManager); tap it to refresh immediately.

## Widget styles

Seven styles, all drawn on a canvas so they scale properly instead of clipping:

| Style | Default size | Shows |
| --- | --- | --- |
| **Detail** | 4×3 | Every provider: the window that is actually limiting you as a large %, its bar, when it resets, and the remaining windows |
| **Slim bars** | 4×1 | One thin row per provider — % used and time to reset |
| **Rings** | 3×2 | Dial gauges, the tightest limit per provider |
| **History** | 4×3 | 24-hour chart with axes and a legend |
| **Battery** | 3×1 | The inverse framing: each AI as a battery showing how much is *left* |
| **Countdown** | 4×1 | Time-first: when each limit resets, with a bar of how far through the window you are |
| **Ticker** | 4×1 | One dense line of text, every provider — the smallest footprint there is |

**Detail** re-lays itself out for the size you drag it to — from a two-column glance at 4×1, through a bar-per-provider at 4×2, to the full hero layout with per-provider 12-hour sparklines and a footer when it is tall enough. Every style is resizable to any cell count the launcher offers and drops its least important element rather than clipping: ring captions disappear before the dials shrink, the chart sheds its axes before the plot does, and dragging a widget taller grows the charts instead of leaving a gap.

## Settings

All of these change the widgets immediately, with a live preview in the app:

- **Which providers to show** — Claude, Codex, and optionally **Gemini**. With one provider on, it gets a roomier layout (a full row per window); with three, the widget lays out three panels. Gemini stays off widgets until you sign in.
- **Exact counts over percentages**, where a provider publishes one. Gemini reports tokens remaining, so widgets can say "1.2M tokens left" instead of "63% left"; Claude and Codex publish percentages only, and nothing is invented for them.
- **Pace against the clock** — "1.8x pace" means the window is being spent nearly twice as fast as it refills. Shown only for windows whose length is known, and only once enough of the window has elapsed for the ratio to mean anything.
- **Which windows to show, per provider** — uncheck any limit window (say, hide Claude's 5-hour and keep only the weekly) and the widgets' headline becomes the fullest of what's left. New windows from the API appear automatically. When hiding windows changes which one leads, trend-derived extras (burn rate, projection, sparkline) go quiet rather than describing the wrong window.
- **Theme** — follow the system, or force dark/light regardless of it.
- **Background opacity**, 40–100%, for translucency over the wallpaper.
- **Burn-projection warning** and **trend sparklines** can each be turned off.
- **Alerts** — get notified when a window passes 75/80/90/95%. Fires once per window per reset period, not on every refresh.
- **Auto-refresh** every 15 min / 30 min / 1 h / 2 h.
- **Copy diagnostics** puts the current state on the clipboard, including the *field names* each provider last returned. Names only, with anything that does not look like a field name redacted — a payload keyed by account id or email must not put that identifier into text you are invited to paste into a bug report. Error text is summarised rather than raw, because a provider error can embed a server response body. Usage figures and plan tier are included; credentials never are.

## What it shows

- **Claude**: 5-hour window, 7-day window, plus Opus/Sonnet 7-day windows when present.
- **Codex**: primary (≈5 h) and weekly windows, plus the plan type as a chip.
- **Gemini** (optional): live per-model quota — % used and reset time per family (Pro, Flash…), plus your tier as a chip. Fetched from the same Cloud Code endpoint Gemini CLI uses.
- **Aligned across providers**: every bar starts at a shared column and the type is sized for a large phone, so two or three panels read as one even grid. Widgets resize across the full One UI cell range and degrade gracefully when narrow (dropping the least important element rather than clipping).
- **The binding window** — whichever is fullest — is the headline number, because that is the one that will actually stop you.
- **"USED" prefixes every headline number**, and a companion "N% left" is shown, so there is no confusion between how much is spent and how much remains.
- **Burn rate**: how fast the binding window is climbing right now, in points per hour ("burning 6%/hr", "holding steady", "easing off"), read from recent history.
- **Reset** as both a clock time and time remaining ("resets 18:12 · 3h 40m left").
- **Colour escalates** with pressure: provider colour under 75%, amber to 90%, red above.
- **Burn projection**: if recent history says you will hit the cap before the window resets, the header switches to "on pace to cap 16:52".
- Tall or sparkline-less widgets fill the freed space with the stats line rather than leaving a gap.
- **Per-provider staleness**: if one provider stops answering, its name and dot turn amber in every style. A failed refresh keeps the last good numbers, so without this a revoked token looked identical to healthy data.
- A stale-data dot appears if the last successful fetch is over an hour old.

## How it works

- Claude: `claude.ai/oauth/authorize` PKCE flow → `api.anthropic.com/api/oauth/usage`.
- Codex: `auth.openai.com/oauth/authorize` PKCE flow with an in-app loopback catcher on `http://localhost:1455/auth/callback` → `chatgpt.com/backend-api/wham/usage`. The refresh token rotates on every use; the app persists the new one each time.
- Gemini: Google PKCE flow (`accounts.google.com`) with a loopback catcher on `http://localhost:7856/oauth2callback`, using Gemini CLI's public installed-app client — then `cloudcode-pa.googleapis.com/v1internal:loadCodeAssist` (tier + project, onboarding if needed) and `:retrieveUserQuota` (per-model buckets: fraction remaining + reset time). Endpoints and shapes verified against the Gemini CLI source.
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
