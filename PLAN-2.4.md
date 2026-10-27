# JARVIS 2.4 → 2.8 — the plan

**Written after reading the code, not from memory.** Every claim in §1 has a file and line behind it.

The short version: JARVIS is honest and well-built, but it is **narrow**. It knows 33 actions and about
15 spoken phrasings, and when a phrase misses it says "I could not find an app called gallery" — which
is exactly the feeling you described. Three of the things you hit are bugs with a single clear cause, so
they get fixed first. Everything else gets built in phases so each release is real and testable.

---

## 1. What is actually broken right now

| What you said | Real cause in the code | Kind |
|---|---|---|
| "open gallery" does nothing | `IntentRouter.matchApp()` only searches installed apps whose **label** starts with or contains "gallery". On ColourOS the gallery is called *Photos* / *HeyTap Photos* / *Album*, so the match fails and you get "I could not find an app called gallery". There is **no media-intent fallback** for galleries at all. | Bug |
| "show my last photo" fails silently on a modern phone | `Gallery.openLatest()` queries `MediaStore.Images` but the manifest declares **no media permission**. On Android 13+ that query throws and returns *"The gallery could not be read."* | Bug |
| "open Instagram and send message Khushi saying hi" does nothing | `sendSteps()` requires the name to resolve in the **Contacts app**. "Khushi" with no saved contact → a spoken refusal and nothing else. Even when it does resolve, sending relies on the `wa.me` deep link then an accessibility tap on the exact label "Send", which varies by keyboard and by WhatsApp build. There is no verification that the message actually arrived in the box. | Bug + design gap |
| "it doesn't come in the lock screen" | JARVIS has its **own** PIN screen drawn inside the app (`LockScreen.kt` + `Privacy.kt`), shown only when the app is opened. It has no relationship to the phone's real lock screen, and as a launcher it can never be drawn on the keyguard. | Design gap |
| "it can read notifications and tell us" | The listener already collects every notification into `NotificationRepository`, and the Alerts screen shows them. But **nothing speaks them**, nothing judges whether they matter, and their reply buttons are thrown away. | Missing |
| "health, steps, water" | Not present at all. No `ACTIVITY_RECOGNITION`, no step sensor, no water log. | Missing |
| "calculate distance from here to somewhere" | Not present. No location permission, no geocoder, no distance maths. | Missing |
| "open files and find this file" | Not present. No file search of any kind. | Missing |

Two more things I found while reading, which matter for the "it feels dumb" problem:

- **When nothing matches, the fallback says "Not understood"** and lists features. That is the worst
  possible sentence from a family member. It must always offer a next step instead.
- **`IntentRouter` sends the provider's name into the spoken reply** (`"AI · Groq"`). You asked that
  JARVIS never talk about keys or providers. That label has to leave the voice path entirely.

---

## 2. The principles this plan follows

1. **Never silent, never fake.** Every command ends in one of three honest states: done, "I need one
   thing from you", or "I can't, and here's why". No command ends with nothing happening.
2. **Forgiving by design.** People forget the exact words, mumble, mix Hindi and English, and say it
   differently every time. So: a large alias dictionary, fuzzy matching, and an AI fallback that reads
   sloppy sentences. If the phone can do it, JARVIS should find the way from ~6 different phrasings.
3. **The phone's own behaviour is the reference.** "Gallery" means whatever gallery is installed.
   "Lock" means the phone's lock, not ours.
4. **Nothing new is claimed that isn't tested.** CI compiles and runs the JVM suites. Anything needing
   a sensor, a real keyguard or a real WhatsApp build is labelled *needs your phone* and listed at the
   end of each phase — I will not pretend a green build proves it works.

---

## 3. Phase 1 — **2.4.0 "It answers everything"** (fixes + a much wider ear)

**Goal: every sentence gets an answer, and the three bugs above are dead.**

### 3.1 The command dictionary (`data/Phrases.kt`, new)

A single table of *intent → phrasings*, replacing the scattered regexes in `IntentRouter.localPlan()`.
~250 phrasings across ~70 intents, including Hinglish and common typos:

```
"open gallery" / "gallery kholo" / "show my photos" / "meri gallery" / "pictures"
"whatsapp khushi hi bolo" / "message khushi ko hi" / "tell khushi hi" / "khushi ko hi bhejo"
"how many steps" / "kitne steps" / "mera step count"
"distance to india gate" / "kitna door hai" / "how far is ..."
"find my aadhaar pdf" / "search this file" / "file dhundo"
```

- Matching order: exact alias → normalised alias → **fuzzy** (Jaro-Winkler over 2–4 word windows) →
  AI → "did you mean…?" suggestion with a one-tap confirm card.
- `VoiceParsing.normalise()` already exists; it gets Hinglish numerals ("paanch", "das") and filler
  removal ("please", "yaar", "zara", "kya aap").

### 3.2 Opening anything (`device/AppLaunch.kt`, new)

Rewrite the open path so "open X" never fails for a thing that exists:

1. Fuzzy app label match (`Photos` ≈ `photos`, `gallery` → the installed gallery).
2. **System-intent aliases** — gallery, camera, files, settings, messages, dialer, clock, calculator,
   calendar, contacts, play store, browser, wifi, bluetooth, hotspot, battery, storage, accounts,
   accessibility, notification access, app info for a package. These resolve through `Intent`,
   never through a label, so they work on every phone.
3. If still unknown: ask "do you mean Photos or Files?" with both as tappable chips.

### 3.3 Media permissions and a real gallery path

- Declare `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` (13+), `READ_EXTERNAL_STORAGE`
  (≤12, `maxSdkVersion=32`), plus `USE_FULL_SCREEN_INTENT` later in Phase 5.
- `Gallery` gains: `count()`, `latestImage()`, `latestVideo()`, `openGallery()`, `searchByDate()`.
- If permission is missing, JARVIS **asks for it in that moment** ("I need permission to see your
  photos — tap here"), then finishes the original command. It never dead-ends on a permission.

### 3.4 Messaging that actually sends (`device/Messaging.kt`, new)

For "send message Khushi saying hi":

1. **Resolve the person** in this order: contacts (fuzzy, first-name, nickname) → `VoiceMemory` of
   people you have messaged before → the WhatsApp notification history (a chat that has messaged you)
   → ask "Which Khushi — the one on WhatsApp or the saved contact?"
2. **WhatsApp**: try `wa.me` deep link; then verify by reading the screen through accessibility
   (`visibleLabels()` already exists) that the chat opened with that name and the text box holds the
   message; then tap send (`pressByText("Send")`, then "SEND", then the send button resource-id via
   `pressLoose`). If any check fails, say precisely which step failed — never "done".
3. **Reply from a notification** (better than a cold open): if a live WhatsApp notification from that
   person exists, reply through its `RemoteInput` action — no UI automation at all, and it works even
   when WhatsApp is not open. This is the reliable path and Phase 1 includes it (§3.5).
4. SMS/Telegram keep their current behaviour, with the same honest reporting.

### 3.5 Notification intelligence (`device/NotifyBrain.kt`, new)

This is the "ask while seeing the notification" behaviour you described.

- **Read aloud, in JARVIS's voice, only when it matters.** Ad/marketing detection: an allowlist of
  chat/mail apps (WhatsApp, Telegram, Signal, SMS, Gmail, Instagram DM), plus a spam score from
  keywords (offer, sale, %, ₹, "hurry", "limited", Flipkart/Amazon/Myntra/Ajio promo packages,
  OTP patterns). Shopping notifications get **one dry line** ("Flipkart is having a sale. Ignoring it.")
  and are then muted for that app for the day; real messages get read properly.
- **Summary on demand**: "what did I miss" reads back the last N notifications grouped by person.
- **Reply suggestions**: for a WhatsApp/SMS message, JARVIS speaks the message and offers 3 short
  replies generated by the AI when a key exists, or template replies when it doesn't ("ok", "on my way",
  "call you later"). You pick by voice ("reply second one") or by tapping the card.
- **Sending the reply** uses `RemoteInput` on the live notification — the correct Android API for this,
  no accessibility and no WhatsApp automation.
- **Who am I replying to**: parsed from the notification's messaging style / `CarExtender` / title, and
  matched against contacts so "reply to Khushi" works even if three chats are open.
- Quiet hours, and a master switch. This is opt-in because it means JARVIS speaking unprompted.

### 3.6 Voice identity

- Remove provider labels from the voice path (`"AI · Groq"` → `"AI"` internally, spoken as nothing).
- Add a persona block: who built him (**Rishu Jaswar**), that he is family not a product, that he does
  not discuss keys, providers, tokens or "the model". Includes a guard: if a spoken reply somehow
  contains a provider name or a key-shaped string, it is rewritten before it is spoken.

**Phase 1 needs your phone for:** real WhatsApp send, notification reply, gallery on ColourOS.

---

## 4. Phase 2 — **2.5.0 "The whole phone"** (13 new action types)

New `ActionType` entries, each wired into `StepExecutor`, the AI prompt, and the phrase table:

| Action | Example you can say | How it works |
|---|---|---|
| `SEARCH_FILES` | "find my aadhaar pdf", "file dhundo photo" | `MediaStore.Files` + `DocumentsContract` search by name, type, date; results as a tappable list; opens with a read grant |
| `OPEN_FILE` | "open the newest download" | content URI + `FLAG_GRANT_READ_URI_PERMISSION` |
| `OPEN_GALLERY` | "open gallery", "show videos" | `ACTION_VIEW` on media, or the installed gallery |
| `RECORD_AUDIO` | "record a voice note" | `MediaRecorder` into `Music/JARVIS` (the app already has `RECORD_AUDIO`) |
| `LOCATION_QUERY` | "where am I" | `LocationManager` last-known + fresh fix |
| `DISTANCE` | "distance to India Gate", "kitna door hai" | `Geocoder` the place, haversine from GPS, then **road distance via the public OSRM router** (no key, rate-limited). If offline, says "straight line is 8.4 km" instead of inventing road distance |
| `NAVIGATE` | "take me to Connaught Place" | `google.navigation:` / `geo:` intent |
| `CALENDAR_ADD` / `CALENDAR_READ` | "add meeting tomorrow 4pm" | `CalendarContract` insert/query (`WRITE_CALENDAR`/`READ_CALENDAR`) |
| `CONTACT_ADD` | "save this number as Amit" | `ContactsContract` insert (`WRITE_CONTACTS`) |
| `SETTINGS_PAGE` | "open hotspot settings", "app info for Instagram" | `Settings.ACTION_*` deep links, ~25 of them |
| `CALCULATE` | "12 percent of 4500", "convert 5 miles to km" | Local expression/ununit engine — works with no network and no key |
| `SHARE_TEXT` | "share this with Khushi" | `ACTION_SEND` chooser |
| `READ_SCREEN` | "read this screen to me" | accessibility labels → spoken summary (already half-built in `visibleLabels()`) |

Plus: **file access modes.** Default is the scoped, Play-safe route (MediaStore + folder grants through
the system picker, persisted). Optional "All files access" toggle for full-disk search — it is a
special permission that needs a manual grant in Settings, and it only makes sense if you sideload
this APK rather than ship it on Play. The app will explain that difference plainly in Settings.

Plus: **a real calculator and unit converter** and **"what can you do"** that answers with what is
actually installed on *your* phone, not a generic list.

---

## 5. Phase 3 — **2.6.0 "Health and the day"**

| Feature | How | Honest limit |
|---|---|---|
| Steps today / this week | `TYPE_STEP_COUNTER` sensor + `ACTIVITY_RECOGNITION` permission, counted from a stored baseline | Needs the hardware sensor; a phone that was off while you walked under-counts |
| Distance walked | Steps × your stride (settable) — labelled as an estimate | Not GPS, so indoor accuracy is approximate |
| Active minutes / move reminders | Sensor deltas + an hourly nudge if you have not moved | — |
| Water | One-tap log from the card + voice ("I drank a glass"), daily target, reminders | Manual by nature; JARVIS cannot sense water |
| Sleep | Screen-off → screen-on gaps from `ACTION_SCREEN_OFF/ON` | Explicitly called an **estimate**; not medical, not from a wearable |
| Heart rate | `TYPE_HEART_RATE` if the phone has the sensor | Most phones do not; the card hides itself instead of faking |
| Daily brief | Morning summary: steps target, first appointment, unread messages, weather if a network source is set | — |

Health lives as a new HUD module with a 7-day ring, and every number it speaks is read from the same
store the card shows — no separate "voice numbers" that can drift.

---

## 6. Phase 4 — **2.7.0 "Family, not a product"**

- **Memory of people**: relations ("Khushi is my sister") stored locally, used in phrasing ("your
  sister messaged") and to disambiguate contacts.
- **Conversation**: multi-turn without repeating context; JARVIS asks one short question instead of
  giving up, and remembers the answer for the rest of the task.
- **Mood-aware brevity**: terse when you're driving/driving-mode, warmer in the evening, no jokes at
  3 a.m. — driven by time, screen state and recent commands, not by a fake "emotion engine".
- **Proactive but quiet**: says the useful thing at the right moment (leave now for your 6 p.m. meeting,
  battery will not last the trip home) and stays silent otherwise. Everything under one switch.
- **The identity answer**, written once and enforced everywhere: *"I was built by Rishu Jaswar. I'm his
  JARVIS."* No provider, no key, no model — in voice, in the terminal, in AI replies.

---

## 7. Phase 5 — **2.8.0 "The lock screen"** — what is possible and what is not

You asked for the phone's **real** lock, not one JARVIS invents. That is the right instinct, and it
changes what I build. Here is the honest map:

**What Android allows**
1. **Use the phone's own lock as JARVIS's gate.** `KeyguardManager.createConfirmDeviceCredentialIntent()`
   shows the *system* PIN/pattern/password/biometric screen — the exact one you already use — and
   returns only pass/fail. Selecting this mode means JARVIS's custom keypad disappears and unlocking
   JARVIS becomes unlocking your phone. This is a real option and I will implement it.
2. **JARVIS over the keyguard, briefly.** A full-screen-intent notification can appear over the lock
   screen (weather/alarm-style). So "JARVIS panel while locked" is possible as a *notification*, not as
   a permanent surface.
3. **Talk to JARVIS from the lock screen** through the existing Quick Settings tile and the bubble,
   both of which work while the keyguard is up (bubble needs "display over other apps").

**What Android does not allow — and I will not pretend otherwise**
- A third-party app **cannot replace the lock screen** or draw on it permanently. Lockscreen widgets
  were removed in Android 5.0; there is no API, on ColourOS or anywhere.
- JARVIS **cannot read your PIN, pattern or fingerprint**, and cannot detect *what* your credential is.
  It can only ask the system "did the user pass?".
- If JARVIS is set as the home app, it appears the moment the keyguard is dismissed — that is the
  closest thing to "JARVIS is what you see when you unlock", and it already works. What cannot happen
  is JARVIS painting over the clock and notifications.

So Phase 5 ships: **the phone-lock option** (with the PIN mode kept as a fallback for anyone who wants
a second, app-only gate), **a lock-screen notification** with the day's essentials, and a settings page
that states these limits in plain language instead of implying more.

---

## 8. Order, size, and what each release proves

| Release | Contents | Proves |
|---|---|---|
| 2.4.0 | Phrase dictionary, open-anything, gallery fix, media permissions, WhatsApp send with verification, notification intelligence, voice identity | Every sentence gets an answer; the three bugs are gone |
| 2.5.0 | 13 new actions: files, gallery, location, distance, navigation, calendar, contacts, settings, calculator, share, screen reading | "The whole phone" |
| 2.6.0 | Health module: steps, distance, water, sleep estimate, heart rate if present, daily brief | A friend who notices your day |
| 2.7.0 | Relations memory, conversation, proactive nudges, persona | Feels like family |
| 2.8.0 | Phone-lock gate, lock-screen notification | The lock story, told honestly |

Each phase: its own PR, JVM tests for every pure rule (phrase matching, ad detection, distance maths,
step bucketing, reply ranking, haversine, unit conversion), CI green before merge, docs updated.

**New test suites planned:** `PhrasesTest`, `FuzzyMatchTest`, `NotifyBrainTest`, `DistanceTest`,
`StepsTest`, `UnitsTest`, `ReplyRankTest`, `MessagingResolveTest`.

---

## 9. What I cannot verify from CI — needs your phone

I will not claim these work until you try them: WhatsApp sending on your build, notification replies,
gallery naming on ColourOS, the step sensor, heart rate, the system credential screen, the full-screen
notification over the keyguard, and battery behaviour with the listener running all day.

---

## 10. Three answers I need before I start

1. **Lock screen** — which of these did you mean?
   (a) JARVIS should be unlocked *by your phone's own PIN/fingerprint* instead of its own keypad,
   (b) JARVIS should *appear on* the lock screen as a panel/notification,
   (c) both.
2. **File search depth** — scoped (photos, videos, downloads, documents, plus folders you pick) or the
   full "All files access" permission? The second can find anything but cannot be shipped on Play.
3. **Language** — should JARVIS understand and answer in Hinglish/Hindi, or English only?
