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
![Version](https://img.shields.io/badge/version-1.0.1-16C8FF?style=flat-square)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Tests](https://img.shields.io/badge/tests-60%20suites-48E5A2?style=flat-square)
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
- [The core — the brain on your PC](#the-core--the-brain-on-your-pc)
- [The curiosity](#the-curiosity)
- [The sky](#the-sky)
- [The assistant button](#the-assistant-button)
- [Hey Jarvis](#hey-jarvis)
- [Staying in the conversation](#staying-in-the-conversation)
- [Talking sooner](#talking-sooner)
- [Before it runs, and after it leaves](#before-it-runs-and-after-it-leaves)
- [Memory and who he is](#memory-and-who-he-is)
- [The crew](#the-crew)
- [The dial](#the-dial)
- [The eyes](#the-eyes)
- [Full control](#full-control)
- [Presence, the sideways wheel, and the Vault](#presence-the-sideways-wheel-and-the-vault)
- [What he can do](#what-he-can-do)
- [The lock, honestly](#the-lock-honestly)
- [Install the APK](#install-the-apk)
- [Build it yourself](#build-it-yourself)
- [What he cannot do](#what-he-cannot-do)
- [Architecture](#architecture)
- [What is stored, and where](#what-is-stored-and-where)
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

## The core — the brain on your PC

The six parts above run on the phone, against your own key. There is a second arrangement: a model
running on your own computer does the planning, and the phone keeps the ear, the voice, and the hands.

**Settings → INTELLIGENCE → CORE · PC AS BRAIN.** Start a model on the PC — Ollama, llama.cpp,
LM Studio, vLLM, anything OpenAI-compatible — plug the phone in, and run:

```
adb reverse tcp:11434 tcp:11434
```

That is the whole setup. The PC now appears to the app as `127.0.0.1:11434`, the bytes travel down
the USB cable, and nothing touches the wifi. Then turn the core on and plan there instead of at a
provider.

**Why the address is local.** A plain `http://192.168.x.x` is refused, with a sentence saying so.
Over wifi, any machine on the network can claim to be the core, and a phone has no way to tell once
it starts treating that machine's words as instructions. Over the cable there is nobody in the middle
and TLS would be theatre. An `https://` address is accepted from anywhere, so a core served with a
real certificate works over the LAN or a tailnet. The rule is enforced twice — when you save
(`CoreLink`) and by the platform's network policy — and `CoreLinkTest` fails CI if it is ever
loosened for convenience.

**The PC is never required.** If the core is off, asleep, or the cable is out, that same sentence is
planned with your key instead. With neither configured, the local rules answer exactly as they did
before this build. Nothing about the phone waits for a computer that is not there.

**What is not built yet.** No pairing flow, no installer for the core, no phone-side control of the
PC, and no long-lived session. This slice moves one thing — the plan — and leaves every other part
where it was.

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

## The sky

Weather and air quality, from a source that asks for nothing: Open-Meteo, no key, no account, no
sign-up. It only needs a coordinate, which the phone already has for distance and navigation.

| | |
| --- | --- |
| **On the dashboard** | A SKY panel: temperature, condition, today's high and low, humidity, wind, and the air-quality band with the advice that goes with it |
| **Out loud** | *"mausam kaisa hai"*, *"will it rain"*, *"air quality"* — answered in the language you asked in |
| **Cached for half an hour** | So the panel costs one request per half hour at most, and nothing at all when warm. A spoken question skips the cache, because it deserves a live reading |
| **Honest when it cannot** | No location permission, no fix, no network — the panel says which, instead of showing a friendly 24°C that is not real. A stale reading is labelled **STALE**, and *unknown air* is never shown as *good air* |

---

## The assistant button

Hold the assistant key — Home, the side key, whatever your phone calls it — and JARVIS opens
already listening. No phrase to say, no microphone held open, nothing running while the phone sits
in your pocket.

That is the whole of it, and it is deliberate. The seat is Android's own assistant role
(`ROLE_ASSISTANT`): the system starts a `VoiceInteractionSession`, the session hands straight to the
app with the same voice extra the bubble and the widget use, and closes itself. There is no
listener, no thread, no wake word and no model — the two services that exist to make the role
possible are empty by design, and one of them (`JarvisRecognitionService`) exists only because
Android's assistant metadata refuses to parse without it. It answers *"no speech input"* and never
opens the microphone.

Advanced → **ASSISTANT BUTTON** shows the real state — *held by JARVIS*, *not set*, or *not offered
by this phone* — and the button opens Android's own dialog. It also tells you where the gesture is
hidden on your brand: HyperOS, ColorOS, One UI, Funtouch and Moto each bury it somewhere different,
and a wrong instruction is worse than none.

The gesture costs nothing when you don't use it. For a hands-free start there is also **"Hey
Jarvis"** (below), which is opt-in and does cost battery while it is on.

## Hey Jarvis

Say **"Hey Jarvis"** and he opens listening. It is off until you turn it on in
**Advanced → HEY JARVIS**.

- **Engine:** [Vosk](https://alphacephei.com/vosk/), offline, Apache 2.0. No account, no access key,
  no cloud. The recogniser is given a grammar of exactly two alternatives — the phrase and the
  engine's "unknown" token — so ordinary speech does not wake him.
- **The model** (`vosk-model-small-en-us-0.15`, ~40 MB) is packed into the APK at build time, so
  installing the app is enough — there is nothing to download afterwards, and it works with the
  phone in flight mode. Turning the wake word on unpacks it to private storage, offline, in seconds,
  and it is checked by its contents before use. The build enforces this: CI compiles with
  `-PrequireVoskModel=true` and rejects any APK that is missing `assets/model-en-us/am/final.mdl`,
  because a build that quietly shipped without the model would ask you for a second download — the
  exact thing this is here to prevent.
- **While it is on:** a foreground service holds the microphone. Android shows its microphone
  indicator and a notification the whole time, with a *TURN OFF* button. It listens only while
  switched on; turning it off stops the service.
- **On a hit, the sentence comes with it.** The service reads the microphone itself, so the audio
  that finds the phrase is the same audio that becomes the command. It keeps **1.2 s from before**
  the hit, then captures until you stop speaking — a one-second pause ends it, an eight-second
  ceiling bounds it — and hands the whole thing to the app as one utterance. So
  *"hey jarvis, open WhatsApp"* acts in a single breath, with no tap and no repeating yourself, and
  `hey jarvis` is stripped out so it never becomes part of the request.
- **If nothing follows the phrase** — you only said the wake word, or a door slammed — it gives up
  at once, opens JARVIS listening, and re-arms after about 10 seconds. If the phone's own assistant
  is already holding the microphone, the notification says "tap to talk" instead.
- **Tapped listening ends itself too:** pause for a second and it transcribes, rather than waiting
  for a second tap.
- **After a reboot:** it resumes the first time you open JARVIS, not before. Android does not let an
  app start a microphone service from a boot broadcast.

**The honest cost.** Listening uses battery the whole time it is on. The figure of 1–3% an hour is
an estimate, not a measurement; measure it on your own phone before you rely on it. Turn it off
when you don't need it.

## Staying in the conversation

One sentence used to be the whole session: say it, hear the answer, say *"hey jarvis"* again. With
**Advanced → Conversation** on, the ear stays open after a finished turn instead.

- **He answers, then asks.** The question is shaped by what just happened — after a chat app opens,
  *"Boss, kisko message karna hai?"*. The next sentence is taken as a normal command, with no wake
  word and no tap.
- **Bounded in four directions.** A per-sentence window of 9 seconds (4–20, adjustable), a turn
  budget of 6 (up to 12), exit words (*bas*, *theek hai*, *chalo bye*, *thank you*, *so jao*), and
  silence — which closes the window quietly instead of showing a failure. On the provider route
  recording stops after 900 ms of trailing quiet, and a silent window is never sent anywhere.
- **A male voice, chosen rather than inherited.** Most phones default to a bright female voice. A
  male voice is picked from the engine's own list for the spoken language; when the phone has none,
  pitch drops to 0.78 instead of the wrong voice being used. **Advanced → JARVIS Voice** switches it.
- **Feelings, read and not invented.** Tired, low, angry, anxious, happy, excited, warm — read from
  your own words in English, Hindi and Hinglish. A feeling word inside a command never swallows the
  command (*"tired hoon, alarm laga do"* still sets the alarm). Days together, streak and turn count
  are arithmetic over real timestamps, and *Forget everything* clears them. Care is only offered from
  a real reading — a battery at 8%, a water log at zero, past midnight — never out of nowhere. His
  register reaches the voice: concern is spoken slower and softer.
- **The internet, one page at a time.** *"padho https://…"* fetches that page, answers from it and
  names the site. https only, loopback and private ranges refused, no JavaScript, and an empty page is
  reported as empty rather than summarised. This reads one page on request; it is not a search engine.
- **He can be taught, and learning is a proposal.** *"what can't you do"* reads back what he failed
  at; *"seekho …"* turns your description into a routine built only from actions that already exist,
  saved **disabled** so you can read it before it ever runs.
- **Captions on the platform's real rules.** *"reel ka caption banao about X"* writes a hook that fits
  the ~125 characters before *more*, 3–8 specific hashtags and one call to action, lints it and
  **copies it to the clipboard**. Posting stays yours: Instagram has no public API for an ordinary
  app, and the timing line stays silent because this build has no account connections to read numbers
  from — a guess dressed as data is worse than no advice.

## Talking sooner

The old answer arrived whole: the request finished, then the first word was spoken. On a good day
that was a second and a half of silence, and silence is what makes an assistant feel like a form.

- **The reply is read as it is written.** The provider's stream is consumed frame by frame, and each
  finished sentence goes to the voice the moment its full stop arrives, queued so the next one can
  follow without cutting it off. The first sentence usually starts a few hundred milliseconds after
  the request instead of after the whole paragraph. It is a *streaming* path and nothing else changes:
  same model, same key, same fallbacks.
- **A routine is never spoken while it is being written.** The first character decides: a brace means
  a plan, and a plan must be whole before anything happens to it, so nothing is spoken until the
  object has been validated. Conversation is spoken; commands stay quiet until they are understood.
  If the stream dies mid-sentence, what was already said is kept and the rest is not invented.
- **The pause that ends a sentence depends on the sentence.** A two-word command ends after 0.55 s of
  quiet, an ordinary sentence after 0.7 s, and only a long description keeps the full second — timing
  measured from the audio, before any transcription exists.
- **What it costs when it fails.** A provider without streaming, or one that drops the connection,
  falls back to the ordinary whole-reply path on the same turn. The worst case is the old behaviour,
  never a half-spoken answer followed by an error.

**The honest limit.** Sentence-by-sentence speaking is only as fast as the provider's first sentence.
On a local core (Ollama, LM Studio) the model itself may take longer to produce the first sentence
than the network ever did, and the core is still asked for whole replies. That is the next thing to
fix, not something this build claims.

## Before it runs, and after it leaves

A routine can do irreversible things, so there are two ways to look at one before it does anything.

**PREVIEW** walks the routine exactly as RUN would, and reports instead of acting. Every step comes back
as `would run: <the value it would have used>`, a `WAIT UNTIL` is *read* rather than waited on
("false now — would wait up to 90s"), and an `IF` says which branch it would take. Nothing is touched:
no app is opened, no message is sent, no confirmation is asked, and the preview is deliberately not
written into history, because a preview is not a run.

**SHARE** hands the routine out as text: its name, its trigger, its step count, its link, and its JSON.
The JSON is the same shape the store reads, so a routine can be pasted into a message, edited by hand,
and kept next to the app.

A routine can also be woken by the calendar: **BEFORE AN EVENT** fires when the next event starts
within the number of minutes you set. It fires *once* per event, not once per minute — the watcher
polls every minute, so "already ran since this event's window opened" is what keeps it to one run. An
all-day event has no moment to be fifteen minutes before, so it is ignored rather than guessed at.

**ON ARRIVAL** and **ON DEPARTURE** are the same idea with a circle instead of a clock: save a place as
`lat,lon|metres|name` — honestly, tap **USE CURRENT LOCATION** while standing there — and the routine
fires when you cross the fence. Two thresholds, not one, because a phone on a desk near the edge
reports 195 m, 205 m, 190 m: you are inside at or under the radius and gone only once you are a quarter
past it, so the routine fires on a crossing rather than on GPS jitter. A first look is never a
crossing, which is why a phone that has just booted does not announce an arrival wherever it is.

The price is one location read a minute, and only while a fence routine is armed — every other routine
costs this nothing. It uses the freshest position the system already has, so it never turns the radio
on. The automation board grows a **LOCATION** row when a fence routine is armed and the permission is
missing, because a trigger that can never fire should say so instead of staying silent.

The link is `jarvis://run/<id>`. Nothing on the web resolves it — it is a handle for shortcuts, widgets,
QR codes and automation apps, and the app answers it, opens the automation board and starts that
routine with the trigger label `Link`. A link to a routine that has been deleted, or switched off, runs
nothing and says so: enabling a routine is a decision, and a link is not allowed to make it for you.

---

## Memory and who he is

- **Every conversation is kept.** Each exchange is appended to a file in the app's private storage
  (one line per exchange). The Privacy screen shows how many are saved and how large the file is, and
  lets you pause recording or clear the conversations. Clearing or *Forget everything* removes them.
- **Talk memory across restarts:** the last 20 exchanges from that record go back to the model as real
  earlier messages, so \"and then?\" and \"why?\" have a subject, even after the app restarts.
- **Habits:** he counts what you ask for, by time of day (morning, afternoon, evening, night). Once a
  request has come three times, it is mentioned to the model as a habit, so he can anticipate it gently.
  Habits are counters, not a model: nothing is inferred beyond how often something was asked.
- **What leaves the phone:** only the last 20 exchanges, with each AI request. The full record stays
  on the phone. Export (Privacy → Export) includes the full record, and restore adds back any lines
  that are not already there.
- **Facts and people** are still learned from plain statements such as \"my sister is Khushi\".
- **Reading stays cheap:** the next reply reads only the newest 256 KB of the record, so a long history
  does not slow him down. Nothing polls for memory.
- **Identity:** every model call starts with: he is JARVIS, the operator is his boss, and Rishu
  Jaswar built him. He never names a provider, model or key.
- **Voice:** a lower pitch and steady pace on every phone. Where the installed voices include a
  male one, he uses it. Android does not say which voice is male, so the pitch does most of the work.

## The crew

One brain, four lanes, one arbiter — and it is not the thing the concept renders show.

There is one screen, one microphone and one voice on a phone. Two "agents" acting at once would both
read the same window and both tap it, and two model calls would cost twice the wait for half the
sense. So the rule is narrower and true:

| Lane | What it is | May run alongside others? |
| --- | --- | --- |
| **DEVICE** | Spoken commands, screen tasks, the camera | no — it holds the phone |
| **ROUTINES** | Your trigger-driven workflows | no — same lock as DEVICE |
| **RESEARCH** | Look-ups and the knowledge shelf | yes |
| **ALERTS** | What is waiting in the shade | yes |

Anything that touches the screen takes **one shared lock**. The second thing to want it says
**QUEUED** instead of silently doing nothing. The read-only lanes never wait, because a look-up does
not fight anybody for the display.

`ACTIVITY` (under the day ruler) shows all four lanes live, then the real history: what each routine
did and what trigger fired it, what is on the knowledge shelf, and every piece of work this session.

**No avatars. No invented task counts. No percentage bars.** A number on that screen came from a run
that happened, because a number on that screen is a claim.

---

## The dial

The reactor is the navigation now, not a picture of one.

Every module sits on a ring around it — eleven labelled keys, rotated along the arc, flipped on the
lower half so nothing is ever read upside down. Tap one and it opens. The centre of the dial is the
microphone and nothing else, because the ring is built from eleven real touch targets rather than one
clever full-screen hit test; the orb keeps its tap by construction, not by luck.

Around it, the instrument furniture that makes a HUD feel alive rather than decorated:

| | |
| --- | --- |
| **Micro-readings** | A strip of the smallest numbers on the screen — `BAT 84 · MEM 61 · STO 44 · LINK · LIVE 11/11`. Every value is measured. A reading that is not available is dropped, never filled in with something plausible. |
| **Crosshairs** | One hairline pass through the dial and four cardinal ticks. Cheapest way to make a circle read as an instrument. |
| **The day ruler** | The whole day as one bar: your events as blocks, six-hour marks, and a cursor at now. The dashboard used to answer "what is next", which is the least useful form of the question at four in the afternoon. |

**What it costs:** nothing measurable. The ring canvas reads no animation state, so it paints when
the module list changes and not per frame; label text is measured once per list, not inside `onDraw`.
No service, no worker, no new dependency — it is drawing, and it stops when the screen is off.

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

## Presence, the sideways wheel, and the Vault

Three ideas taken from the OSO concept reel, each built the honest way.

**He reacts to the room.** The reactor used to spin on a fixed loop, which reads as a screensaver.
Now the recorder publishes the real loudness of the microphone buffer about twenty times a second,
and the orb answers it: it lifts and brightens with a voice, falls slowly in a pause, and carries a
short fast arc only while he is thinking. Nothing polls, and the measurement is not invented — it is
the same PCM that becomes the transcript. A quiet room really is a still orb.

**Held sideways, the dial turns.** Portrait is unchanged. In landscape the ring rotates ninety degrees
and the disc gives up height, so the dock becomes a wheel you read left-to-right the way a phone is
held in two hands — the reel's signature move, with no new dependencies.

**The Vault.** A locked drawer in Advanced: health today, the conversation archive with its export,
and private notes. It opens only after the phone's own fingerprint or face prompt says yes — JARVIS
never sees the scan, only the answer. If no fingerprint is enrolled it says so plainly and lets you
in, because pretending a gate exists would be the one dishonest thing here. The notes live in the
app's private storage: a locked drawer in a locked room, not a safe.

---

## Full control

The ask was plain: whatever is said should be done. Three things stood between the app and that, and
all three are gone.

**Shizuku comes inside the APK.** Shizuku is the companion app that lends JARVIS the `adb` shell
identity, and it is the one thing the switches below need. Asking you to find a second download and
install it was the last rough edge, so the release APK now carries it: the build fetches the pinned
release (13.6.0, Apache-2.0, from RikkaApps' official GitHub release) and packs it inside the JARVIS
APK. One button — *Install Shizuku* in Launcher settings — opens Android's own install screen with one
tap to confirm. If a build had no network when it was made, there is no bundled copy and the same
button downloads it on the phone instead. Either way you then open Shizuku once, start it over
wireless debugging, and authorise JARVIS.

Android never lets one app install another silently, and that is deliberate: the confirmation screen
is the system's, not JARVIS's. This is also why the APK is for sideloading, not Google Play, whose
policy forbids `REQUEST_INSTALL_PACKAGES` for an app like this.

**The switches Android keeps to itself.** `wifi off`, `bluetooth on`, `mobile data off`,
`airplane mode on`, `hotspot on`, `brightness 40`, `rotation lock on`. Android stopped letting
ordinary apps flip Wi-Fi, Bluetooth and mobile data years ago — `svc wifi` is `adb`'s verb, not an
app's — and airplane mode and hotspot were never available. With **Shizuku** connected he runs those
commands himself, so the switch is genuinely off. Without it he opens the system panel with the
switch in front of you and says exactly that. He never claims a switch he did not flip. Brightness
and rotation need no shell at all once the app may write system settings.

**Long tasks finish.** A plan may now hold 24 steps instead of 8, and the screen agent that taps
through an app he has never seen may take 20 actions instead of 6 — with a four-minute ceiling as
well, so nothing can sit inside one app forever. Every action still appears on the card as it
happens and the whole run can be stopped.

**He acts instead of offering.** The rule he is given is now explicit: never "I can do that", never
"shall I", never a description of the steps he would take — return the steps. A sentence phrased as
a wish ("it is dark in here") counts as a request.

**Shell, as asked.** Voice may now use the privileged tier for what no action covers: a vendor
setting, a permission toggle, an app's hidden state. This is real power — it runs with `adb`'s
identity. Commands that cannot be undone (`reboot`, `pm clear`, uninstalling, wiping) are asked
about first.

**The one yes.** Calling somebody, sending a message, replying, saving a contact or adding an event
waits for one short question, in the language you spoke. The plan is stored exactly as it was
planned, so the yes runs what you heard rather than a second guess; the question expires after 90
seconds, and any other answer throws it away. Everything else on the phone runs on the first word.

---

## What he can do

<table>
<tr><th align="left" width="30%">If you say…</th><th align="left">He does</th></tr>

<tr><td><b>Open anything</b></td><td>

`open gallery` · `files kholo` · `open wifi settings` · `open Instagram`

System intents, skin-specific packages and fuzzy labels — so it works on ColorOS, MIUI, One UI and
stock alike.</td></tr>

<tr><td><b>System switches</b></td><td>

`wifi off` · `bluetooth on` · `airplane mode on` · `mobile data off` · `hotspot on` ·
`brightness 40` · `rotation lock on`

Through Shizuku the switch really flips. Without Shizuku the panel opens with the switch in front of
you, and he says so instead of pretending.</td></tr>

<tr><td><b>The assistant key</b></td><td>

Hold the assistant key and he is listening. The hole in the "what he cannot do" list about wake
words is answered by Android's own assistant role instead of a microphone left open.

</td></tr>

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

Full automation engine: triggers, conditions, control flow, retries, run history, and **63 action
types** — 58 actions and 5 control-flow steps — available to both routines and voice.</td></tr>

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

Pick the newest **`jarvis-1.0.0-b<build>.apk`** → download → open it on the phone → allow
*Install unknown apps* once. The build number is the `versionCode`; the version name stays 1.0.0 for
ever build, so the build number is how you tell one APK from another.

That is the file to share. Anyone who opens that link can install it without a GitHub account.

### Where the file comes from

| Location | Path |
|---|---|
| GitHub Actions run | **Artifacts** → `jarvis-release-apk` → `jarvis-1.0.1-b40.apk` |
| Build machine | `app/build/outputs/apk/release/app-release.apk` |
| CI copy | `dist/jarvis-1.0.1-b40.apk` |
| GitHub Release | attach `jarvis-1.0.1-b40.apk` to tag `v1.0.1-build40` |

### Nothing is downloaded from inside the app

The voice model, Shizuku and the OCR engine all travel **inside the APK**. There is no first-run
fetch, no "download the model" screen, no 40 MB surprise on mobile data: installing the file is the
whole install, and the app works in flight mode afterwards. This is enforced twice — CI builds with
`-PrequireVoskModel=true -PrequireShizuku=true` and fails rather than shipping an APK without them,
and the runtime fallbacks that used to fetch them are closed by `BUNDLED_ONLY`. A build that is
missing something says so plainly instead of quietly downloading it.

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
./gradlew :app:testDebugUnitTest    # 60 JVM suites
python3 tools/guard.py              # docs, pins, cleartext, exported receivers
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
| Wake word with no AI key | The captured sentence is turned into words through your provider's speech endpoint. With no key — or a provider that has none — JARVIS cannot transcribe the audio it captured, so it falls back to listening again rather than guessing. |
| Always-on wake word | Android gives its efficient hardware path only to system apps. So "Hey Jarvis" is opt-in: a foreground service listens while you have it on, and costs battery while it does. He uses the assistant key when the wake word is off. |
| Run without your AI key for free-form conversation | Small-talk works offline; a real conversation needs a model you own. |
| Publish a website or run a server | Out of scope by design. He is the brain on your phone, not a hosting company. |
| Wi-Fi, Bluetooth, data, airplane mode or hotspot without Shizuku | Android reserves those switches for `adb`'s identity. With Shizuku connected he flips them himself; without it he opens the panel and one tap finishes it. |
| Install Shizuku without a tap | Android requires the system's own confirmation for any app install. JARVIS opens that screen; your tap finishes it. |
| Keep the bundled Shizuku up to date by itself | The copy inside the APK is pinned to one version. A newer Shizuku comes when a newer JARVIS does. |
| Fire a place routine without the location permission | A fence needs to know where the phone is. Without the permission nothing fires, and the automation board says `LOCATION · NOT GRANTED` rather than pretending. |
| Undo a destructive shell command | The privileged tier runs as `adb`: a wipe is a wipe. That is why `reboot`, `pm clear`, uninstalling and wiping are asked about before they run. |

**Verified by CI:** compilation, 60 test suites, both APKs, manifest integrity.
**Needs your phone:** WhatsApp sending on your build, notification replies, the ColorOS gallery name,
step/heart sensors, the system credential screen, TTS voices.

---

## Architecture

Two Gradle modules, split along the one line that matters: what needs a phone, and what does not.

```
engine/src/main/java/com/jarvis/assistant/      the rules — not one `import android.`
├── automation/                   ActionType (58 actions), 11 conditions, 13 triggers, the risk
│                                 ladder, the fingerprint, retry and rollback policy, screen map
└── data/                         AssistContext (what the gesture handed us) · MemorySearch (BM25)

app/src/main/java/com/jarvis/assistant/
├── MainActivity.kt               single activity, Compose host
├── ai/                           your key, any provider, JSON + free conversation
├── automation/                   the executor, the store, run history, step reflection, previews
├── data/                         PhraseBook (~400 phrasings) · Units · When · command line
├── device/                       the phone itself: files, gallery, location, health, calendar,
│                                 contacts, notifications, voice notes, lock, biometrics
├── shell/                        Shizuku user service (optional, adb-level, no root)
├── ui/                           Home · Dashboard · Lock · Privacy · Terminal · Voice
├── voice/                        Brain (memory) · IntentRouter · Language · Persona · FollowUp
└── wake/                         "Hey Jarvis": Vosk grammar, model inside the APK, service
```

The split is not tidiness. `:engine` holds the decisions that have to be *reasoned about* — what an
action is allowed to do, whether a retry is safe, what a routine's fingerprint covers — and it can be
compiled, read and tested as plain Kotlin. `tools/guard.py` fails the build if an `import android.` or
`import androidx.` appears anywhere in it, because a module that can reach the phone will eventually
reach the phone.

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

## What is stored, and where

| What | Where | Why there |
|---|---|---|
| Routines | JSON in `SharedPreferences` | Eight small documents. A database for eight rows is ceremony. |
| Run history | SQLite, through Room | The one thing that grows without a ceiling. It used to be a JSON array rewritten in full on every run — now it appends, orders and trims in the table, and the screen reads only the fifty rows it shows. |
| Conversation log | One JSON line per exchange, appended | Append-only, and read backwards from the tail. Rewriting a file to add a line is the mistake the run history was making. |
| Memory, settings, keys | `SharedPreferences`; keys in the Keystore | Small, read constantly, and small is the right size. |

`fallbackToDestructiveMigration()` is on. If a future schema change cannot be migrated, the run history
is dropped rather than the app crashing — and a preference-era history is imported into the table once,
on first launch, so an update does not lose it.

---

## Privacy

| | |
|---|---|
| **Backend** | None of mine. No JARVIS server, no account, no sync. The optional core is your own machine, reached over your own cable. |
| **Network** | Only: your own AI key's provider, one optional router for road distance, a URL you asked for, or the core you run yourself. The wake-word model is inside the APK — nothing is fetched to make "Hey Jarvis" work. |
| **Memory** | A preferences file on this phone. Read it in Advanced; wipe it with one key. |
| **Keys** | Android Keystore (`AES/GCM`). Never in this repository. |
| **Notifications** | Held in memory only, dropped when the listener disconnects. |
| **Photo, microphone, screen** | Only while you are using the feature that needs them. |
| **Location** | Only if you use a place routine, or ask for a distance. The watcher reads the position the system already has, once a minute, while a fence routine is armed. The coordinates live in the routine on this phone. |

Permissions are not hidden in a settings page nobody opens — *Advanced → ACCESS* lists every
capability, what it unlocks, and what breaks without it.

---

## Tests

60 JVM suites and 553 tests, run before every APK in CI.

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
| `StepInputTest` | the address a webhook may call, and the gesture a word means |
| `ToolSchemaTest` | the tool schema covers every action, condition, trigger and fail policy, on all three wires |
| `ToolCallsTest` | reading a call off OpenAI, Anthropic and Gemini envelopes — and refusing rubbish |
| `StepReflectionTest` | when a silent screen means a miss, and which id the retry should use |
| `RetryPolicyTest` | irreversible work is attempted once, whatever it asked for |
| `CompensationTest` | rollback order, and the two cases it must refuse |
| `AssistContextTest` | what a screen may never carry: passwords, duplicates, instructions |
| `MemorySearchTest` | relevance ranking, long-entry damping, Hinglish and Devanagari |
| `EndpointingTest` | the pause that ends a sentence, from the speech that came before it |
| `PlaceTriggerTest` | The parameter string round-trips · the band holds its answer · a first look is never an arrival |
| `CalendarTriggerTest` | One event, one run · the window clamps · no start means no run |
| `RoutineExportTest` | The share link carries the store's id · a history row survives its JSON · presets are well formed |
| `StreamingReplyTest` | prose is spoken as it arrives, a routine is never spoken at all |
| `SseTest` | reading OpenAI, Anthropic and Gemini streams, frame by frame |
| `TimeFormatTest` · `VoiceParsingTest` · `JsonReplyTest` · `WavTest` · `WorkflowJsonTest` | the rest of the pure logic |

---

## Release checklist

```bash
# 1. versionCode moves on every build; versionName moves only for a release.
#    app/build.gradle.kts  →  versionCode = 40, versionName = "1.0.1"

# 2. green CI on main
gh run list --limit 1

# 3. tag it — CI attaches the APKs to the release automatically.
#    The tag carries both, so a tag on its own says exactly which file it built.
git tag v1.0.1-build40 && git push origin v1.0.1-build40
```

CI writes the release title and the APK file names from `versionName` and `versionCode`, so they can
never disagree with the build. Edit the notes afterwards if you want to say more than the workflow
does; the download link above works for everyone either way.

---

<div align="center">

**Built by Rishu Jaswar.**

*"Sometimes you gotta run before you can walk."*

<sub>Kotlin · Jetpack Compose · Material 3 · minSdk 29 · no backend, no account, no telemetry</sub>

</div>
