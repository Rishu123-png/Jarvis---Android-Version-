# Roadmap

Living plan for J.A.R.V.I.S. — what is shipped, what is left, and what was borrowed in spirit from
[FRIDAY OS](https://github.com/Rishu123-png/friday-os) but **rebuilt natively** (JARVIS does not
depend on that project, its backend, or its web layer).

Last updated with **2.1.0 (versionCode 14)**.

---

## 1 · Shipped

| Area | State |
| --- | --- |
| HUD, arc reactor, telemetry, clock, boot log | Live |
| Home app role, app drawer, editable dock | Live |
| Notifications (listener, alerts panel, media) | Live |
| Controls: torch, volume, media transport, system panels | Live |
| Do Not Disturb — direct control after one grant | Live |
| Automation: 27 actions, 12 conditions, 10 triggers, IF/REPEAT/WAIT UNTIL, AI authoring, run history, foreground watcher | Live |
| Intelligence: bring-your-own AI key, three wire formats, model discovery | Live |
| Terminal: ~22 commands, offline where possible | Live |
| Instagram creator analytics, views-first + AI briefing | Live |
| Connections: Instagram OAuth, Google Drive + Gmail (read-only) | Live |
| Privileged shell via Shizuku (optional, adb-level, no root) | Live |
| Voice: tap-to-speak orb, Whisper via your key, device recognizer fallback, spoken replies | Live |
| Voice commands: contacts, calls, WhatsApp messages, apps, device control, alarms, timers | Live |
| Memory: last 50 commands, contact nicknames, app aliases (on device) | Live |
| Focus home: top bar + orb only, full dashboard behind one button | Live |
| Chained voice tasks: 8-step plans, waits, CameraX capture, open newest photo | Live |
| Screen-aware loop: observe → decide → act across apps (accessibility tier, 6 actions max) | Live |
| Automated tests | **None** |

---

## 2 · What is left

### 2.1 Voice — the last missing module

This is the only tile still marked STAGED on the HUD.

- Push-to-talk: microphone permission requested **only** when the module is opened, released the
  moment the utterance ends. Never at launch.
- `SpeechRecognizer` first, with `EXTRA_PREFER_OFFLINE`; fall back to the keyboard on any error, no
  spinner left running (the recogniser has a hard ~10 s window — respect it).
- Transcript goes to `CommandRegistry` (local commands) and then to the AI core, exactly like typed
  input. Replies are spoken with the existing `SpeechOut` TTS.
- No always-on wake word in this pass: it needs a foreground mic service, costs battery and is the
  single most common cause of "assistant drains my phone". Revisit only if asked.

### 2.2 Memory — an assistant that remembers

- Local store: facts, notes, pinned items, and a short conversation digest.
- A compact memory brief is prepended to the AI system prompt, so answers carry context.
- Fully wipeable from Settings; nothing leaves the device except in the prompt the operator sends.

### 2.3 Small, high-value additions

| Item | Why it matters | Risk |
| --- | --- | --- |
| Quick Settings tile | Torch / DND / open HUD from the shade | Low |
| Reminders | "remind me at 6 to call X" — a real notification, not a clock alarm | Low |
| Screenshot action | `AccessibilityService.takeScreenshot()` on API 30+, saved and shareable | Low |
| Widget | Reactor + three paddles on the home screen | Low |
| Diagnostics depth | FPS, memory, AI latency, battery gate (FRIDAY has this; JARVIS has battery/storage only) | Low |

### 2.4 Medium

| Item | Note |
| --- | --- |
| Floating assistant bubble | `SYSTEM_ALERT_WINDOW`, opt-in, foreground service — the one place an ANR could hide, so it must be dismissible and non-blocking |
| App lock / privacy PIN | Gates automation and the AI history; never stores a plaintext PIN |
| Downloads worker | WorkManager + progress notification; pairs with the Shizuku tier |
| Translate | Through the operator's own AI key first, on-device ML Kit only if size allows |
| Health Connect / steps, weather, geofences | Each adds a permission; each must degrade to a hidden row when absent |

### 2.5 Deliberately out of scope

- **Always-on wake word** — battery, privacy, and the mic held open behind the user's back.
- **A JARVIS backend** — the app is the product; no server to run, no account, no sync.
- **Telegram / MQTT bridges** — they require an account and a server token, which is the opposite of
  the current privacy posture.
- **Auto-sending SMS or calls** (SOS, call guard) — needs SMS permission and cannot be tested safely
  from here; dialing and the SMS composer stay as they are.
- **Scraped or unofficial APIs** (WhatsApp linked-device, Instagram private endpoints) — they break on
  someone else's schedule and would take the app's stability with them.

### 2.6 Engineering debt worth paying

- **No automated tests at all.** The analytics maths, condition evaluation, JSON schema round-trips
  and the action parser are pure Kotlin and testable on the JVM with no device. FRIDAY OS ships ~400
  tests; a dozen here would have caught the last three compile-time mistakes before CI did.
- Version and release notes live in the README; a `CHANGELOG.md` would be cheaper to maintain.

---

## 3 · What to take from FRIDAY OS, and how

FRIDAY OS is a PWA + Capacitor + FastAPI stack. JARVIS is a native Compose app with no server, so the
ideas are worth more than the code — everything below is a rewrite, not a port.

| FRIDAY capability | JARVIS plan | Effort |
| --- | --- | --- |
| Voice engine state machine + TTS | Voice module (2.1) with the same explicit states: idle → listening → thinking → speaking, all driven by real callbacks | 1 pass |
| Memory + daily digest | 2.2 | 1 pass |
| Typed action protocol with safety tiers | **Already there** — `ActionType` + confirmation gate + preflight; JARVIS additionally has control flow, which FRIDAY's 15 actions do not | done |
| Honest failure surface | Partly there (missing-metric notes, skipped steps). Extend to a single "degraded subsystems" strip on the dashboard | small |
| Quick Settings tile, bubble, widget | 2.3 / 2.4 | 1 pass each |
| Performance monitor (FPS/CPU/RAM) | Extend DIAGNOSTICS | small |
| Agent orchestrator with verification | Automation already verifies step results; add a **VERIFY** step type and an AI "plan → check → run" preview | medium |
| Telegram / MQTT | Out of scope (needs a server) | — |
| On-device sherpa/Vosk STT, llama.cpp | Defer: 50 MB–2 GB of model weight. System `SpeechRecognizer` first; revisit only if offline voice is a hard requirement | large |

---

## 4 · Order of work

1. **Voice** — the last staged tile.
2. **Memory** — makes the AI core feel like an assistant rather than a chat box.
3. **Tests** — a dozen JVM tests over the pure logic, so stability stops depending on CI luck.
4. Quick Settings tile + reminders + screenshot (one pass, they are small).
5. Widget, bubble, app lock, downloads.
6. Everything in 2.5 stays out unless the operator asks.
