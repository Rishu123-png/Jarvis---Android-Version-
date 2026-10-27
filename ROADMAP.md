# Roadmap

Living plan for J.A.R.V.I.S. — what is shipped, what is left, and what was borrowed in spirit from
[FRIDAY OS](https://github.com/Rishu123-png/friday-os) but **rebuilt natively** (JARVIS does not
depend on that project, its backend, or its web layer).

Last updated with **1.0.0 (versionCode 21)**.

---

## 1 · Shipped

| Area | State |
| --- | --- |
| HUD, arc reactor, telemetry, clock, boot log | Live |
| Home app role, app drawer, editable dock | Live |
| Notifications (listener, alerts panel, media) | Live |
| Controls: torch, volume, media transport, system panels | Live |
| Do Not Disturb — direct control after one grant | Live |
| Automation: 37 action types (33 actions + 4 control-flow), 12 conditions, 10 triggers, VERIFY, AI authoring, run history, foreground watcher | Live |
| Intelligence: bring-your-own AI key, three wire formats, model discovery | Live |
| Terminal: ~22 commands, offline where possible | Live |
| Instagram creator analytics, views-first + AI briefing | Live |
| Connections: Instagram OAuth, Google Drive + Gmail (read-only) | Live |
| Privileged shell via Shizuku (optional, adb-level, no root) | Live |
| Voice: tap-to-speak orb, Whisper via your key, device recognizer fallback, spoken replies | Live |
| Voice commands: contacts, calls, WhatsApp messages, apps, device control, alarms, timers | Live |
| Memory: last 50 commands, contact nicknames, app aliases (on device) | Live |
| Knowledge shelf: keyless lookups (Wikipedia + dictionary), on-device summariser, ask-first dial, cards that answer offline | Live |
| Sight: one on-demand photo, decode-time subsampling, answered by the configured model, refuses to guess | Live |
| Continuity: whole memory exported to one JSON file, imported additively, AI key excluded | Live |
| Focus home: top bar + orb only, full dashboard behind one button | Live |
| Chained voice tasks: 8-step plans, waits, CameraX capture, open newest photo | Live |
| Screen-aware loop: observe → decide → act across apps (accessibility tier, 6 actions max) | Live |
| ~400 phrasings, fuzzy matching, Hinglish, never a dead end | Live |
| Open anything · message by name · alerts read aloud with reply suggestions | Live |
| File search, distance by road, offline maths, settings deep links | Live |
| Health: steps, distance, water, sleep estimate, heart rate | Live |
| Phone's own lock, lock-screen line, language-aware replies | Live |
| Calendar, contacts, voice notes, follow-ups, brief | Live |
| **The brain**: local memory of people and facts, free conversation, small talk offline | Live |
| Tests: 17 JVM suites over the pure logic, run in CI before the APKs | Live |
| Quick Settings tiles (open JARVIS, torch) | Live |
| Reminders, screenshot action, subsystem health panel | Live |
| Privacy lock (PBKDF2 PIN) + fingerprint/face shortcut, floating bubble, home-screen widget | Live |
| Downloads, translate, per-app speech engine choice | Live |
| OCR fallback for the screen agent (bundled, on device) | Live |
| VERIFY step type, multi-turn voice, 12-hour clock | Live |
| Automated tests | **None** |

---

## 2 · What is left

Voice, memory and voice chains all shipped in 2.0.0 / 2.1.0, so the list below is what remains after
them. Four phases, in the order I would do them.

### Phase A — Tests ✔ shipped in 2.2.0

Extracted the parsers out of the Android classes so they could be tested at all, then covered them:

- `VoiceParsingTest` — message / call / volume / alarm / timer / camera / reminder phrasing, including
  the negatives ("show me my photos" must not fire the camera).
- `JsonReplyTest` — code fences, prose, braces inside strings, invalid objects before valid ones.
- `WavTest` — RIFF header arithmetic against the payload.
- `WorkflowJsonTest` — routine round-trip, nested blocks, conditions, time windows, unique action ids.
- `AppLaunchTest` (androidTest) — compiles in CI, runs on a device with `connectedDebugAndroidTest`.

CI runs `testDebugUnitTest` **before** building the APKs, and publishes a per-suite table in the run
summary, so a red test stops a release.

### Phase B — Small additions ✔ shipped in 2.2.0

Quick Settings tiles (open JARVIS, torch), reminders as real notifications (exact alarms, reboot-safe),
screenshot via the accessibility API, and a SUBSYSTEMS panel in DIAGNOSTICS that reports every
permission-gated capability honestly.

### Phase C — Medium ✔ shipped in 2.3.0

Privacy lock (PBKDF2 PIN, relock after a minute), floating bubble (opt-in, foreground, never listens on
its own), home-screen widget (live 12-hour `TextClock`), downloads worker (bounded, progress
notification, `Download/JARVIS`), translate through the operator's key.

### Phase D — Depth, partly shipped in 2.3.0

| Item | State |
| --- | --- |
| VERIFY step type (app / screen / notification / battery / charging / network) | Shipped |
| OCR fallback for custom-drawn apps (bundled ML Kit, on device) | Shipped |
| Multi-turn voice (previous command passed to the planner) | Shipped |
| Speech engine choice: auto / whisper / device / device-offline | Shipped |
| **True on-device Whisper** (40–75 MB of model weight, native build) | **Still deferred** — the offline route uses the platform recognizer instead |
| Longer screen-agent budget behind an opt-in | Not started |
| Health Connect / weather / geofences | Not started |

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
| A | Tests over the pure logic | **Shipped** (2.2.0) |
| B | Quick Settings tile, reminders, screenshot, diagnostics depth | **Shipped** (2.2.0) |
| C | Privacy lock, bubble, widget, downloads, translate | **Shipped** |
| D | VERIFY, OCR fallback, multi-turn voice, engine choice | **Shipped** |
| D | On-device Whisper model, longer agent budget, health/weather | Later |

Everything in "Deliberately out of scope" stays out unless the operator asks.
