# Work Contacts Sync

An Android app that copies selected **work-profile** contacts into your
**personal profile** — with a configurable surname suffix so they're easy to
spot, and full re-sync semantics (add / update / delete / conflict).

It works **without MDM / device-admin control**. The app installs only in the
personal profile and reads work contacts through Android's cross-profile
("enterprise") contacts filter — the same lookup channel the stock
Messages/Dialer apps use to show work contacts in the personal profile.

> The viability of the no-MDM approach, and exactly which fields are available,
> were confirmed empirically with a throwaway probe app before this app was
> built. See [`DESIGN.md`](DESIGN.md) §1 for the probe findings.

---

## What it does

- **Discovers work contacts** without a "list all" API. On startup (after you
  grant contacts permission) it auto-sweeps the enterprise filter with
  single-character seeds (`a`–`z`, then `0`–`9`) and unions the results,
  de-duplicated by lookup key, to approximate a full list. A search box also
  does targeted lookups, and a **Load all** button re-runs the sweep.
- **Multi-select** which contacts to sync (Select all / Deselect all, with a
  live "N selected" count). Already-synced rows show a `✓ synced` badge.
- **Copies the available fields** into a dedicated local account:
  display name, structured name, **all phone numbers**, **all emails**, and the
  **photo**.
- **Appends a configurable suffix** to the surname (default `-wc`) so synced
  work contacts are visually distinct and safely identifiable.
- **Re-syncs** on demand with full add/update/delete/conflict handling (below).
- **Deletes safely** — synced contacts can be removed individually or all at
  once, behind a strict ownership + suffix guard so a genuine personal contact
  can never be deleted.

### Field coverage (a hard platform limit)

The cross-profile channel exposes **name + all phones + all emails + photo**
only. Organization, job title, notes, postal address, website, IM, and events
are **not readable** cross-profile without MDM — the generic contacts Data
table returns a null cursor. This is a platform constraint, not a missing
feature. (Postal address via `StructuredPostal.ENTERPRISE_CONTENT_FILTER_URI`
is a deferred TODO — not yet probed.)

---

## Re-sync semantics

On **Re-sync all**, for every tracked contact:

| Work side | Personal copy | Result |
|---|---|---|
| unchanged | unchanged | nothing |
| changed   | unchanged | personal copy auto-updated from work |
| unchanged | edited by you | your personal edit kept; suffix re-applied if needed |
| changed   | edited by you | **conflict** → you choose *Take work* or *Keep personal* |
| removed from work, **or** deselected | — | deleted from personal (behind the safety guard) |

**Deletion safety guard** — a synced contact is only deleted if all of:
1. its surname ends with the current suffix, **and**
2. the RawContact belongs to this app's dedicated local account, **and**
3. a sync-state row we created points at it.

**Channel-unavailable protection** — if contacts are tracked but the entire
work channel returns nothing (e.g. an admin disabled cross-profile search),
the app treats it as "channel unavailable" and performs **no deletions**.

Changing the suffix in Settings triggers a rename pass that rewrites the
surname of every synced contact to use the new suffix.

---

## Requirements

- A device with a **work profile** where cross-profile contact search is
  enabled (if stock Messages already shows work contacts, it's enabled).
- Android 11+ (minSdk 30), compileSdk 34.
- Android SDK with platform `android-34` and build-tools `34.0.0`, JDK 17.

---

## Build

A Gradle wrapper is included (pinned to Gradle 8.9).

```bash
# from the project root
./gradlew assembleDebug
```

The APK is produced at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Set your SDK location in `local.properties` (not committed):

```
sdk.dir=/path/to/Android/Sdk
```

> If a build fails with "Cannot lock checksums cache", stop any stray Gradle
> daemon: `./gradlew --stop` (and if needed `rm -rf .gradle`).

---

## Install (PERSONAL profile)

The app must run in the **personal** profile — that's where the sync target
address book lives and where cross-profile lookup is consumed.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk

# if you have multiple users, target the personal user (usually 0) explicitly:
adb shell pm list users
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

Launch **Work Contacts Sync**, grant the contacts permission, let it sweep (or
search), select the contacts you want, and tap **Sync selected**.

> `versionCode`/`versionName` are derived from build time, so every build is
> strictly newer and `adb install -r` always upgrades.

---

## How it works

```
┌──────────────────── PERSONAL PROFILE (only install) ─────────────────────┐
│                                                                            │
│   UI  ──▶  Sync Engine  ──▶  Personal ContactsProvider                     │
│  (search/pick,  (diff & decisions,   (WRITE_CONTACTS, dedicated local      │
│   settings,      suffix logic,        RawContact account)                  │
│   conflicts)     batched writes)                                           │
│     ▲                │ reads                                                │
│     │                ▼                                                      │
│  Sync-state DB ◀─ WorkContactsRepository ──enterprise filter──▶ System     │
│  (SQLite)         (ENTERPRISE_*_FILTER,     directory=1000000000  broker → │
│                    read-only)                                    work prof │
└────────────────────────────────────────────────────────────────────────────┘
```

- **WorkContactsRepository** — the only code that touches the enterprise filter
  URIs; turns cursors into `WorkContact` domain objects (read-only).
- **Sync-state DB** — SQLite store of what was synced and from what. Holds two
  content-hash baselines (work + personal) that drive the three-way merge.
- **Sync Engine** — given work snapshot + personal state + DB baseline, produces
  create/update/delete/conflict actions.
- **ContactWriter** — applies actions via batched `ContentProviderOperation`
  into a dedicated local account
  (`com.example.workcontactssync.local`), keeping work data out of cloud backup
  and giving the deletion guard a strong ownership signal.
- **UI** — classic Android Views: search & multi-select, a Settings page for the
  suffix, and conflict-resolution dialogs.

See [`DESIGN.md`](DESIGN.md) for the full design, the empirical platform
findings, and the requirement-by-requirement rationale.

---

## Project layout

```
app/src/main/java/com/example/workcontactssync/
  work/WorkContact.kt             domain models + content hashing
  work/WorkContactsRepository.kt  enterprise-filter reads (search / sweep)
  db/SyncedContact.kt             sync-state model + SyncState enum
  db/SyncStateStore.kt            SQLite store + DAO (baselines)
  settings/SettingsStore.kt       DataStore: configurable suffix
  contacts/ContactWriter.kt       batched writes to the local account
  sync/SyncEngine.kt              orchestration + decision table
  ui/MainActivity.kt              search, multi-select, sync actions
  ui/WorkContactsAdapter.kt       RecyclerView adapter (checkboxes, badges)
  ui/SettingsActivity.kt          suffix setting + build date
probe-archive/MainActivity.kt     archived diagnostic probe (not compiled)
DESIGN.md                         full design + empirical constraints
```

> Implementation note: the app uses a hand-written `SQLiteOpenHelper` and
> classic Android Views rather than Room/Compose, to keep the build free of
> annotation-processor / compiler-version lockstep. Architecture, data model,
> and requirements are unchanged from `DESIGN.md`.

---

## Limitations

- **Field ceiling:** name + phones + emails + photo only (platform limit).
- **Discovery is search/sweep-based**, not a guaranteed-complete enumeration;
  Latin-script names are well covered by the `a`–`z` + `0`–`9` seed set.
- **Depends on cross-profile contact search staying enabled** by the work-
  profile admin. If disabled, the filters return nothing and sync halts safely
  without deleting anything.
- Non-numeric phone values (e.g. `Employee`) are copied verbatim.

---

## License

See [`LICENSE`](LICENSE).
