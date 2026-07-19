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

## What it shows

- **Claude**: 5-hour window, 7-day window, plus Opus/Sonnet 7-day windows when present — % used and reset time.
- **Codex**: primary (≈5 h) and weekly windows with % used and reset time, plus plan type.

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
