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

Voice, memory and voice chains all shipped in 2.0.0 / 2.1.0, so the list below is what remains after
them. Four phases, in the order I would do them.

### Phase A — Tests (recommended next)

The single largest risk in this project is that nothing is verified except by compiling. Every bug
so far was found by CI or by you, never by a test.

- **JVM tests, no device needed** over the pure logic: `InstagramAnalytics.buildNextMoves`, condition
  evaluation, `ActionType`/`Workflow` JSON round-trips, the voice local parser (`IntentRouter`),
  `VoiceMemory` alias matching, `Contacts` name normalisation, WAV header generation.
- **A fake-AI harness** so `AiPlanner` / `IntentRouter` prompt→JSON validation can be tested without
  a key or a network.
- **One instrumentation smoke test** that opens the app and confirms Home renders.
- CI already runs Gradle, so this costs about one pass and then pays for itself every release.

### Phase B — Small additions (one pass, they belong together)

| Item | Why it matters | Risk |
| --- | --- | --- |
| Quick Settings tile | Torch / DND / voice from the shade, no app launch | Low |
| Reminders | "remind me at 6 to call X" — a real notification, not a clock alarm | Low |
| Screenshot action | `AccessibilityService.takeScreenshot()` on API 30+, saved and shareable | Low |
| Diagnostics depth | FPS, AI latency, per-subsystem health instead of battery/storage only | Low |

### Phase C — Medium (one pass each)

| Item | Note |
| --- | --- |
| Home-screen widget | Reactor + three paddles; `Glance` or a remote-views layout |
| Floating assistant bubble | `SYSTEM_ALERT_WINDOW`, opt-in, foreground service — the one place an ANR could hide, so it must be dismissible and non-blocking |
| App lock / privacy PIN | Gates automation and AI history; never stores a plaintext PIN |
| Downloads worker | WorkManager + progress notification; pairs with the Shizuku tier |
| Translate | Through the operator's own AI key first |

### Phase D — Depth, only if the above feel solid

- **Offline Whisper** so voice works with no key and no network (40–75 MB of model weight — the
  reason it was deferred; the engine is already pluggable).
- **VERIFY step type** in automation, plus a plan → check → run preview for AI-authored routines.
- **Screen-loop improvements**: OCR fallback for custom-drawn apps that expose no labels, and a
  longer action budget behind an explicit opt-in.
- **Multi-turn voice**: "do it again but louder" — the memory layer already stores enough context.
- Health Connect / weather / geofences — each adds a permission and must degrade to a hidden row.

### Deliberately out of scope

- **Always-on wake word** — battery, privacy, and the mic held open behind the operator's back.
- **A JARVIS backend** — the app is the product; no server, no account, no sync.
- **Telegram / MQTT bridges** — they need an account and a server token.
- **Auto-sending SMS or calls** (SOS, call guard) — needs SMS permission and cannot be tested safely.
- **Scraped or unofficial APIs** (WhatsApp linked-device, Instagram private endpoints) — they break on
  someone else's schedule and take the app's stability with them.

### Engineering debt

- ~~No automated tests at all~~ — still true, and Phase A exists to fix it.
- Version notes live in the README; a `CHANGELOG.md` would be cheaper to maintain.
- `VoiceController` and `ScreenAgent` have grown into the two files most worth covering with tests.

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

| Phase | Contents | State |
| --- | --- | --- |
| — | Voice, memory, focus home, voice chains, screen loop | **Shipped** (2.0.0, 2.1.0) |
| A | Tests over the pure logic + fake-AI harness | Next |
| B | Quick Settings tile, reminders, screenshot, diagnostics depth | Queued |
| C | Widget, bubble, app lock, downloads, translate | Queued |
| D | Offline Whisper, VERIFY step, OCR fallback, multi-turn voice | Later |

Everything in "Deliberately out of scope" stays out unless the operator asks.
