# Changelog

All notable changes to JARVIS, newest first. Versions match `versionName` in `app/build.gradle.kts`.

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
