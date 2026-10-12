<h1 align="center">Questa — Android</h1>

<p align="center">
An RPG-style habit &amp; task tracker for Android, built with Jetpack Compose and Room.
</p>

<p align="center">
<a href="LICENSE"><img alt="License: Apache-2.0" src="https://img.shields.io/badge/License-Apache_2.0-blue.svg"></a>
<a href="NOTICE"><img alt="Third-party notices" src="https://img.shields.io/badge/Third--party-notices-NOTICE-informational.svg"></a>
<img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF.svg">
<img alt="Android" src="https://img.shields.io/badge/Android-API%2024%2B-3DDC84.svg">
</p>

---

## ⚠️ This is a vibe-coded project

**Please read this before you use, fork, or depend on it.**

Questa was written by a single person **driving AI coding agents** (Claude, Codex,
and others) through an extended, largely unsupervised build. Very little of this
code was written by hand, and very little of it was reviewed by a human who
understands all of it.

What this means for you, concretely:

- **Expect bugs.** Some are obvious (you will hit them in a day). Some are
  subtle correctness or data-integrity bugs that only show up after months of
  use. Issues are accepted, but there is no triage and no fix SLA — a report
  may sit unread indefinitely.
- **There are no releases, tags, or changelog**, and probably never will be.
  There is no CI. Nothing is automatically tested on push.
- **No security process.** There has been no audit, no threat model, and no
  dependency scanning. Do not treat the sync/auth code as hardened.
- **The architecture is uneven.** Parts of it are careful and heavily
  constraint-checked; other parts are unreviewed first-draft output. You are
  looking at the average, not the ceiling.
- **Read the code before you trust it with your data.** This app stores
  personal habit and mental-health information, and syncs it to Dropbox.
  If that matters to you, verify what it does.

It is published because the sync/parity engineering is genuinely interesting
and might be useful to someone. It is offered **as-is, with no warranty of any
kind**. By the Apache License 2.0, the contributors accept no liability.

If you want to help: the most valuable contributions are bug reports with
reproduction steps, and fixes that come with a test.

---

## What it does

- **Dailies, todos and habits** with streaks, difficulty tiers and counters.
- **RPG framing** — a character with HP, XP, gold and levels that responds to
  how you are actually doing.
- **Rewards** you can spend gold on.
- **Custom metrics and saved views** to track whatever you care about.
- **Dropbox sync** between devices, built on an event log with hybrid logical
  clocks and a three-way merge.
- **JSON import/export** of the full state, with an integrity hash.

The UI is currently **English only**; the string resources are the only place
user-facing text is collected (most of it is still inline in the Compose code),
so localising it is a real piece of work rather than a drop-in `values-sk/`.

It is a native Android port of an earlier progressive web app. The sync engine,
the import/export format and the merge semantics are deliberately
byte-compatible with that original, so the two can share data.

## Requirements

- JDK 21+ (the JDK bundled with Android Studio works)
- Android SDK with API 36 installed (`compileSdk 36`); the app runs on Android
  7.0 and newer (`minSdk 24`)
- Android Studio, or only the command line: the Gradle wrapper (`gradlew` /
  `gradlew.bat`) downloads the right Gradle version itself
- Windows, macOS or Linux all work. `build.bat` is a Windows convenience; on
  macOS/Linux use `./gradlew` directly. For a command-line build, tell Gradle
  where the SDK is: set `ANDROID_HOME`, or copy `local.properties.example` to
  `local.properties` and set `sdk.dir`
- Node.js 18+ **and** a checkout of the original web app (see
  [Parity](#parity-with-the-original-pwa)) — needed for a handful of tests and
  for the fixture tools. Without them those tests skip; everything else runs.

## Build

### Android Studio

Open the folder, let it sync Gradle, and press Run.

### build.bat (Windows)

```
build.bat dev|release|both|installdev|forget [install] [nokey]
```

Add `nokey` to build an APK without the Dropbox key pre-fill, even when
`local.properties` has one (for example `build.bat release nokey`). When you
double-click `build.bat`, it asks.

| Pick | What it does | Output |
|---|---|---|
| `1 dev` | Debug build "Questa dev" (`io.github.palo007.questa.dev`) | `out\Questa-dev.apk` |
| `2 release` | Signed release build "Questa" (`io.github.palo007.questa`) | `out\Questa.apk` |
| `3 both` | Both builds | both files |
| `4 forget` | Deletes the saved release password | — |
| `installdev` | Same as `dev install` | `out\Questa-dev.apk` |

After building it offers to install over Wi-Fi/USB adb. It prefers an already
connected Wi-Fi device, then the last used Wi-Fi address, then USB (switching
to Wi-Fi via `adb tcpip 5555`). See `tools/install-phone.ps1`.

> **Release signing.** The release key is expected at `my-upload-key.jks`
> (override with `KEYSTORE_PATH`) with alias `upload`. The password is asked
> once and cached in `signing.pwd`, encrypted with Windows DPAPI so only your
> Windows account on that PC can read it. `STORE_PASSWORD` / `KEY_PASSWORD`
> environment variables override it. If no key is configured, Gradle signs with
> the **debug** key and prints a warning — such a build cannot update an
> installed release build.
>
> **Back up your keystore and its password.** Losing them means you can never
> ship an update to an already-installed app. `.jks`, `.keystore`,
> `keystore.properties`, `signing.pwd` and `out/` are all git-ignored and must
> stay that way.

### Gradle directly

```bash
git clone <this repository>
cd <the cloned folder>
./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug
./gradlew :app:testDebugUnitTest    # optional: run the unit tests
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`; a release
build lands in `app/build/outputs/apk/release/`. The debug app is
"Questa dev" (package name ends in `.dev`), so it installs **beside** a release
install instead of replacing it, and keeps its own data.

### Release signing

A release build reads its signing key from these environment variables:

| Variable | Meaning |
|---|---|
| `KEYSTORE_PATH` | Path to your `.jks` keystore |
| `STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password |
| `KEY_ALIAS` | Key alias (default `upload`) |

Or create a git-ignored `keystore.properties` in the project folder with
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Environment
variables win over the file. If neither is set, Gradle prints a warning and
signs the release build with the **debug** key: fine for trying it out, but such
an APK cannot later be updated by a properly signed one.

### Set up Dropbox sync (optional)

The app works fully offline. Sync is optional and uses **your own** Dropbox app
key: the source contains no key.

1. Go to <https://www.dropbox.com/developers/apps> and click **Create app**.
2. Choose **Scoped access**, then **App folder** — never "Full Dropbox". With
   App folder, Questa can only see its own folder (`Apps/<your app name>/`) and
   none of your other files.
3. Name the app (any free name) and create it.
4. On the **Permissions** tab, tick `files.content.read` and
   `files.content.write`, then click **Submit**.
5. On the **Settings** tab, under **OAuth 2 → Redirect URIs**, add:
   - `questa://oauth` for the release app
   - `questa-dev://oauth` for the debug app ("Questa dev")

   Add both if you use both. The app shows the exact URI it needs in the key
   dialog.
6. On the same tab, copy the **App key**. (You never need the App secret.)
7. In Questa: **Settings → Sync → Dropbox app key**, paste the key, save, then
   tap **Connect Dropbox** and sign in to Dropbox.

The key dialog in the app has a short "How do I get one?" version of these
steps.

**Pre-fill the key for your own builds (optional).** Put
`dropboxAppKey=<your app key>` in `local.properties` (copy it from
[`local.properties.example`](local.properties.example)), or set the environment
variable `DROPBOX_APP_KEY`. The build then fills in the key on your own install
once, at first start, if no key is stored yet. `local.properties` is
git-ignored, so the key never gets committed. Without it, the app simply asks
for the key in Settings. Gradle users can pass `-PnoKeySeed` to leave the key out.

**Sync between devices, or with the Questa web app.** Every device and the web
app must use the **same** Dropbox app key, because each Dropbox app has its own
app folder. Different keys means different folders, and the data never meets.

**Changing the key** in Settings disconnects Dropbox and resets the sync state
on that device. Your local data stays; the next sync after reconnecting merges
it with what is in the new app folder.

### Troubleshooting

| What you see | Cause and fix |
|---|---|
| Dropbox says "invalid redirect_uri" | The redirect URI from step 5 is not registered on your Dropbox app. Add the one the app shows (release and debug differ). |
| The "Dropbox app key" row says "Set up" | No app key entered yet. Do step 7. |
| Questa's files land outside an `Apps/` folder, or it can see all your files | The Dropbox app was created with "Full Dropbox". Create a new one with **App folder** and enter its key. |
| Another device does not see your data | The devices use different app keys. Enter the same key on all of them. |
| Connected, then a key change disconnected you | Expected: changing the key disconnects and resets sync. Tap Connect Dropbox again. |

## Versioning

The version is set by hand in `gradle.properties`: `appVersionName` (for
example `1.0`) and `appVersionCode` (a whole number). Settings shows it at the
bottom as `v1.0` (`v1.0-dev` for a debug build), and `build.bat` uses it in the
APK file names. For each release raise both values. `appVersionCode` must always
go up: Android refuses to install an APK with a lower code over a newer one.

Tap the version 5 times within 3 seconds to open Diagnostics, which can export a
support bundle (it deliberately excludes tokens and account IDs).

## Architecture

```
app/src/main/java/com/example/
  data/          Room entities, DAOs, repositories
  data/sync/     The sync engine — the interesting part
  launcher/      The launcher icon / shortcut entry points
  reminders/     Notification scheduling
  startup/       Cold-start migrations and first-launch seeding
  ui/            Jetpack Compose screens and visuals
tools/           Node fixture generators and the parity checker
fixtures/        Golden files the tests assert against
```

The `data/` and `data/sync/` packages are the load-bearing ones:

- **`SerializationEngine`** — import/export. Tokenised field maps, event-array
  detokenisation, canonical JSON, and the SHA-256 integrity hash.
- **`CanonicalJson`** — byte-exact canonical serialisation. Key order and number
  formatting are part of the contract, not an implementation detail.
- **Merge / HLC** — hybrid logical clocks for ordering, a three-way merge with
  conflict resolution, and tombstones for deletes.

## Parity with the original PWA

The native app must stay interchangeable with the web app it was ported from.
The rules, briefly:

- Changes to the **export format, the HLC clock, the merge, tombstones or the
  database schema** are never a Kotlin-only decision. They need a change
  request agreed with the other side, plus a golden fixture, *before* merging.
- A Kotlin-only *feature* is allowed, but must be recorded as an accepted
  divergence so it is never silently "fixed" back.
- Anything touching the parity zone carries a one-line commit trailer:

  ```
  Parity: none - <reason>
  Parity: CR-<id>
  Parity: DIV-<id>
  ```

Run the checker before merging to a release branch:

```bash
node tools/parity_check.mjs
```

It verifies this app's output against the **original web app**, which lives in
its own repository: <https://github.com/Palo007/Questa>. That repo is *not*
vendored here. Point `OPTI_ROOT` at a local checkout of it, or keep it as a
sibling directory named `Opti`; the pinned commit it wants is `7d5d4f3`.

```bash
git clone https://github.com/Palo007/Questa.git ../Opti
git -C ../Opti checkout 7d5d4f3
node tools/parity_check.mjs --opti-root ../Opti
```

Exit codes:

| Code | Meaning |
|---|---|
| `0` | Clean — the Kotlin export matches the pinned web-app commit. |
| `1` | Drift: the web-app checkout has moved past `7d5d4f3`, or output no longer matches. |
| `2` | Needs a full unit-test run first (so the export it reads exists), or no usable checkout. |
| `3` | A tool it shells out to is missing. |

The checker also needs `app/build/kotlin_torture_export.json`, which a full
`./gradlew testDebugUnitTest` produces. On a fresh clone, run the tests first;
without the web-app checkout the checker exits `2` with an explanation rather
than failing on something unrelated.

## Testing

```bash
./gradlew testDebugUnitTest        # JVM + Robolectric unit tests
./gradlew connectedDebugAndroidTest   # instrumented tests (needs a device)
```

The fixture-backed tests are the important ones. They round-trip real-shaped
data — legacy payloads with pre-HLC tasks, non-monotonic IDs, missing fields —
and assert that import and re-export produce identical output.

### About `fixtures/real_backup.json`

This fixture was derived from a real user export. **It has been fully
anonymised before publication:** every personal string (task titles, notes,
habit labels, device names, the avatar image) was replaced with synthetic text,
all UUIDs and device IDs were regenerated, and all timestamps were shifted back
by exactly 3640 days — a whole number of weeks, so weekday alignment and every
interval between events are preserved. Counts, key order, numeric literals and
the overall structure are byte-identical to the original, which is the entire
point: the edge cases the tests depend on are still there, but no real person
is.

## Privacy

The app stores your data **on your device**. It syncs to **your own Dropbox**
account using your own OAuth token. There is no backend operated by the author,
no analytics, and no crash reporting. The one network dependency is the Dropbox
API.

## Contributing

Small, focused PRs are much easier to review than large ones — especially for a
vibe-coded project. Please include a test with any behaviour change, and keep the
parity trailer convention above.

Do **not** open pull requests generated entirely by an AI agent without a human
having read and understood every line. That is the single thing that makes this
project hard to trust.

## License

Apache License 2.0 — see [LICENSE](LICENSE). Third-party attributions are in
[NOTICE](NOTICE); every runtime dependency is Apache-2.0, and the only
copyleft component (JUnit 4, EPL-1.0) is test-only and never shipped in the APK.

Copyright 2026 palo007.
