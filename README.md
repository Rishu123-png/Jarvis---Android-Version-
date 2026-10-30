<div align="center">

<img src=".github/assets/banner.png" alt="J.A.R.V.I.S." width="100%" />

<br />

<svg width="120" height="120" viewBox="0 0 120 120" role="img" aria-label="Arc reactor">
  <defs>
    <radialGradient id="core" cx="50%" cy="50%" r="50%">
      <stop offset="0%" stop-color="#EAFDFF"/>
      <stop offset="45%" stop-color="#16C8FF"/>
      <stop offset="100%" stop-color="#0A3D57"/>
    </radialGradient>
    <filter id="glow">
      <feGaussianBlur stdDeviation="2.4" result="b"/>
      <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
    </filter>
  </defs>
  <g filter="url(#glow)">
    <circle cx="60" cy="60" r="52" fill="none" stroke="#16C8FF" stroke-opacity="0.18" stroke-width="1"/>
    <g>
      <circle cx="60" cy="60" r="44" fill="none" stroke="#16C8FF" stroke-opacity="0.55"
              stroke-width="1.5" stroke-dasharray="10 8">
        <animateTransform attributeName="transform" type="rotate"
                          from="0 60 60" to="360 60 60" dur="14s" repeatCount="indefinite"/>
      </circle>
    </g>
    <g>
      <circle cx="60" cy="60" r="34" fill="none" stroke="#8BEAFF" stroke-opacity="0.5"
              stroke-width="1.2" stroke-dasharray="3 14" stroke-linecap="round">
        <animateTransform attributeName="transform" type="rotate"
                          from="360 60 60" to="0 60 60" dur="9s" repeatCount="indefinite"/>
      </circle>
    </g>
    <g>
      <circle cx="60" cy="60" r="24" fill="none" stroke="#16C8FF" stroke-opacity="0.7" stroke-width="1"/>
      <circle cx="60" cy="60" r="24" fill="none" stroke="#8BEAFF" stroke-width="2"
              stroke-dasharray="6 44" stroke-linecap="round">
        <animateTransform attributeName="transform" type="rotate"
                          from="0 60 60" to="360 60 60" dur="3.2s" repeatCount="indefinite"/>
      </circle>
    </g>
    <circle cx="60" cy="60" r="12" fill="url(#core)">
      <animate attributeName="r" values="11;13;11" dur="2.6s" repeatCount="indefinite"/>
      <animate attributeName="opacity" values="0.85;1;0.85" dur="2.6s" repeatCount="indefinite"/>
    </circle>
  </g>
</svg>

# J.A.R.V.I.S.

**A family member who lives on your phone — not an assistant that waits for commands.**

[![Build](https://github.com/Rishu123-png/Jarvis---Android-Version-/actions/workflows/build.yml/badge.svg)](https://github.com/Rishu123-png/Jarvis---Android-Version-/actions/workflows/build.yml)
![Version](https://img.shields.io/badge/version-1.0.0-16C8FF?style=flat-square)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Tests](https://img.shields.io/badge/tests-28%20suites-48E5A2?style=flat-square)
![No backend](https://img.shields.io/badge/backend-none-FF465C?style=flat-square)

**Built by [Rishu Jaswar](https://github.com/Rishu123-png).**

*Ask him anything. He remembers.*

</div>

---

```
> jarvis, how far is india gate

  DISTANCE · INDIA GATE
  ─────────────────────────────────────────────
  India Gate is 8.4 kilometres away by road.
  ─────────────────────────────────────────────
```

He knows the difference between **"open gallery"**, **"gallery kholo"** and **"show me my photos"**.
He knows your sister's name because you told him once. He knows it is half past eleven at night and
answers accordingly. He answers in the language you spoke to him.

And when he cannot do something, he says so — precisely, and with the reason.

---

## Table of contents

- [What he actually is](#what-he-actually-is)
- [The brain](#the-brain)
- [The curiosity](#the-curiosity)
- [The eyes](#the-eyes)
- [What he can do](#what-he-can-do)
- [The lock, honestly](#the-lock-honestly)
- [Install the APK](#install-the-apk)
- [Build it yourself](#build-it-yourself)
- [What he cannot do](#what-he-cannot-do)
- [Architecture](#architecture)
- [Privacy](#privacy)
- [Tests](#tests)
- [Release checklist](#release-checklist)

---

## What he actually is

A full Android launcher, an automation engine, and a conversation partner in one app. He replaces
your home screen, speaks with you, acts on your phone, and keeps a small, local memory of the things
that matter to you.

Two ideas shape every design decision in this repository:

> **He never lies.** If a message was not sent, he says it was not sent. If a sensor is missing, he
> says the phone has no sensor. There is no "done" that did not happen, anywhere in the codebase.

> **He is family, not a product.** No account. No sync. No analytics. No telemetry. Your memory of
> him is a preferences file on your phone that you can read in *Advanced → What he remembers* — and
> wipe with one key.

---

## The brain

Most "AI assistants" are a command table with a microphone bolted on. Say the wrong words and they
collapse. JARVIS has four layers, and each one catches what the one above it missed.

<table>
<tr><td width="33%" valign="top">

### 1 · The ear
**~400 phrasings, ~50 intents**

Exact → prefix → word-set → Jaro-Winkler fuzzy.

`open galery` `gallery kholo`
`kitne steps` `tumhe kisne banaya`

A miss returns *"did you mean Photos?"* — never silence.

</td><td width="33%" valign="top">

### 2 · The conversation
**Free speech, not JSON**

Ask him to *do* something and he plans it. Talk to *him* and he answers with sentences. He knows the
time, the battery, your next event and who has messaged you — and he uses it.

</td><td width="33%" valign="top">

### 3 · The memory
**Local, inspectable, erasable**

*"my sister is Khushi"* → remembered.
*"I like tea"* → remembered.
*"do that for her"* → he knows who.

Stored in one preferences file. Nothing leaves the phone.

</td></tr>
<tr><td valign="top">

### 4 · The judgment
**Knows when to speak**

Adverts get one dry line, then a day of silence. Real messages get read aloud with three suggested
replies. Quiet hours are quiet. The brief happens once every three hours, and never when there is
nothing to say.

</td><td valign="top">

### 5 · The honesty
**Never a dead end**

Every reply ends in one of three states: done, *"I need one thing from you"*, or *"I cannot, and here
is why."* A provider name, an API key or an "as an AI language model" never reaches your ears —
scrubbed before speech.

</td><td valign="top">

### 6 · The language
**Answered in the language asked**

English, Hindi and Hinglish work fully **offline**. Every other language comes back in that language
through the model, with the matching text-to-speech voice.

</td></tr>
</table>

---

## The curiosity

He can go and find something out — and he asks first, because that is the only thing he does that
leaves the phone.

Say *"look up Nikola Tesla"*, *"what is a black hole"*, *"photosynthesis kya hai"* or *"define
serendipity"* and he answers from a named source: Wikipedia's REST API and a free dictionary. **No
API key, no account, no bill.** Those two endpoints are public, documented, and free at any volume a
person can speak.

| | |
| --- | --- |
| **He asks first** | Three positions, on the Knowledge page: *ask everything*, *ask once per topic* (default), *don't ask*. Nothing is fetched before a yes. |
| **He remembers** | Every answer is kept as a card — topic, two sentences, source, date. Ask again and it is instant, offline, and silent. |
| **He summarises without a model** | The paragraph is cut to the two sentences that answer *your* question by counting overlapping words and rewarding the opening sentence. Dumb, deterministic, and it needs no download. |
| **It is all visible** | The Knowledge page lists every card. Delete one, or clear the shelf; the words he learned about you are separate and untouched. |
| **What it cannot do** | Log in anywhere, post, send, follow a link it was not given, or reach a site that forbids it. One JSON document in, two fields read, stop. |

With an AI key stored, the model still answers general conversation — the shelf is the part that
works with nothing configured at all, and the part that makes the next identical question free.

---

## The eyes

He can look at something and tell you about it — on demand, never in the background.

Say *"look at this"*, *"read this for me"*, *"what is this"*, *"yeh kya hai"* or *"इसे पढ़ो"* and he opens
his capture surface for a second, takes one frame, shrinks it, and answers the question you actually
asked. A menu, a sign, a medicine strip, a bill, a page of homework, a plant that is dying.

| | |
| --- | --- |
| **Only when asked** | A sentence has to ask for it. No camera runs in the background, no sensor is held open, no extra service. A photo you did not ask for is a photo he never takes. |
| **Small on purpose** | A 4000×3000 frame is subsampled by powers of two *at decode time* — the full-size bitmap is never in memory — then capped at 1024 px and re-encoded. One second on mobile data instead of ten. |
| **It refuses to guess** | Blurry photo, dark room, unreadable label: he says so and asks for another, instead of inventing a dosage or a name. That instruction is in the prompt, in as many words. |
| **Your key, your bill** | This uses the AI provider you already configured. Nothing new to sign up for, nothing extra to pay. |
| **The photo stays yours** | The frame is written to your gallery by the capture surface, as every photo he takes is. Nothing is kept for later, and nothing is uploaded anywhere except that one image to your own provider. |

---

## What he can do

<table>
<tr><th align="left" width="30%">If you say…</th><th align="left">He does</th></tr>

<tr><td><b>Open anything</b></td><td>

`open gallery` · `files kholo` · `open wifi settings` · `open Instagram`

System intents, skin-specific packages and fuzzy labels — so it works on ColorOS, MIUI, One UI and
stock alike.</td></tr>

<tr><td><b>Talk to people</b></td><td>

`send message Khushi saying hi` · `call dad` · `reply to Khushi "on my way"`

Resolves the person from contacts, memory or a live chat. Replies through the notification's own
`RemoteInput` — the supported path. Verifies the chat before pressing send, and says so when unsure.</td></tr>

<tr><td><b>Messages that matter</b></td><td>

`what did I miss` · `read my notifications` · `reply second one`

Reads real messages with three fitting replies. Ignores Flipkart-style adverts after one line. Reads
OTPs with the code.</td></tr>

<tr><td><b>Find anything</b></td><td>

`find my aadhaar pdf` · `open the newest download`

Whole-phone search with All-files access, falling back to indexed media. Opens when there is exactly
one match; lists them when there are several.</td></tr>

<tr><td><b>Know where</b></td><td>

`how far is India Gate` · `where am I` · `take me to Connaught Place`

Road distance through OSRM. Says *straight line* out loud when the router cannot be reached — never
presents crow-flight as driving distance.</td></tr>

<tr><td><b>Health</b></td><td>

`how many steps` · `I drank a glass of water` · `heart rate` · `how did I sleep`

Hardware sensors with hardware honesty: no sensor means he says so. Water is only what you logged.
Sleep is a screen-off estimate, labelled as one.</td></tr>

<tr><td><b>Think</b></td><td>

`12 percent of 4500` · `5 km to miles` · `50 c to f`

Offline. No key, no network, no waiting.</td></tr>

<tr><td><b>The day</b></td><td>

`what's my schedule` · `add meeting with Amit tomorrow at 4` · `save this number as Khushi`

Your real calendar, your real contacts. Spoken time parsed properly: `tomorrow at 4`,
`kal shaam 6 baje`.</td></tr>

<tr><td><b>Do it again</b></td><td>

`do that for Khushi` · `and tomorrow` · `make it 8:30`

Follow-ups resolved against the previous turn — the thing that stops a conversation feeling like a
form.</td></tr>

<tr><td><b>Capture</b></td><td>

`screenshot` · `take a photo` · `record a note for 30 seconds`

Photos to `Pictures/JARVIS`, notes to `Music/JARVIS`.</td></tr>

<tr><td><b>Routines</b></td><td>

Full automation engine: triggers, conditions, control flow, retries, run history, and **19 action
types** available to both routines and voice.</td></tr>

</table>

---

## The lock, honestly

Android does not allow any app to replace the lock screen or draw on it permanently — lockscreen
widgets were removed in Android 5.0. Rather than pretend, JARVIS offers the two things that are real:

| Mode | What happens |
|---|---|
| **PHONE LOCK** *(recommended)* | Your phone's own credential screen — PIN, pattern, password or fingerprint. JARVIS learns only *pass* or *fail*. |
| **JARVIS KEYPAD** | A second, app-only gate: PBKDF2-HMAC-SHA256, 120 000 iterations, random salt, constant-time compare. |
| **Lock-screen line** | A public-visibility notification over the keyguard: steps, water, unread messages. The only form Android permits. |

Fingerprint and face are a **shortcut, never a second password**. Android matches in secure hardware;
nothing biometric reaches this app.

---

## Install the APK

### For everyone else — one link

The APK lives on the **Releases** page, not in the repository:

```
https://github.com/Rishu123-png/Jarvis---Android-Version-/releases/latest
```

Pick **`jarvis-1.0.0.apk`** → download → open it on the phone → allow *Install unknown apps* once.

That is the file to share. Anyone who opens that link can install it without a GitHub account.

### Where the file comes from

| Location | Path |
|---|---|
| GitHub Actions run | **Artifacts** → `jarvis-release-apk` → `jarvis-1.0.0.apk` |
| Build machine | `app/build/outputs/apk/release/app-release.apk` |
| CI copy | `dist/jarvis-1.0.0.apk` |
| GitHub Release | attach `jarvis-1.0.0.apk` to tag `v1.0.0` |

### First run

1. Set JARVIS as the **home app** when asked.
2. *Settings → Advanced → ACCESS* — grant what you want him to be able to do. Each row states what it
   unlocks and what breaks without it.
3. *Settings → Intelligence* — paste your own AI key (Groq, OpenAI, Anthropic, Gemini, or any
   OpenAI-compatible endpoint). Without one, he still works: a local phrase engine answers offline.
4. Tap the arc reactor and speak.

> **No key? Still useful.** Call, message, open, torch, volume, alarm, timer, photos, calculator,
> steps, water, calendar, notifications, and full conversation small-talk all work with no key at all.

---

## Build it yourself

```bash
git clone https://github.com/Rishu123-png/Jarvis---Android-Version-.git
cd Jarvis---Android-Version-
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # 22 JVM suites
```

Requires JDK 17 and Android SDK 34. Every push runs the same thing in CI.

---

## What he cannot do

Listed here rather than buried in an issue tracker, because an assistant you cannot trust is worse than
no assistant.

| Not possible | Why |
|---|---|
| Replace the lock screen | Removed from Android in 5.0. No API exists. |
| Read your PIN, pattern or fingerprint | Only *pass* or *fail* is ever returned to an app. |
| Send WhatsApp with zero taps when there is no live notification | App sandbox. His route is notification reply → `wa.me` link → in-app search. |
| Always-on wake word | Would drain the battery and hold the microphone permanently. He listens when tapped. |
| Run without your AI key for free-form conversation | Small-talk works offline; a real conversation needs a model you own. |
| Publish a website or run a server | Out of scope by design. He is the brain on your phone, not a hosting company. |

**Verified by CI:** compilation, 22 test suites, both APKs, manifest integrity.
**Needs your phone:** WhatsApp sending on your build, notification replies, the ColorOS gallery name,
step/heart sensors, the system credential screen, TTS voices.

---

## Architecture

```
app/src/main/java/com/jarvis/assistant/
├── MainActivity.kt               single activity, Compose host
├── ai/                           your key, any provider, JSON + free conversation
├── automation/                   the engine: 19 actions, conditions, triggers, screen agent
├── connect/                      Instagram, Google Drive + Gmail (read-only), OAuth
├── data/                         PhraseBook (~400 phrasings) · Units · When · command line
├── device/                       the phone itself: files, gallery, location, health, calendar,
│                                 contacts, notifications, voice notes, lock, biometrics
├── shell/                        Shizuku user service (optional, adb-level, no root)
├── social/                       Instagram analytics
├── ui/                           Home · Dashboard · Lock · Privacy · Terminal · Voice
└── voice/                        Brain (memory) · IntentRouter · Language · Persona · FollowUp
```

**The turn, end to end:**

```
  tap ──► Ears (Whisper or on-device)
          │
          ▼
      Language.detect ──► EN / HI / HINGLISH / …
          │
          ▼
      FollowUp.rewritten      "do that for her"  ──►  "message Khushi I will be late"
          │
          ▼
      IntentRouter.plan
          ├── identity  ──► answered locally. Never a model.
          ├── PhraseBook ──► local steps (no key needed)
          └── Brain context + AI ──► JSON steps  OR  plain conversation
          │
          ▼
      StepExecutor  (25 s ceiling, honest failure)
          │
          ▼
      Brain.remember + absorb      "my sister is Khushi" ──► memory
          │
          ▼
      SpeechOut in the detected language
```

---

## Privacy

| | |
|---|---|
| **Backend** | None. There is no JARVIS server, no account, no sync. |
| **Network** | Only: your own AI key's provider, one optional router for road distance, a connector you set up, or a URL you asked for. |
| **Memory** | A preferences file on this phone. Read it in Advanced; wipe it with one key. |
| **Keys** | Android Keystore (`AES/GCM`). Never in this repository. |
| **Notifications** | Held in memory only, dropped when the listener disconnects. |
| **Photo, microphone, screen** | Only while you are using the feature that needs them. |

Permissions are not hidden in a settings page nobody opens — *Advanced → ACCESS* lists every
capability, what it unlocks, and what breaks without it.

---

## Tests

22 JVM suites, run before every APK in CI.

| Suite | Pins down |
|---|---|
| `PhrasesTest` | ~400 phrasings including typos and Hinglish |
| `LanguageTest` | script detection, Hinglish word vote, reply templates |
| `BrainTest` | what is remembered, and what must **not** be |
| `FollowUpTest` | "do that for Khushi", "and tomorrow", "make it 8:30" |
| `NotifyBrainTest` | advert vs message vs OTP, quiet hours, reply suggestions |
| `PersonaTest` | provider names and keys never reach speech |
| `WhenTest` | spoken time → timestamp, with no guessing |
| `UnitsTest` | percentages, conversions, spoken operators |
| `DistanceTest` | haversine, road-distance phrasing |
| `HealthMathTest` | step baselines, sleep estimate caps |
| `NudgesTest` | briefs that should stay silent |
| `BiometricPolicyTest` | when the sensor may fire on its own |
| `TimeFormatTest` · `VoiceParsingTest` · `JsonReplyTest` · `WavTest` · `WorkflowJsonTest` | the rest of the pure logic |

---

## Release checklist

```bash
# 1. version stays 1.0.0; only versionCode moves
#    app/build.gradle.kts  →  versionCode = 20

# 2. green CI on main
gh run list --limit 1

# 3. tag it — CI attaches the APK to the release automatically
git tag v1.0.0 && git push origin v1.0.0
```

Then edit the draft release, paste the notes from [CHANGELOG.md](CHANGELOG.md), and the download link
above works for everyone.

---

<div align="center">

**Built by Rishu Jaswar.**

*"Sometimes you gotta run before you can walk."*

<sub>Kotlin · Jetpack Compose · Material 3 · minSdk 29 · no backend, no account, no telemetry</sub>

</div>
