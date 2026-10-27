# J.A.R.V.I.S.

A native Android HUD — *Just A Rather Very Intelligent System*. Dark, high-contrast, drawn with
Compose Canvas rather than stock Material cards. It can be set as your Home app, so the HUD replaces
the launcher instead of sitting behind it.

Package `com.jarvis.assistant` · Kotlin · Jetpack Compose · minSdk 29 (Android 10) · targetSdk 34

---

## What works right now

| Area | State |
| --- | --- |
| HUD, arc reactor, live telemetry, clock | Working |
| Home app role, app drawer, launching apps | Working |
| Notifications, torch, volume, media, system panels | Working |
| Editable dock, storage telemetry | Working |
| Automation routines with confirmation gates | Working |
| Intelligence: your own AI key, any supported provider | Working |
| Voice input and spoken replies | Not built |

Every tile on the HUD is either backed by a real Android API or clearly marked as not built. Nothing
in the interface imitates a capability the app does not have.

---

## Intelligence — bring your own key

There is **no JARVIS server**. You paste your own provider key in *Settings → INTELLIGENCE*, and
requests go from the phone straight to that provider.

**Supported:** OpenAI · Anthropic · Google Gemini · Groq · xAI · OpenRouter · Mistral · any
OpenAI-compatible endpoint (self-hosted, proxy, or a provider not listed).

The provider is inferred from the key's prefix, so pasting `sk-ant-…` selects Anthropic and
`AIza…` selects Gemini automatically. The picker stays available if the guess is wrong, models are
editable, and custom endpoints take a base URL.

**Handling.** The key is encrypted with the Android Keystore (AES-256-GCM) before it touches disk;
only a masked `••••1234` is ever displayed. It is never logged, never written to the repo, and
exists in plaintext only for the duration of a request. Uninstalling the app or clearing its data
destroys the encryption key with it.

**Model names are the fragile part.** Providers retire model ids without warning, so no name is
trusted: **FETCH MODELS** asks the provider what your key can actually reach and lists it, and a
model rejection (HTTP 404) makes the client walk the provider's known alternatives, then remember
the one that answered. A retired model cannot silently break a working setup. **Failure is explicit.** A bad key, a missing model, a rate limit, a timeout or a dead network each
produce a sentence you can act on. Connect and read timeouts are fixed, one request is in flight at
a time, and the request is cancelled when you leave the screen — a slow provider cannot leave the
panel stuck.

---

## Terminal — real commands

The console runs real work against real Android APIs. It is not a shell emulator, and it does not
print scripted text.

```
status      battery     network    storage     apps       alerts [clear]
torch [on|off]          volume [0-100|up|down]          time
panel wifi|bt|dnd|display|volume|notification|battery
routines    run <name>  stop       modules    ai         help
```

Anything that is not a local command is offered to your AI key instead. When the model suggests a
local action, it appears as a chip you have to tap — the model never executes anything by itself.

History is capped, so a long session cannot grow the list without bound.

---

## Automation

Routines are ordered step lists stored as JSON on the device. They run top to bottom, one at a time,
with a 20-step ceiling. A failing step is logged and the run continues, so one unavailable action
does not strand the rest.

**Steps:** open app · open link · dial number (dialer only, never calls) · compose SMS (never sends) ·
set timer · torch on/off · set media volume · play/pause · clear alerts · open a system panel · wait ·
system gesture (back/home/recents/shade/quick settings/lock) · press a labelled control on screen.

### The safety model

| Tier | Requires | Grants |
| --- | --- | --- |
| Nothing | — | app launching, links, dialer, SMS composer, timer, torch, volume, panels |
| Notification access | system switch | media transport, clearing alerts |
| Accessibility service | system switch | navigation gestures |
| Screen pressing | separate in-app opt-in | pressing one labelled control |

Every **sensitive** step suspends the routine until you approve it. Decline halts the run. STOP
cancels immediately and leaves nothing running.

The accessibility service's `onAccessibilityEvent` is intentionally empty: JARVIS never reads screen
content in the background. `pressByText` is exact-match only, bounded to the active window, and
refuses to guess.

### What automation deliberately cannot do

It will not place a call, send a message, or move money. Those steps open the relevant screen so the
final action stays yours. It also cannot control every app or protected system screen — a regular
Android app cannot, and the UI says so instead of implying otherwise.

---

## Launcher

- **Selectable as the Home app.** `MainActivity` declares `HOME` + `DEFAULT` alongside `LAUNCHER`, with
  `stateNotNeeded`, `launchMode=singleTask` and `excludeFromRecents` — the combination a Home app needs.
  JARVIS never switches the Home app itself: you tap *Set JARVIS as home* and Android shows its own
  confirmation dialog.
- **App drawer** — every launchable activity from `PackageManager`, alphabetical section headers, live
  search over label and package name, and a refresh control. Icons load off the main thread into an LRU
  cache, seeded synchronously so scrolling never flashes placeholders.
- **App launching** — explicit `MAIN`/`LAUNCHER` component intent with `FLAG_ACTIVITY_NEW_TASK`, falling
  back to `getLaunchIntentForPackage`, then a toast if the app cannot be opened.
- **Dock** — five slots filled from recognised system apps (dialer, messaging, browser, camera,
  settings), topped up from installed apps so it is never empty on any device. Long-press a slot to
  change it.
- **Package visibility** — the manifest declares a `<queries>` block. Without it, `queryIntentActivities`
  returns an empty list on Android 11+ and the drawer silently appears broken. That is the single most
  common launcher bug, which is why CI verifies the APK's intent filters.

---

## Stability

The HUD is a live surface, so the failure mode to design against is a janky or frozen screen:

- Periodic work (clock, telemetry, media polling) parks while the app is in the background.
- Automation steps, including the bounded accessibility-tree walk, run on a background dispatcher —
  never on the main thread.
- Overlapping app refreshes on resume are gated, so rapid foreground passes cannot queue up work.
- The live notification list and terminal history are both capped.
- Every network call has explicit connect and read timeouts and is cancellable.
- Anything that can throw (package manager, keystore, accessibility tree, provider JSON) is wrapped
  and degraded to a readable state rather than a crash.

---

## Build it

### Option A — Codemagic (recommended for installing on your phone)

`codemagic.yaml` at the repository root defines two workflows:

| Workflow | Setup needed | Use |
| --- | --- | --- |
| **JARVIS · debug APK** | none | download and install right away |
| **JARVIS · release APK** | a keystore in Codemagic | stable signature so updates install over each other |

1. Add the repository in Codemagic and select **codemagic.yaml** as the configuration source.
2. Run **JARVIS · debug APK**, then download `app-debug.apk` from the build's artifacts.

> **Free plan note.** Codemagic's free tier only includes macOS machines, so both workflows use
> `instance_type: mac_mini_m2`. Using `linux_x2` on a free account fails *before the build starts*
> with "The selected instance type is not available with the current billing plan". Android builds
> work perfectly on the Mac machine; switch to `linux_x2` only if you enable billing. macOS builds
> can queue for a while on the free plan.

3. Optional but recommended: add a keystore under *Team settings → Code signing identities → Android
   keystore* (the group `keystore_credentials`), then use **JARVIS · release APK** from then on.

Both workflows tolerate a missing SDK package or keystore: they warn and continue rather than failing.
CI validates `codemagic.yaml` on every push — YAML syntax, every script step through `sh -n`, and a
check that no workflow requests a billing-gated instance type.

**Install notes**

- One package only: `com.jarvis.assistant`. New builds update the installed app instead of creating a
  second icon.
- Uninstall the old app first if the signing key changed between builds — Android refuses an update
  signed by a different key. A Codemagic keystore keeps the key stable forever.
- Play Protect warns about any self-signed app. Choose *Install anyway*.
- Requires Android 10 (API 29) or newer.

### Option B — GitHub Actions (no local toolchain needed)

Every push and pull request runs `.github/workflows/build.yml`, which installs JDK 17 + the Android
SDK, runs `./gradlew assembleDebug assembleRelease`, validates `codemagic.yaml`, verifies the APK's
signature and intent filters into the run summary, and uploads the APKs.

1. Open the **Actions** tab → latest **Build JARVIS APK** run.
2. Download `jarvis-debug-apk`, unzip it, install `app-debug.apk` (allow "install unknown apps" when
   prompted).

### Option C — Android Studio

Open the repository folder, let Gradle sync, press Run. Requires JDK 17 and the Android 14 (API 34)
SDK.

### Option D — command line

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # with a device connected over adb
```

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

Type is two families: **monospace** for every telemetry readout (numbers do not jitter, and it sells
the instrument-panel feel), and the **platform sans** for headings and prose. Small labels are
wide-tracked small caps.

Shapes are `ChamferedShape` — a rectangle with 45° cut corners, configurable per corner, so panels and
tiles never read as stock rounded cards.

---

## Project layout

```
app/src/main/java/com/jarvis/assistant/
├── MainActivity.kt              single-activity host
├── ai/
│   ├── AiProvider.kt            provider table, wire formats, key detection
│   ├── AiKeyStore.kt            Keystore-encrypted key storage
│   └── AiClient.kt              HTTP client for the three wire formats
├── automation/
│   ├── AutomationModels.kt      actions, steps, workflows, JSON
│   ├── AutomationStore.kt       persistence + presets
│   ├── StepExecutor.kt          one step, against real APIs
│   ├── AutomationEngine.kt      sequential runner, confirmation gate, run log
│   └── JarvisAccessibilityService.kt
├── device/
│   ├── SystemStatus.kt          battery / network / memory / uptime / build info
│   ├── LauncherStatus.kt        home role, role request, app launching
│   ├── Notifications.kt         listener repository + access checks
│   ├── QuickControls.kt         torch, volume, media session, system panels
│   └── StorageStatus.kt         StatFs telemetry
├── data/
│   ├── HudModules.kt            shortcut tile registry
│   ├── CommandRegistry.kt       terminal commands + AI system prompt
│   ├── BootSequence.kt          boot log + rotating status notes
│   ├── DockPrefs.kt             persisted dock
│   └── InstalledApps.kt         PackageManager index, icon LRU cache
└── ui/
    ├── JarvisApp.kt             shell + string-route navigation
    ├── theme/                   Color, Type, Shape (ChamferedShape), Theme
    ├── components/              ArcReactor, HudSurface, HudText, Tiles, AppIcons, Lifecycle
    └── screens/
        ├── DashboardScreen.kt   the home HUD
        ├── ModuleScaffold.kt    shared module frame
        ├── TelemetryScreens.kt  Power, Network, Diagnostics
        ├── AppDrawerScreen.kt   installed apps, search, sections
        ├── AutomationScreen.kt  routines, run state, log
        ├── AutomationEditorScreen.kt
        ├── AiSettingsScreen.kt  provider, model, key
        ├── TerminalScreen.kt    device console
        └── …
```

The split is deliberate: `device/` owns Android APIs, `ai/` owns provider traffic, `data/` owns the
model, `ui/components/` owns the visual language, and `ui/screens/` only composes them.

---

## Review checklists

**Intelligence**

- [ ] Paste a key in Settings → INTELLIGENCE; the detected provider matches the key.
- [ ] TEST CONNECTION reports the provider and model, or a specific error.
- [ ] Ask a question in the terminal and get a reply attributed to the provider.
- [ ] A suggested `RUN:` chip only runs after you tap it.
- [ ] FORGET KEY removes it and the terminal falls back to local-only.

**Automation**

- [ ] Presets run with no special access (torch, volume, timer, clear alerts).
- [ ] A gesture step asks for approval, and Decline halts the run.
- [ ] STOP halts a running routine and the log shows HALTED.
- [ ] Accessibility status reflects the real system state after you change it in settings.
- [ ] Screen pressing stays blocked until you allow it separately.
- [ ] Editor: add → configure → reorder → save → run.
- [ ] A failing step is logged and the run continues.

**Launcher**

- [ ] Settings shows *not the home app*; *Set JARVIS as home* opens Android's dialog.
- [ ] After choosing JARVIS, pressing Home opens the dashboard.
- [ ] The drawer lists real apps with real icons, grouped A–Z; search filters as you type.
- [ ] Every dock slot launches.
- [ ] Pressing Home inside another app returns to the HUD, not a blank screen.

**HUD**

- [ ] The reactor animates and the clock ticks.
- [ ] Battery matches the system indicator; telemetry panels show plausible live values.
- [ ] System log types itself out; `help` lists commands.
- [ ] Back from any module returns to the HUD.
- [ ] Layout survives a small screen without clipping.

---

## Security and privacy posture

- No JARVIS backend, no analytics, no crash reporting, no third-party SDKs.
- `INTERNET` exists for one reason: sending your prompt to the provider whose key you stored. With no
  key configured, the app makes no network request at all.
- The AI key is encrypted with the Android Keystore and sent only to that provider.
- No microphone permission is requested. Notification access and accessibility are optional, off by
  default, revoked by a system switch you control.
- Secrets never belong in the repository — `.gitignore` excludes `secrets.properties`, `*.jks`,
  `keystore.properties` and `.env`.

---

## Honest limits

Choosing JARVIS as the launcher will not replace Android. The lock screen, permission dialogs, system
navigation and protected screens stay with the OS. It is a regular app: no root, no kernel access, no
monitor mode, no shell inside another app's sandbox. Where a capability is impossible, the interface
says so rather than pretending.

Voice input is not built in this version.
