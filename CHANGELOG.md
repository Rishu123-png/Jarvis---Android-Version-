# Changelog

All notable changes to JARVIS, newest first. Versions match `versionName` in `app/build.gradle.kts`.

---

## 1.0.0 — versionCode 24

**The version number restarts here.** From this release on, JARVIS is **1.0.0** and stays there in
every build; `versionCode` is what increases. The earlier 2.x names were internal scaffolding, and a
family member released to family should not be called 2.4.

Everything below is new in this release.

**Memory, identity, voice, and the model in the APK (same version).** The last 20 exchanges now go to the
model as real earlier messages, so the conversation has a thread; the phone keeps 40 turns. Every model
call begins with who he is: JARVIS, the operator is his boss, built by Rishu Jaswar. The voice uses a
lower pitch and steady pace, and a male voice when the phone has one. The "Hey Jarvis" model is packed
into the APK at build time, so installing needs no download; a build that cannot fetch it falls back to
downloading on first use. **versionCode 27.**

**"Hey Jarvis" (same version).** Opt-in, offline, no account: Vosk listens for the phrase through a
grammar of exactly two alternatives, so ordinary speech does not wake him. The model (~40 MB) downloads
once on first use and is checked by its contents. While on, a foreground microphone service shows the
system indicator and a *TURN OFF* button. On a hit it opens JARVIS listening and re-arms after ~10 s.
Battery cost is an estimate until measured on a device. **Removed:** the Instagram module and its
analytics, the Connections screen, and the Instagram and Google Drive / Gmail logins. Their stored
tokens are deleted on first launch. Both are in git history if you ever want them back.
**versionCode 26**.

**The assistant button (same version).** Hold the assistant key and he opens already listening. This
is Android's own assistant role (`ROLE_ASSISTANT`), not a wake word: the system starts a
`VoiceInteractionSession`, the session hands over to the app with the voice extra the bubble and the
widget already use, and closes itself. The two services that make the role possible are empty by
design, and `JarvisRecognitionService` exists only because Android's metadata refuses to parse
without it — it answers "no speech input" and never touches the microphone. Advanced shows the
true state (held / not set / not offered by this phone) and the per-manufacturer instructions for
finding the gesture on HyperOS, ColorOS, One UI, Funtouch and Moto. Nothing listens in the
background, nothing new idles. **versionCode 25**, 27 suites.

**The sky, the language fix and the shortcuts (same version).** Weather and air quality from
Open-Meteo — keyless, no account, cached for half an hour, with a SKY panel on the dashboard and a
spoken answer in the language the question was asked in. Every failure states its own reason: no
location permission, no fix, no network, or a reading old enough to be labelled STALE. Unknown air
quality is never rendered as clean air. The **NEEDS YOU** card now speaks Hindi and Hinglish like
everything else — it was English-only, which was my mistake, not a decision. And the launcher icon
now has **long-press shortcuts**: Speak, Knowledge, Activity. **versionCode 24**, 26 suites.

**The crew, and what is waiting (same version).** Four lanes — DEVICE, ROUTINES, RESEARCH, ALERTS —
with one arbiter: anything that touches the screen takes a single shared lock, so a spoken command and
a routine can never fight over the window, and the one that waits says **QUEUED** rather than doing
nothing. The read-only lanes run alongside everything. A new **ACTIVITY** screen (under the day
ruler) shows the lanes live plus the real history — routine runs with the trigger that fired them,
the knowledge shelf, and this session's work. No avatars, no invented task counts.
Also: a **NEEDS YOU** card on the dashboard — at most three rows, empty when nothing is waiting,
ordered by what happens next, and it says *"alert access is off"* rather than showing a confident
zero — and **NEXT UP** now lists three events instead of one. **versionCode 23**, 25 suites.

**The dial (same version).** The reactor is the navigation now: eleven module keys around the ring,
labelled along the arc and flipped on the lower half so nothing reads upside down, each with its own
46 dp touch target — the centre of the dial stays the microphone because the ring does not cover it.
A micro-reading strip (`BAT · MEM · STO · LINK · LIVE`), crosshairs and cardinal ticks on the dial,
and a day ruler across the whole day with a cursor at now. All of it is drawing: no service, no
worker, no new dependency, nothing runs when the screen is off. **versionCode 22**, 23 suites.

**The eyes and the suitcase (same version).** He can look at something — *"look at this"*, *"read
this"*, *"yeh kya hai"* — take one frame with his own capture surface, shrink it by subsampling at
decode time (so a 12-megapixel photo never enters memory whole), and answer the question you asked
through the AI key you already have. On demand only: no background camera, no held sensor. He is told
in as many words to say "I cannot read that" rather than invent a dosage or a name. And the whole
memory — people, facts, conversation, knowledge shelf, routines — now exports to **one JSON file** in
`Download/JARVIS` for a new phone, and imports back additively. The AI key is deliberately excluded
from the file, because exports travel by chat and mail. **versionCode 21**, 22 suites.

**The curiosity (same version, rebuilt without the AI price tag).** He can look something up —
Wikipedia and a free dictionary, both public, both keyless, no account and no bill — and he **asks
first**: *ask everything*, *ask once per topic* (default) or *don't ask*, three positions on the new
**KNOWLEDGE** page. Answers are summarised on the phone by counting (two sentences, no model, no
download), attributed out loud to their source, and kept as cards that answer the same question
instantly and offline next time. Every card is listed with its date and can be deleted one by one.
Nothing about the operator is ever sent, and he still cannot post, send or sign in to anything.
Version code **20**.

**The brain (built after the first pass, same version).** JARVIS now keeps a local memory of the
operator — people and how they are related, standing facts like a name or a city, the last few turns
of conversation — and answers *conversation* as well as commands: a spoken reply with no JSON steps
is now a legitimate answer instead of a parse failure. Four families of plain sentence teach him
something ("my sister is Khushi", "my name is Rishu", "I like tea", "I live in Delhi"), and the
narrowness is deliberate: a wrong memory is repeated back with total confidence. Everything is
visible in *Advanced → What he remembers* and erasable with one key. Small talk ("how are you",
"kaise ho", "who am I", "I am tired") works with no AI key at all, in English, Hindi and Hinglish.

**Also in this same version (built straight after):** your calendar read aloud and events created by
voice ("add meeting with Amit tomorrow at 4"), "save this number as Amit" writing a real contact,
voice notes recorded straight into `Music/JARVIS`, multi-turn follow-ups ("do that for Khushi",
"and tomorrow", "make it 8:30"), and a once-every-three-hours brief when you open JARVIS — the next
event, a dying battery, people waiting for an answer — silent when there is nothing to say.
Replies are now spoken by a voice in the operator's own language, and the dashboard's TODAY panel
shows the next event beside today's steps and water.

**It answers everything now.** The old router was a chain of regexes: "open gallery" worked, "gallery
kholo" and "show my photos" did not, and a miss produced "I could not find an app called gallery".
A phrase book of ~400 wordings across ~50 intents replaces it, matched exact → prefix → word-set →
Jaro-Winkler fuzzy, so "open galery", "kitne steps" and "tumhe kisne banaya" all land. A request that
still misses gets the closest real app as a "did you mean", never a dead end.

**Opening anything.** Gallery, camera, files, ~25 settings pages, Play Store and every installed app
are opened through documented system intents rather than guessed labels, with the skin-specific
gallery packages (ColorOS, MIUI, One UI, Google) tried by package name.

**Messaging that actually sends.** "Send message Khushi saying hi" no longer needs a saved contact:
the person is resolved from contacts, remembered names, or a live chat notification. Sending prefers
answering the notification through its own `RemoteInput` action — the supported path, no screen
taps — then the `wa.me` link with the text pre-filled, and only presses send after it can see the
chat. If it cannot confirm, it says exactly that.

**Notifications with judgement.** With one switch on, JARVIS reads a message aloud and offers three
replies that fit it; an advertisement gets one dry line and is then ignored for the day; an OTP is
read with its code, because that is the one alert people actually need. Quiet hours, per-app mute,
and everything process-only — nothing is stored.

**Files, distance, maths.** "Find my aadhaar pdf" searches the whole phone with All-files access
(falling back to MediaStore, and asking for the permission instead of failing), opens the file when
there is exactly one match and lists them when there are several. "How far is India Gate" answers by
road through OSRM, and says *straight line* out loud when the router cannot be reached. Arithmetic
and unit conversion run offline: no key, no network, no hesitation.

**Health, honestly.** Steps from the hardware counter (and "this phone cannot count steps" when it
cannot), distance labelled an estimate, water only what was logged, sleep as a screen-off estimate,
heart rate only where the sensor exists.

**The phone's own lock.** *Advanced → Which lock* switches the gate to your real PIN, pattern or
fingerprint through the system credential screen — JARVIS learns only pass or fail. The keypad stays
as the fallback. A lock-screen line shows steps, water and unread messages as a public notification;
Android does not allow any app to replace the keyguard, and the settings page says so in plain words
instead of implying otherwise.

**Language.** The language of the question is detected from its script and words, and the answer
comes back in it. English, Hindi and Hinglish are answered completely offline; every other language
is answered by the model when a key is set, in that language, with the matching TTS voice.

**Family, not a product.** Asked who built him, JARVIS answers "Rishu Jaswar" and nothing else — and
that answer never leaves the phone. Provider names, key-shaped strings and "as an AI language model"
are scrubbed from every spoken reply before it is heard.

**Also in this release**: 15 new automation actions (message, reply, read alerts, find file, open
file, open gallery, location, distance, navigate, calculate, settings page, read screen, share, health,
log water, read calendar, add event, save contact, record note) available to routines and to the AI
planner; one ACCESS panel that states every permission and what breaks without it; 11 new JVM test
suites (17 in total).

---

## 2.3.1 — versionCode 17

- **Fingerprint / face unlock** for the privacy lock, through Android's own biometric hardware. One
  automatic attempt when the lock appears, then a button; it stops auto-firing after two failures and
  never replaces the PIN. No new dependency — the platform API is used directly.
- New settings panel showing the sensor state (`READY`, `NO FINGERPRINT SAVED`, `NO SENSOR`, `SENSOR BUSY`).

---

## 2.3.0 — versionCode 16

**Twelve-hour time.** Every clock the operator sees is 12-hour with AM/PM: the home orb, the dashboard,
diagnostics, reminders and the alarm confirmation. The 24-hour value is kept for logs, terminals and
file names. Pinned by `TimeFormatTest`, including midnight and noon.

### Phase C — reach and protection

- **Privacy lock.** A numeric PIN in front of the whole app: PBKDF2-HMAC-SHA256, 120 000 iterations,
  random salt, constant-time compare, no recovery, re-engages a minute after leaving.
- **Floating bubble.** An opt-in disc over other apps: tap to open JARVIS with voice armed, drag to
  move, long press to remove. Foreground service with a quiet notification; it never listens by itself.
- **Home-screen widget.** A 4×2 plate with a live `TextClock`, so it is always right without waking the
  app. Tap opens JARVIS ready to speak.
- **Downloads.** `download https://…` saves to `Download/JARVIS` with a progress notification, a 250 MB
  cap, hard timeouts and cancellation.
- **Translate.** `translate good morning to hindi` through your own AI key, spoken back.

### Phase D — depth

- **`VERIFY` step** in automation: `app:`, `screen:`, `notif:`, `battery<20`, `charging`, `wifi`. A
  routine can check that something actually happened.
- **OCR fallback** for the screen agent: bundled ML Kit text recognition reads the pixels when the app
  exposes no accessibility text — fully on device, no download.
- **Multi-turn voice**: the previous command is passed to the planner with a follow-up rule, so “again,
  but quieter” and “do that for Divik” resolve. `louder` / `quieter` work without a key.
- **Speech engine choice**: `AUTO`, `WHISPER`, `DEVICE`, `DEVICE OFFLINE` — visible in Advanced settings.

### Also

- Advanced settings screen (lock, bubble, engine, widget) reachable from Settings.
- CI names release artifacts `jarvis-<version>.apk` and attaches them automatically on a `v*` tag.

---

## 2.2.0 — versionCode 15

- **Tests.** Pure logic extracted into `VoiceParsing`, `JsonReply` and `Wav`, then covered by four JVM
  suites; CI runs them before building the APKs and publishes a per-suite table.
- **Quick Settings tiles**: open JARVIS, toggle the torch.
- **Reminders**: real notifications at a real time, exact alarms when allowed, re-armed after a reboot.
- **Screenshot** action through the accessibility API, saved to `Pictures/JARVIS`.
- **Subsystems panel** in DIAGNOSTICS: every permission-gated capability, honestly.

---

## 2.1.0 — versionCode 14

- **Camera capture that finishes on its own**: CameraX, `Pictures/JARVIS`, no shutter to press.
- **Chains up to 8 steps** with waits between app hand-offs.
- **Screen agent**: observe → decide → act inside unfamiliar apps, six actions maximum.
- New actions `CAPTURE_PHOTO`, `OPEN_LAST_PHOTO`; accessibility helpers `visibleLabels`,
  `currentPackage`, `pressLoose`.

---

## 2.0.0 — versionCode 13

- **Focus home**: a top bar and one orb; the full dashboard moved one tap behind the grid button.
- **Voice**: tap-to-speak, Whisper via your key or the device recognizer, spoken replies.
- **AI-first routing** with no canned command list, and a silent local parser when there is no key.
- **Memory**: last 50 commands, contact nicknames, app aliases.
- New actions `CALL_CONTACT`, `TYPE_TEXT`; contact resolution; WhatsApp prefill with optional auto-send.

---

## 1.9.2 — versionCode 12

- Six-probe Instagram `diagnose()` with per-step verdicts, error codes in messages, preflight warnings.

## 1.9.1 — versionCode 11

- Facebook-free Instagram route made explicit; token steps rewritten; platform notes in the UI.

## 1.9.0 — versionCode 10

- Connections module: Instagram login, Google Drive + Gmail (read-only), WhatsApp limits stated.
- Views-first Instagram analytics with `NEXT POST`.

## 1.8.0 and earlier

- HUD, arc reactor, telemetry, launcher, dock, notifications, controls, DND, automation engine with
  control flow, Shizuku shell, terminal, AI key management.
