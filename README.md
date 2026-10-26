# J.A.R.V.I.S.

**Just A Rather Very Intelligent System** — a personal mobile intelligence interface for Android.

Package `com.jarvis.assistant` · Kotlin · Jetpack Compose · min SDK 29 (Android 10) · target SDK 34

This repository is the native Android app. It is a new, independent project with no dependency on any
earlier build.

[![Build JARVIS APK](https://github.com/Rishu123-png/Jarvis---Android-Version-/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Rishu123-png/Jarvis---Android-Version-/actions/workflows/build.yml)

> **Design preview:** open [`preview/jarvis-hud.html`](preview/jarvis-hud.html) in any browser to see the
> HUD layout, palette and reactor animation without installing anything.

---

## Where the project is right now

| Phase | Scope | Status |
| --- | --- | --- |
| **1** | HUD, arc reactor, clock, battery, tiles, theme, dashboard | Merged |
| 2 | Default launcher: home screen, app drawer, installed apps, launching | Merged |
| 3 | Device information and controls (notifications, quick actions) | Merged |
| 4 | Voice assistant: mic, speech recognition, spoken replies | Not started |
| 5 | AI intelligence: backend, planning, tool use | Not started |
| **6** | **Mobile automation with confirmation gates** | **Built — this branch (built ahead of 4 and 5)** |

Phase 2 makes JARVIS a real Home app: it can be selected as the Android launcher, its drawer lists
every launchable app on the device and opens them, and the dashboard carries a quick-access dock.
Voice, AI and automation remain labelled as staged — no fake features.

---

## What phase 1 actually does

**Live, real data**

- Clock and date, ticking on the wall-clock second boundary.
- Battery percentage, charge state, power source, temperature, voltage, health and technology, read
  from the `ACTION_BATTERY_CHANGED` system broadcast.
- Network transport, validation state and downstream estimate from `ConnectivityManager`.
- Memory used / total from `ActivityManager`, and device uptime from `SystemClock`.
- Device model, manufacturer, Android release, API level and ABI.

**Interface**

- Animated **Arc Reactor** HUD drawn entirely with Compose `Canvas`: 72-tick bezel, battery power arc,
  two counter-rotating rings, radial scale, scanning comet, breathing core bloom, orbiting markers.
- Chamfered panel system with machined corner brackets, hairline gradient borders and a technical
  grid backdrop with a reactor bloom and vignette.
- Identity bar, telemetry strip, six shortcut tiles, self-typing system log, voice affordance.
- Working **local terminal** (offline command table — not an AI) reached from the system log.
- Six module panels: Apps (staged), Network (live), Power (live), Diagnostics (live), Voice (staged),
  Automation (staged).
- Portrait layout that scrolls and scales from small phones to tall ones.

**Explicitly not in this build:** no launcher replacement, no notification access, no microphone, no
accessibility service, no network calls, no API keys, no analytics. The only manifest permission is
`ACCESS_NETWORK_STATE`, which is a normal (non-prompting) permission.

---

## Phase 6 — automation

Built ahead of phases 4 and 5 by request. The runtime is independent of voice and AI, so those
phases will plug into the same executor rather than replacing it.

### What it does

Routines are ordered lists of steps, stored on device as JSON. A routine runs top to bottom, one at
a time, with a 20-step ceiling. A failing step is logged and the run continues, so one unavailable
action does not strand the rest.

**Available steps:** open app · open link · dial number (dialer only, never calls) · compose SMS
(never sends) · set timer · torch on/off · set media volume · play/pause · clear alerts · open a
system panel · wait · system gesture (back/home/recents/shade/quick settings/lock) · press a labelled
control on screen.

### The safety model

| Tier | Requires | Grants |
| --- | --- | --- |
| Nothing | — | app launching, glances, timers, torch, volume, panels |
| Notification access | system switch | media transport, clearing alerts |
| Accessibility service | system switch | navigation gestures |
| Screen pressing | separate in-app opt-in | pressing one labelled control |

Every **sensitive** step (gestures, screen presses) suspends the routine on a `CompletableDeferred`
until you approve it. Decline halts the run. STOP cancels immediately and leaves nothing running.

The accessibility service's `onAccessibilityEvent` is intentionally empty: JARVIS never reads screen
content in the background. `pressByText` is exact-match only, bounded to the active window, and
refuses to guess.

### What automation deliberately cannot do

It will not place a call, send a message, or move money. Those steps open the relevant screen so the
final action stays yours. It also cannot control every app or system screen — a regular Android app
cannot, and the UI says so instead of implying otherwise.

---

## Phase 2 — launcher

- **Selectable as the Home app.** `MainActivity` declares `HOME` + `DEFAULT` alongside `LAUNCHER`, with
  `stateNotNeeded`, `launchMode=singleTask` and `excludeFromRecents` — the combination a Home app needs.
  JARVIS never switches the Home app itself: the user taps *Set JARVIS as home* in Launcher settings and
  Android shows its own confirmation dialog.
- **App drawer** — every launchable activity from `PackageManager`, alphabetical section headers, live
  search over label and package name, and a refresh control. Icons load off the main thread into an LRU
  cache, seeded synchronously so scrolling never flashes placeholders.
- **App launching** — explicit `MAIN`/`LAUNCHER` component intent with `FLAG_ACTIVITY_NEW_TASK` (required
  outside a task), falling back to `getLaunchIntentForPackage`, then a toast if the app cannot be opened.
- **Dock** — five slots filled from recognised system apps (dialer, messaging, browser, camera,
  settings), topped up from installed apps so it is never empty on any device.
- **Launcher settings** — live Home-role status, the system role request, the Home settings screen, and
  the app info page.
- **Package visibility** — the manifest declares a `<queries>` block. Without it, `queryIntentActivities`
  returns an empty list on Android 11+ and the drawer silently appears broken. That is the single most
  common launcher bug, and it is why the CI now verifies the APK's intent filters.

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
> can queue for a while on the free plan, so start the build and come back to it.
3. Optional but recommended: add a keystore under *Team settings → Code signing identities → Android
   keystore* (the group `keystore_credentials`), then use **JARVIS · release APK** from then on.

Both workflows tolerate a missing SDK package or keystore: they warn and continue rather than failing.
CI validates `codemagic.yaml` on every push — YAML syntax, every script step through `sh -n`, and a
check that no workflow requests a billing-gated instance type.

**Install notes**

- One package only: `com.jarvis.assistant`. There is no `.debug` suffix any more, so new builds update
  the installed app instead of creating a second icon.
- If you installed an older build that used `com.jarvis.assistant.debug`, uninstall it once.
- Uninstall the old app first if the signing key changed between builds — Android refuses an update
  signed by a different key ("package conflicts with an existing package"). A Codemagic keystore keeps
  the key stable forever.
- Play Protect warns about any self-signed app. Choose *Install anyway*.
- Requires Android 10 (API 29) or newer.

### Option B — GitHub Actions (no local toolchain needed)

Every push and pull request runs `.github/workflows/build.yml`, which installs JDK 17 + the Android
SDK, runs `./gradlew assembleDebug`, and uploads the APK.

1. Open the **Actions** tab → latest **Build JARVIS APK** run.
2. Download the `jarvis-debug-apk` artifact, unzip it, and install `app-debug.apk` on the phone
   (allow "install unknown apps" for your browser/file manager when prompted).

Push a tag like `v1.0.0-phase1` and the APK is also attached to a GitHub Release.

### Option C — Android Studio

Open the repository folder, let Gradle sync, press Run. Requires Android Studio with JDK 17 and the
Android 14 (API 34) SDK.

### Option D — command line

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # with a device connected over adb
```

> Debug and release share the same package and the same signing key when a keystore is configured, so
> you can always install a newer build straight over an older one.

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
| Amber | `#FFC24B` | Staged / caution |

Type is two families: **monospace** for every telemetry readout (numbers do not jitter, and it sells
the instrument-panel feel), and the **platform sans** for headings and prose. Small labels are wide-
tracked small caps.

Shapes are `ChamferedShape` — a rectangle with 45° cut corners, configurable per corner, so panels and
tiles never read as stock rounded cards.

---

## Project layout

```
app/src/main/java/com/jarvis/assistant/
├── MainActivity.kt              single-activity host
├── device/
│   ├── SystemStatus.kt          battery / network / memory / uptime / build info
│   └── LauncherStatus.kt        home role, role request, app launching
├── data/
│   ├── HudModules.kt            shortcut tile registry (id, accent, phase, live?)
│   ├── CommandRegistry.kt       offline terminal command table
│   ├── BootSequence.kt          boot log + rotating status notes
│   └── InstalledApps.kt         PackageManager index, icon LRU cache, dock picking
└── ui/
    ├── JarvisApp.kt             shell + string-route navigation
    ├── theme/                   Color, Type, Shape (ChamferedShape), Theme
    ├── components/
    │   ├── ArcReactor.kt        the centrepiece, pure Canvas
    │   ├── HudSurface.kt        panels, brackets, chips, readouts, meters, backdrop
    │   ├── HudText.kt           clock, typewriter, blinking cursor, pulse dot
    │   ├── Tiles.kt             shortcut tiles, telemetry stats, section headers
    │   └── AppIcons.kt          icon loading, app grid tiles, dock slots
    └── screens/
        ├── DashboardScreen.kt   the home HUD
        ├── ModuleScaffold.kt    shared module frame
        ├── TelemetryScreens.kt  Power, Network, Diagnostics
        ├── AppDrawerScreen.kt   installed apps, search, sections
        ├── LauncherSettingsScreen.kt  home role + build info
        ├── StagedScreens.kt     Voice, Automation, empty-drawer diagnostics
        └── TerminalScreen.kt    local command shell
```

The split is deliberate: `device/` owns Android APIs, `data/` owns the model, `ui/components/` owns
the visual language, `ui/screens/` only composes them. Phases 2–6 add packages beside these rather
than rewriting them.

---

## Phase 6 review checklist

- [ ] Presets run with no special access (torch, volume, timer, clear alerts)
- [ ] A routine with a gesture step asks for approval before acting, and Decline halts it
- [ ] STOP halts a running routine and the log shows HALTED
- [ ] Accessibility toggle reflects the real system state after you change it in settings
- [ ] Screen pressing stays blocked until you allow it separately
- [ ] Editor: add a step, set its value, reorder it, delete it, save, run
- [ ] A failing step (e.g. open an app you uninstalled) is logged and the run continues

## Phase 2 review checklist

- [ ] Launcher settings shows *not the home app*, and *Set JARVIS as home* opens Android's dialog.
- [ ] After choosing JARVIS, pressing Home opens the dashboard.
- [ ] The drawer lists your real apps with their real icons, grouped A–Z.
- [ ] Search filters as you type; the CLR chip clears it.
- [ ] Tapping any app opens it.
- [ ] The dock shows five apps and every one launches.
- [ ] Back from the drawer returns to the HUD.
- [ ] Pressing Home while inside another app returns to the HUD (not a blank screen).

## Phase 1 review checklist

- [ ] Dashboard opens with the reactor animating and the clock ticking.
- [ ] Battery percentage matches the system battery indicator.
- [ ] Power / Network / Diagnostics panels show plausible live values.
- [ ] Every tile responds; staged tiles say which phase they belong to.
- [ ] System log types itself out; `TERMINAL` opens and `help` lists commands.
- [ ] Back from any module returns to the HUD.
- [ ] Layout survives a small screen without clipping.

---

## Security and privacy posture

- No network permission, so this build cannot transmit anything.
- No sensitive runtime permission is held: no microphone, no notification access, no accessibility.
- No analytics, no crash reporting, no third-party SDKs.
- API keys for later phases belong in `local.properties` or CI secrets — never in the repo
  (`.gitignore` already excludes `secrets.properties`, `*.jks` and `.env`).

---

## Honest limits

Choosing JARVIS as the launcher will not replace Android. The lock screen, permission dialogs, system
navigation and protected screens stay with the OS. "Control every app and every screen" is not
possible for a regular app, and later phases will say so in the UI rather than over-promising.
