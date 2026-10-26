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
| **1** | HUD, arc reactor, clock, battery, tiles, theme, dashboard | **Built — this branch** |
| 2 | Default launcher: home screen, app drawer, installed apps, launching | Next |
| 3 | Device information and controls (notifications, quick actions) | Partly started (battery / network / memory are live) |
| 4 | Voice assistant: mic, speech recognition, spoken replies | Not started |
| 5 | AI intelligence: backend, planning, tool use | Not started |
| 6 | Mobile automation with confirmation gates | Not started |

Phase 1 is a complete, self-contained app: it launches, animates, reads real device state, and has
no placeholder code paths pretending to work. Tiles whose capability lands later are labelled with
their phase and open an honest "staged" panel instead of a fake feature.

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

## Build it

### Option A — GitHub Actions (no local toolchain needed)

Every push and pull request runs `.github/workflows/build.yml`, which installs JDK 17 + the Android
SDK, runs `./gradlew assembleDebug`, and uploads the APK.

1. Open the **Actions** tab → latest **Build JARVIS APK** run.
2. Download the `jarvis-debug-apk` artifact, unzip it, and install `app-debug.apk` on the phone
   (allow "install unknown apps" for your browser/file manager when prompted).

Push a tag like `v1.0.0-phase1` and the APK is also attached to a GitHub Release.

### Option B — Android Studio

Open the repository folder, let Gradle sync, press Run. Requires Android Studio with JDK 17 and the
Android 14 (API 34) SDK.

### Option C — command line

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # with a device connected over adb
```

> The debug build installs as `com.jarvis.assistant.debug` so it can sit next to a release install.

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
│   └── SystemStatus.kt          battery / network / memory / uptime / build info
├── data/
│   ├── HudModules.kt            shortcut tile registry (id, accent, phase, live?)
│   ├── CommandRegistry.kt       offline terminal command table
│   └── BootSequence.kt          boot log + rotating status notes
└── ui/
    ├── JarvisApp.kt             shell + string-route navigation
    ├── theme/                   Color, Type, Shape (ChamferedShape), Theme
    ├── components/
    │   ├── ArcReactor.kt        the centrepiece, pure Canvas
    │   ├── HudSurface.kt        panels, brackets, chips, readouts, meters, backdrop
    │   ├── HudText.kt           clock, typewriter, blinking cursor, pulse dot
    │   └── Tiles.kt             shortcut tiles, telemetry stats, section headers
    └── screens/
        ├── DashboardScreen.kt   the home HUD
        ├── ModuleScaffold.kt    shared module frame
        ├── TelemetryScreens.kt  Power, Network, Diagnostics
        ├── StagedScreens.kt     Apps, Voice, Automation
        └── TerminalScreen.kt    local command shell
```

The split is deliberate: `device/` owns Android APIs, `data/` owns the model, `ui/components/` owns
the visual language, `ui/screens/` only composes them. Phases 2–6 add packages beside these rather
than rewriting them.

---

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

- No network permission, so the phase 1 build cannot transmit anything.
- No sensitive runtime permission is held: no microphone, no notification access, no accessibility.
- No analytics, no crash reporting, no third-party SDKs.
- API keys for later phases belong in `local.properties` or CI secrets — never in the repo
  (`.gitignore` already excludes `secrets.properties`, `*.jks` and `.env`).

---

## Honest limits

Choosing JARVIS as the launcher will not replace Android. The lock screen, permission dialogs, system
navigation and protected screens stay with the OS. "Control every app and every screen" is not
possible for a regular app, and later phases will say so in the UI rather than over-promising.
