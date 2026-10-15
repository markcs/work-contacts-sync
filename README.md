# Work Contacts Probe

A **throwaway diagnostic app**. Its only job is to find out, on *your* specific
device and MDM, what the Android cross-profile ("enterprise") contacts directory
actually returns — the same lookup channel that the stock Messages/Dialer apps
use to show work contacts in the personal profile.

We build this **before** committing to the full "sync work contacts to personal"
app, because the whole no-MDM approach depends on:

1. whether the enterprise contact filter returns anything at all on your device, and
2. **which fields** it exposes (that decides how much of "copy all details" is possible).

This app installs **only in the personal profile**. It does not write anything.
It only reads via the enterprise filter and prints what it sees.

---

## What it does

When you type a search term and tap **Probe**, it queries, in the personal profile:

- `ContactsContract.Directory.ENTERPRISE_CONTENT_URI` — lists the visible
  directories, including the work-profile ("enterprise") directory if present.
- `Contacts.ENTERPRISE_CONTENT_FILTER_URI/<term>`
- `Phone.ENTERPRISE_CONTENT_FILTER_URI/<term>`
- `Email.ENTERPRISE_CONTENT_FILTER_URI/<term>`

For each query it dumps the **full column set** the provider returns (projection
is left null on purpose, so we see the OEM's real default columns) plus every
non-null value for each matched row. Blob columns (e.g. photo) are reported as
`<blob N bytes>`.

> The enterprise URIs are **filter**-only: you must supply a search string.
> There is no public API to enumerate *all* work contacts. That limitation is
> exactly what we are confirming the shape of here.

---

## Requirements

- A device/profile where a **work profile** exists and cross-profile contact
  search is enabled (if stock Messages already shows work contacts, it's enabled).
- Android 11+ (minSdk 30). The enterprise filter URIs exist from Android 7, but
  this probe targets 30+ for the modern runtime-permission flow.
- Android SDK with platform `android-34` and build-tools `34.0.0` (the module
  targets `compileSdk = 34`).

---

## Build

### Option A — Android Studio (easiest)
1. Open the project folder in Android Studio.
2. Let it sync Gradle (it will download Android Gradle Plugin + Kotlin on first run).
3. Run the `app` configuration onto your device **in the personal profile**.

### Option B — command line
A Gradle wrapper is included (`./gradlew`, pinned to Gradle 8.9).

```bash
# from the project root
./gradlew assembleDebug
```

The APK is produced at:
```
app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` already points `sdk.dir` at the SDK. If your SDK is elsewhere,
edit that file.

> First build downloads AGP, Kotlin, and dependencies from Google/Maven Central,
> which needs network access and a few hundred MB of free disk.

---

## Install into the PERSONAL profile

The probe must run in the **personal** profile (that's where the sync target
address book lives, and where cross-profile lookup is consumed).

```bash
# installs into the default (personal) user
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you have multiple users and need to be explicit, list users and install for
the personal user id (usually 0):

```bash
adb shell pm list users
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

Launch **Work Contacts Probe**, grant the contacts permission when prompted,
type part of a known **work** contact's name, and tap **Probe**.

---

## How to read the output

### 1. Directory table section
- If you see a directory row with a large numeric `_id` (enterprise directories
  live in a reserved high-ID range) and a package/display name pointing at the
  work profile, cross-profile contacts are visible. Good sign.
- If it's empty or only shows local/default directories, the work directory is
  not exposed to this app — the no-MDM approach may not work on this device.

### 2. The three `*_ENTERPRISE_CONTENT_FILTER_URI` sections
For each, note:

- **`row count`** — did your work contact actually come back? `0` means either
  no match for that term, or cross-profile search is disabled by your MDM.
- **`returned columns (...)`** — this is the key result. It's the exact set of
  fields the channel will give us. Look for which of these appear with values:
  - `display_name` / `DISPLAY_NAME_PRIMARY` — name (expected)
  - `data1` on the Phone query — phone number (expected)
  - `data1` on the Email query — email address (expected)
  - `photo_thumb_uri` / `photo_uri` — a photo may be available
  - **Anything beyond that** (postal address, organization, title, notes, IM,
    website, structured name parts) — if these columns are present *with values*,
    we can copy more than just the caller-ID basics.

### What the result tells us for the full app
- **Best case:** rows return with name + phone + email + photo + extra structured
  fields → "copy all available details" is largely achievable.
- **Typical case:** name + phone(s) + email + photo only → the full app can sync
  those; postal/org/notes won't be available via this channel.
- **Blocked case:** null cursors / zero rows / no enterprise directory → your MDM
  has disabled cross-profile contact search, and this approach cannot work without
  an MDM policy change. We'd need to revisit options.

Copy the probe output back and we'll use it to finalize the real design
(step 2: revise the plan based on actual field coverage).

---

## Notes / caveats

- This probe intentionally uses **no AndroidX libraries** — just the platform SDK
  (`android.app.Activity` + classic `requestPermissions`). That keeps it tiny and
  dependency-free. The real app can use AndroidX normally.
- The manifest declares a messaging-style intent filter (`APP_MESSAGING`,
  `smsto`/`sms`) to mirror the "identify as a messaging app" posture discussed
  for the full app. On most devices the enterprise filter is available to any app
  holding `READ_CONTACTS`; the messaging identity is there to maximize
  compatibility, not strictly required for the probe to work.
- Nothing is written to any contacts database. This tool is read-only.
