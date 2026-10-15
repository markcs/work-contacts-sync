# Work Contacts Sync — Implementation State (persisted for future sessions)

This file is auto-loaded into context in this workspace. It records WHAT has
been built, WHY key decisions were made, and HOW to continue. Authoritative
design detail lives in `DESIGN.md`; the probe background lives in `README.md`.

## Project goal
An Android app that syncs selected WORK-profile contacts into the PERSONAL
profile, with a configurable surname suffix, and re-sync semantics
(add/update/delete/conflict). No MDM control is available.

## Hard platform facts (empirically confirmed by the probe)
- Cross-profile contacts are readable from the personal profile via the
  ENTERPRISE filter URIs with `?directory=1000000000` (Directory.ENTERPRISE_DEFAULT),
  WITHOUT MDM, as long as the admin leaves cross-profile contact search enabled.
- Available fields ONLY: display name, structured-ish name (from
  `display_name_alt` = "Family, Given"), all phones, all emails, photo
  (`photo_uri` = content://.../contacts_corp/<id>/photo), and
  `contact_last_updated_timestamp` + `lookup` key.
- NOT available cross-profile: the generic Data table returns a NULL cursor, so
  organization/title/notes/postal/website/IM/events CANNOT be synced without MDM.
  (TODO option A: `StructuredPostal.ENTERPRISE_CONTENT_FILTER_URI` might expose
  postal addresses via the same filter mechanism — not yet probed.)
- Enterprise filter URIs are FILTER-ONLY (no "list all" API).

## Deliberate implementation deviations from DESIGN.md (for build robustness)
- Room  ->  hand-written SQLiteOpenHelper (no KSP/kapt annotation processor).
- Jetpack Compose  ->  classic Android Views + Material (no compose-compiler /
  Kotlin version lockstep). Architecture, data model, and all requirements are
  unchanged. Migrate to Room/Compose only if explicitly requested.

## Module / build facts
- Namespace & applicationId: `com.markcs.workcontactssync`
- minSdk 30, compileSdk 34, Kotlin via AGP 8.5.2 / Kotlin 1.9.24, Gradle 8.9.
- `versionCode` is derived from build time (minutes since 2026-01-01) and
  `versionName`/`BuildConfig.BUILD_TIME` carry the timestamp — so every build is
  strictly newer and `adb install -r` always upgrades (devices were silently
  keeping stale APKs otherwise). buildConfig feature is enabled.
- Build: `cd /home/mark/git/work-contacts-sync && ./gradlew assembleDebug`
  Output: `app/build/outputs/apk/debug/app-debug.apk`
- SDK at /home/mark/Android/Sdk (local.properties set). JDK 17.
- If a build fails with "Cannot lock checksums cache", a stray gradle daemon is
  holding a lock: `./gradlew --stop` and/or `rm -rf .gradle`.
- No adb device is reachable from this environment — runtime behavior cannot be
  verified here; only the build is verifiable.

## Source files (app/src/main/java/com/markcs/workcontactssync/)
- `work/WorkContact.kt` — WorkContact/WorkPhone/WorkEmail domain models +
  ContentHash.sha256 + contentHash() for change detection.
- `work/WorkContactsRepository.kt` — the ONLY class touching enterprise URIs.
  search(term), refetchByTerm(term,lookupKey), and sweepAll(onProgress,
  isCancelled) which iterates seeds a..z then 0..9 (SWEEP_SEEDS) and unions
  results deduped by lookupKey. splitName() parses "Family, Given".
- `db/SyncedContact.kt` — data class + SyncState enum (SYNCED/PENDING_DELETE/CONFLICT).
  Has `searchTerm` field used to re-find a contact on re-sync.
- `db/SyncStateStore.kt` — SQLiteOpenHelper, table `synced_contact`. DAO:
  upsert/update/delete/getAll/getByLookupKey/setSelected. Stores the two
  baselines workContentHash + personalContentHash (the 3-way merge basis).
- `settings/SettingsStore.kt` — DataStore. workContactIdentifier (default "-wc"),
  validated non-empty (it is the delete safety guard). Also `label` (default
  "", KEY "work_contact_label"): optional contact-group title applied to every
  synced contact; blank = not applied (no validation).
- `contacts/ContactWriter.kt` — writes to a dedicated LOCAL account
  (ACCOUNT_TYPE="com.markcs.workcontactssync.local",
  ACCOUNT_NAME="Work Contacts (synced)"). createContact/updateContact (batched
  ContentProviderOperation, MAX_BATCH 450; both take optional `label` arg and
  write a GroupMembership row via groupIdForLabel, which find-or-creates a
  Group with that title in our local account; blank label = no row),
  renameSurname, deleteIfOwned (guard:
  isOwnedByUs + familyEndsWith suffix), personalHashFromProvider (reads live
  rows, strips suffix), readPhotoBytes (streams from photo_uri).
- `sync/SyncEngine.kt` — orchestration + decision table:
  * syncSelection(selected, searchTerm): create/update newly selected.
  * resync(): suffix-rename pass -> refetch each tracked -> 5a delete-with-guard
    (deleted-from-work OR deselected), 5b update (work changed, personal
    untouched), personal-only edit (keep), 5c conflict (both changed -> ask).
    SAFETY: if there ARE tracked contacts but the whole work channel returns
    nothing, returns channelUnavailable=true and does NOT delete.
  * resolveTakeWork / resolveKeepPersonal (5c resolution).
  * deleteAllSynced(): delete every tracked contact (guarded), clears DB.
  * deleteSelected(lookupKeys): delete only chosen tracked contacts (guarded).
  * reFindTerm(work, fallback): durable re-find term (prefix of family/given).
- `ui/MainActivity.kt` — AppCompatActivity, programmatic layout. Rows:
  search field + Search + Load all; Select all/Deselect all toggle + "N selected"
  count; Sync selected / Re-sync all / Settings; Delete selected / Delete all
  synced (destructive, separated). Auto-sweeps on startup after permission grant.
  Conflicts resolved via sequential AlertDialogs (Take work / Keep personal).
  Title is just "Work Contacts Sync" (build date was removed from here).
  Requests READ_CONTACTS + WRITE_CONTACTS (RequestMultiplePermissions).
- `ui/WorkContactsAdapter.kt` — RecyclerView adapter, checkboxes, shows
  name/phones/emails + "✓ synced" badge. setAllSelected/allSelected/
  selectedCount/selectedContacts/hasRows.
- `ui/SettingsActivity.kt` — separate Settings PAGE (not a dialog): the
  identifier suffix option (prefilled, Save, non-empty validation) + a Label
  field (optional contact-group title, prefilled, blank allowed) + explanatory
  text + "Build date: <BuildConfig.BUILD_TIME>" shown at the bottom. The single
  Save button persists both the suffix and the label. Up nav to Main.

## Resources / manifest
- Launcher icon: user's Gemini image at `res/drawable/ic_launcher_image.jpg`
  used as the foreground of adaptive icons `res/mipmap-anydpi-v26/ic_launcher.xml`
  and `ic_launcher_round.xml` over `@color/ic_launcher_background` (#1E1E1E).
  Zero-install approach (no ImageMagick/ffmpeg/PIL available) — single bitmap,
  Android scales per density. Original image also at project root:
  `Gemini_Generated_Image_p5sxpkp5sxpkp5sx.jpg`.
  CAVEAT: full-bleed art as adaptive foreground gets masked/scaled; central
  motif is within the safe zone. For pixel-perfect per-density PNGs, an image
  tool must be installed, then regenerate mipmap-*/ic_launcher.png.
- Manifest: READ/WRITE_CONTACTS; messaging identity (APP_MESSAGING + sms/smsto
  intent-filter, Req 1); launcher ui.MainActivity; ui.SettingsActivity registered
  (exported=false, parentActivityName=.ui.MainActivity).

## Requirements -> status (all implemented, build-verified; runtime unverified)
1 messaging identity ✔  | 2 search + startup sweep + multi-select ✔  |
3 configurable suffix ✔ | 4 copy available fields (name/phones/emails/photo) ✔
(org/notes/postal NOT possible) | 5 suffix-change rename ✔ | 5a delete w/ guard ✔
| 5b update ✔ | 5c conflict prompt ✔. Extras: Select all, Delete selected,
Delete all synced, Settings page with build date, app icon.

## Probe (background tool, DONE)
Archived at `probe-archive/MainActivity.kt` (moved out of src so it doesn't
compile into the app). It confirmed the platform facts above.

## Likely next steps / open items
- Possible AccountManager authenticator stub if the custom local account type
  causes the provider to drop rows on some OEMs (not yet needed/confirmed).
- TODO option A: probe StructuredPostal enterprise filter for postal addresses.
- Optional: confirm-on-large-bulk-sync, progress indicator for big syncs,
  "select unsynced only". Room/Compose migration only if requested.
- Everything is build-verified only; needs a device run to confirm end-to-end.
