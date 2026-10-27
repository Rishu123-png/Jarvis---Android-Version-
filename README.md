# J.A.R.V.I.S.

**A native Android assistant that actually does things.** Voice in, real actions out — calls, messages,
camera, app control — on top of a hand-drawn HUD. No account, no JARVIS server, no telemetry. Every AI
feature runs on a key *you* own, and every permission is requested at the moment it is needed.

`com.jarvis.assistant` · Kotlin + Jetpack Compose + Material 3 · minSdk 29 (Android 10) · **1.0.0**

| | |
| --- | --- |
| **Voice** | Tap the orb, speak, tap again. Whisper through your key, or the phone's own recognizer |
| **Actions** | 33 typed actions — calls, messages, camera, screenshots, downloads, reminders, translation |
| **Chains** | “open camera, capture, show me” — up to 8 steps with waits between app hand-offs |
| **Screen agent** | Observe → decide → act inside apps it has never seen, one tap at a time |
| **Memory** | Last 50 commands, contact nicknames, app aliases — on device only |
| **Privacy** | PBKDF2 PIN lock, local-only keys, nothing leaves the phone unless you send it |

---

## Table of contents

1. [What works right now](#what-works-right-now)
2. [Architecture](#architecture)
3. [Voice](#voice)
4. [Automation](#automation)
5. [Connections and analytics](#connections-and-analytics)
6. [Privacy, permissions and the lock](#privacy-permissions-and-the-lock)
7. [Install it](#install-it)
8. [Release it](#release-it)
9. [Tests](#tests)
10. [Stability and honest limits](#stability-and-honest-limits)
11. [Design system](#design-system)
12. [Project layout](#project-layout)

---

## What works right now

| Area | State |
| --- | --- |
| HUD · arc reactor · live telemetry · 12-hour clock | Working |
| Home app role, app drawer, editable dock | Working |
| Notifications, torch, volume, media, DND, system panels | Working |
| Automation: control flow, triggers, AI authoring, run history | Working |
| Voice: tap-to-speak, Whisper or on-device, spoken replies | Working |
| Voice commands: contacts, calls, WhatsApp messages, apps, reminders | Working |
| Voice chains: camera capture → gallery, multi-app, screen-aware tapping | Working |
| Screen agent with OCR fallback for custom-drawn apps | Working |
| Instagram creator analytics, views-first (your own account) | Working |
| Connections: Instagram login, Google Drive + Gmail (read-only) | Working |
| Intelligence: your own AI key, any supported provider | Working |
| Privacy lock, floating bubble, home-screen widget | Working |
| Quick Settings tiles, reminders, screenshot, downloads | Working |
| Privileged shell via Shizuku (optional, adb-level, no root) | Working |
| Understands ~400 phrasings per intent family, fuzzy and Hinglish included | Working |
| Opens any app, the gallery, files and ~25 settings pages | Working |
| Message/reply by name, through the notification's own reply action | Working |
| Reads alerts aloud, judges adverts, suggests replies | Working |
| File search (All-files access), distance by road, offline maths | Working |
| Health: steps, distance, water, sleep estimate, heart rate where present | Working |
| Lock with the phone's own PIN/pattern/fingerprint, line on the lock screen | Working |
| Answers in the language it was asked in (EN/HI/Hinglish offline) | Working |
| Calendar read/add, contact saving, voice notes, multi-turn follow-ups | Working |
| A short brief on unlock, at most every three hours, silent otherwise | Working |
| Automated tests: 16 JVM suites + 1 device smoke test, run in CI before the APK | Working |

Every tile on the HUD is either backed by a real Android API or clearly marked as not built. Nothing
in the interface imitates a capability the app does not have.

---

## Architecture

```
┌─────────────────────────────── UI (Compose) ───────────────────────────────┐
│  HomeScreen  ── orb + top bar            DashboardScreen  ── tiles, dock  │
│  VoiceTaskCard ── what is happening      LockScreen / PrivacyScreen        │
└───────────────────────────────┬────────────────────────────────────────────┘
                                │ state (StateFlow)
┌───────────────────────────────▼────────────────────────────────────────────┐
│ VoiceController — one turn at a time, every stage bounded                  │
│   MicRecorder ─► Whisper (your key)  │  LocalSpeech (device / offline)     │
│   IntentRouter ─► AI plan (no canned command list) │ local parser fallback │
│   StepExecutor ─► 33 typed actions │ ScreenAgent (observe→decide→act)      │
│   SpeechOut ── spoken reply                    VoiceMemory ── recents      │
└───────────────────────────────┬────────────────────────────────────────────┘
                                │
┌───────────────────────────────▼────────────────────────────────────────────┐
│ Android surfaces: intents · MediaStore · CameraX · AccessibilityService     │
│ AlarmManager · Quick Settings · AppWidget · NotificationListener · Shizuku  │
└────────────────────────────────────────────────────────────────────────────┘
```

Three rules hold the whole thing together:

1. **Nothing blocks the main thread.** Recording, transcription, planning, each step and each screen
   action run off it, with a timeout on all of them.
2. **One turn at a time.** Every turn carries an id; a late coroutine from a cancelled turn can never
   write over what you are looking at.
3. **Honest outcomes.** A failed step stops the turn and says why. There is no fake success anywhere.

---

## Voice

**Tap the orb, speak, tap again.** Home is a top bar and one orb — the tiles and telemetry are one tap
away behind the grid button, so nothing competes with the thing you actually press.

### Ears — four routes, chosen in Advanced settings

| Route | Engine | Audio goes to |
| --- | --- | --- |
| `AUTO` (default) | Whisper if a key is stored, else the device recognizer | as chosen |
| `WHISPER` | Your provider's transcription endpoint | that provider only, then discarded |
| `DEVICE` | Android `SpeechRecognizer` | the platform recognizer |
| `DEVICE OFFLINE` | `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE` | nothing, when an on-device model exists |

No model is bundled and none is downloaded: the Whisper path uploads a 16 kHz WAV built in-process
(RTF 44-byte header, unit-tested) and works with any OpenAI-compatible key. A provider without a
speech endpoint falls back to the device recognizer instead of breaking voice.

### Brain — no command list to memorise

With a key, the raw sentence goes to the model along with the list of capabilities this phone actually
has. Sloppy speech, half-sentences and mixed Hindi–English are fine. The model may only choose ids that
exist; anything it invents is dropped before execution. **There is no canned-command menu in the UI.**

Without a key, a silent parser covers the phrasings people really use — and it is covered by 21 tests,
including the negatives that matter (“show me my photos” must not fire the camera).

### Chains

A sentence can carry several actions: up to 8 steps with waits between app hand-offs.

> “open camera, capture the image and show me in gallery”

→ `TAKE PHOTO ✓` → `WAIT ✓` → `SHOW PHOTO ✓`

The middle step does not need you to press anything: JARVIS opens its own capture surface, takes one
frame with CameraX, writes it to `Pictures/JARVIS` and closes.

### Screen agent

For anything buried inside another app — a switch on a settings page, a chat behind a search box — the
plan ends with a **screen task**: read what is on screen, ask the model for exactly one action, do it,
look again. At most six actions, each shown on the card, each cancellable, each verified against a label
that was actually on screen. When the accessibility tree is empty (custom-drawn apps), it falls back to
a screenshot plus **bundled on-device OCR**, so pixels are readable too.

### Follow-ups

The previous command is handed to the planner, so “again, but quieter”, “do that for Divik” and “turn it
off” resolve without repeating yourself.

---

## Automation

- **37 action types** — 33 executable actions plus `IF` / `REPEAT` / `WAIT UNTIL` / `STOP_ROUTINE`
  control-flow blocks — with 12 conditions and 10 triggers.
- **`VERIFY` step**: `app:com.whatsapp`, `screen:Send`, `notif:hi`, `battery<20`, `charging`, `wifi`.
  A routine can now check that something happened instead of trusting a step's own report.
- **AI authoring**: describe a routine in a sentence; the planner returns a validated workflow, and the
  model can only choose actions and conditions that exist.
- **Safety tiers**: sensitive actions are marked, confirmed before a run, and cannot be produced by the
  planner in a way that skips the gate.
- **Run history** with per-step outcomes, so a failure is inspectable rather than mysterious.

---

## Connections and analytics

- **Instagram**: Instagram Login (no Facebook Page needed), token exchange on device; views-first
  analytics, `NEXT POST` suggestions, and a six-probe `diagnose()` that says exactly which step fails.
- **Google**: Drive and Gmail, read-only, through your own OAuth client — nothing is proxied.
- **WhatsApp**: stated plainly — there is no third-party account-linking API. JARVIS opens a chat with
  the text written; sending needs the accessibility tier.

Secrets live in the Android Keystore (`AES/GCM`), never in the repository and never in a server.

---

## Privacy, permissions and the lock

| Permission | Requested when | Needed for |
| --- | --- | --- |
| Microphone | first orb tap | voice |
| Contacts / Phone | the chips on home | “call dad”, messages |
| Camera | the camera chip | photo capture |
| Overlay | only if you enable the bubble | floating disc |
| Biometric | a normal permission, no prompt | fingerprint/face unlock shortcut |
| Photos and media | a normal runtime dialog | the gallery, your last photo, video |
| Location | a normal runtime dialog | where am I, distance, directions |
| Activity / body sensors | a normal runtime dialog | steps, heart rate where present |
| All files access | a special grant in system settings | finding any file by name |
| Accessibility | your explicit choice in settings | screen taps, screenshots, OCR |
| Notification listener | your explicit choice | alerts, media, `VERIFY` |

**The lock** is a numeric PIN over the app itself: PBKDF2-HMAC-SHA256, 120 000 iterations, random salt,
constant-time compare, no recovery. It re-engages a minute after you leave. The lock screen shows no app
names, no history and no last command.

**Which lock.** Android will not let an app replace the lock screen, so JARVIS offers two honest
choices in *Advanced → Which lock*: **your phone's own lock** (the system credential screen — the same
PIN, pattern or fingerprint you already use; JARVIS learns only pass or fail) or **a JARVIS keypad**
(a second, app-only gate). A separate switch posts a short line over the lock screen — steps, water,
unread messages — as a public notification, which is the only form Android allows.

**There is exactly one credential** — the PIN you choose the first time you open *Settings → Advanced →
Privacy lock*. There is no default, no second password and nothing shipped in the build. Change it by
turning the lock off and setting a new PIN.

**Fingerprint and face are a shortcut, not a second password.** When the phone has an enrolled sensor,
the lock screen offers the system prompt first (one automatic attempt, then a button). Android performs
the match in its own secure hardware and returns only pass/fail — JARVIS never sees, stores or transmits
biometric data, so there is nothing to extract. If the sensor is wet, busy or locked out, the keypad is
still there: biometrics can save typing the PIN, never replace it.

There is **no JARVIS backend**. No account, no sync, no analytics, no crash reporting. The only network
traffic is a request to the provider whose key you pasted, a connector you set up, or a URL you asked
for.

---

## Install it

Requires **Android 10 (API 29) or newer**. One package only (`com.jarvis.assistant`), so new builds
update in place.

### Codemagic (zero setup)

1. Add this repository in Codemagic and select **codemagic.yaml**.
2. Run **JARVIS · debug APK**, then download from the build artifacts:

   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

3. For a stable signature (so updates never conflict), add a keystore under *Code signing identities →
   Android keystore* as `keystore_credentials`, then use **JARVIS · release APK**:

   ```
   app/build/outputs/apk/release/app-release.apk
   ```

> Codemagic's free tier only includes macOS machines, so both workflows use `instance_type:
> mac_mini_m2`. `linux_x2` needs billing enabled and fails before the build starts on a free account.

### GitHub Actions

Every push runs the full pipeline: unit tests → debug + release APKs → APK signature and intent-filter
verification in the run summary → artifacts. The release artifacts are already named for a release:

```
jarvis-1.0.0.apk          (release — use this one)
jarvis-1.0.0-debug.apk    (debug)
```

### Android Studio / command line

```bash
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
./gradlew installDebug       # with a device connected over adb
```

Play Protect warns about any self-signed app — choose *Install anyway*. If a build was signed with a
different key, uninstall the old app first.

---

## Release it

Step by step, from this repository:

1. **Check the build is green** — Actions tab → latest **Build JARVIS APK** run → all steps passed.
2. **Download the release APK** — from the run's artifacts, `jarvis-release-apk` → `jarvis-1.0.0.apk`
   (or from Codemagic: `app/build/outputs/apk/release/app-release.apk`).
3. **Rename it if you like** — the release pipeline already names it `jarvis-<version>.apk`, which is
   the name to upload. Keep it exactly like that: `jarvis-1.0.0.apk`.
4. **Create the GitHub Release** — *Releases → Draft a new release* → tag `v1.0.0` → title
   `JARVIS 1.0.0` → paste the notes from [CHANGELOG.md](CHANGELOG.md).
5. **Attach the APK** to that release as an asset (drag `jarvis-1.0.0.apk` into the assets box).
   Pushing a `v*` tag also does this automatically: the workflow attaches `dist/jarvis-*.apk`.
6. **Verify**: the release page shows `jarvis-1.0.0.apk` plus *Source code* archives.

The file you upload is **`jarvis-1.0.0.apk`**; its source on the build machine is
**`app/build/outputs/apk/release/app-release.apk`**, and in CI **`dist/jarvis-1.0.0.apk`**.

---

## Tests

```bash
./gradlew testDebugUnitTest          # JVM, no device — runs in CI before the APKs are built
./gradlew connectedDebugAndroidTest  # one launch smoke test, needs a device or emulator
```

| Suite | What it pins |
| --- | --- |
| `VoiceParsingTest` | message / call / volume / alarm / timer / camera / reminder phrasing, and what must **not** match |
| `JsonReplyTest` | code fences, prose around the object, braces inside strings, invalid objects |
| `WavTest` | RIFF header sizes and format fields against the payload |
| `WorkflowJsonTest` | routine → JSON → routine, nested blocks, conditions, time windows, unique action ids |
| `TimeFormatTest` | 12-hour conversion, midnight and noon, stored `HH:mm` → `6:30 PM` |
| `AppLaunchTest` (androidTest) | the shell inflates and the HUD draws |

A failing test fails the build before an APK exists, and the run summary lists every suite.

---

## Stability and honest limits

**Designed against:** background work parks when the app is not visible; the accessibility tree walk is
depth- and count-limited; network calls all carry connect/read timeouts; the notification list and
terminal history are capped; every engine release is off the main thread.

**What the app will not do, and says so:**

- Always-on wake word — battery, privacy, and a microphone held open behind your back.
- A cloud backend, accounts or sync.
- Auto-sending SMS, call guarding or SOS.
- Scraped and unofficial APIs (WhatsApp linked-device, Instagram private endpoints).
- Claiming a call was placed when only the dialer opened, or a message sent when only the text was
  written.

**Best-effort by nature:** the screen agent reads labels and pixels, so a fully custom-drawn app can
still defeat it; six actions is a hard ceiling and it gives up out loud. Telegram and Instagram have no
“message this contact” link, so those stay open-the-app only.

---

## Design system

| Token | Value | Use |
| --- | --- | --- |
| Void Black | `#03070D` | App background |
| Deep Navy | `#091421` | Panels |
| Electric Cyan | `#16C8FF` | Primary glow, active elements |
| Ice Blue | `#8BEAFF` | Headings, highlights |
| Status Green | `#48E5A2` | Nominal / charging |
| Alert Red | `#FF465C` | Critical states only |
| Amber | `#FFC24B` | Caution / needs attention |

Monospace for every readout (numbers do not jitter), a light serif-flavoured display face for headings,
chamfered corners, hairline gradient borders and machined corner brackets on every panel. Red is used
sparingly, and only where something is genuinely wrong.

---

## Project layout

```
app/src/main/java/com/jarvis/assistant/
├── MainActivity.kt              single-activity host
├── ai/                          AiClient · AiPlanner · JsonReply · Translator · key store
├── automation/                  AutomationEngine · StepExecutor · Verify · triggers · conditions
├── connect/                     OAuth flows · Drive/Gmail · connector store
├── data/                        app registry · dock · command registry · HUD module list
├── device/                      status · controls · notifications · bubble · widget · privacy
│                                reminders · downloads · quick tiles · capture · health
├── shell/                       Shizuku user service
├── social/                      Instagram client, analytics, store
├── ui/                          JarvisApp · components · screens · theme
└── voice/                       VoiceController · IntentRouter · Ears/Whisper · LocalSpeech
                                 ScreenAgent · Ocr · VoiceMemory · VoiceParsing · Wav
app/src/test/                    JVM suites (parsers, JSON, WAV, workflow, time,
                                 biometric policy)
app/src/androidTest/             launch smoke test
```

What is left, and in what order, is tracked in **[ROADMAP.md](ROADMAP.md)**. Release notes live in
**[CHANGELOG.md](CHANGELOG.md)**.
