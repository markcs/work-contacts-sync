# Work → Personal Contacts Sync — Design

Full-app design for syncing selected **work-profile** contacts into the
**personal profile**, with a user-configurable surname suffix, and
re-sync semantics (add / update / delete / conflict).

This design is scoped to what the Android cross-profile ("enterprise")
contacts channel **actually exposes on the target device**, as confirmed
empirically by the probe app in this repo (see `README.md`). It assumes
**no MDM control** — the app installs only in the personal profile.

---

## 1. Empirically confirmed constraints (the foundation)

The probe (`com.example.workcontactsprobe`) was run against a real work
profile (Ericsson/Android Enterprise). Findings that shape this design:

### Available — the enterprise *filter* URIs work without MDM
Querying from the personal profile, with `READ_CONTACTS` and the
`?directory=1000000000` (`Directory.ENTERPRISE_DEFAULT`) parameter:

- `Contacts.ENTERPRISE_CONTENT_FILTER_URI/<term>` → matched work contacts
  with `display_name`, `display_name_alt` ("Family, Given"), `lookup`,
  `photo_uri`/`photo_thumb_uri`, `contact_last_updated_timestamp`, `_id`.
- `Phone.ENTERPRISE_CONTENT_FILTER_URI/<term>` → one row per phone number,
  with `data1` (number), `data2` (type), `contact_id`.
- `Email.ENTERPRISE_CONTENT_FILTER_URI/<term>` → one row per email, same shape.

Enterprise directory id confirmed: **`1000000000`** (`ENTERPRISE_DEFAULT`).
Other enterprise ids (`…001` local-invisible, `…008` carrier) are noise.

### NOT available — the generic Data table is blocked cross-profile
`content://com.android.contacts/data?directory=1000000000` returns a
**null cursor**. The cross-profile broker only serves a fixed allowlist of
lookup URIs. Therefore these fields **cannot be read** for a work contact
without MDM:

- Organization / job title
- Notes
- Website, IM, SIP
- Events (birthday/anniversary)
- Nickname, relation
- True structured-name parts (we only get the composite display name +
  the "Family, Given" alternate string)

### Consequence for Requirement 4 ("copy all details")
The achievable ceiling is **name + all phone numbers + all emails + photo**.
Organization/notes/address/etc. are **out of scope** on this channel.
This is a hard platform limit, not an implementation gap.

> **TODO (option A, deferred):** `StructuredPostal.ENTERPRISE_CONTENT_FILTER_URI`
> exists and *may* expose postal addresses via the same filter mechanism.
> Not yet probed. If postal turns out to be available, it can be added to the
> synced field set with no architectural change (it is just another
> filter-sourced field). Everything below is designed so adding it later is
> a localized change in the field-mapping layer only.

### No "list all" API
The enterprise URIs are **filter-only** — they require a search term. There
is no API to enumerate every work contact. Discovery is therefore
search-driven (see §4, Req 2).

---

## 2. High-level architecture

Single app, personal profile only.

```
┌──────────────────────────── PERSONAL PROFILE (only install) ────────────────────────────┐
│                                                                                           │
│  ┌───────────────┐   ┌─────────────────────┐   ┌───────────────────────────────────┐     │
│  │   UI (Compose) │──▶│  Sync Engine         │──▶│  Personal ContactsProvider          │   │
│  │  - search/pick │   │  - diff & decisions  │   │  (WRITE_CONTACTS, dedicated local   │   │
│  │  - settings    │   │  - suffix logic      │   │   RawContact account)               │   │
│  │  - conflicts   │   │  - batched writes    │   └───────────────────────────────────┘     │
│  └───────────────┘   └──────────┬──────────┘                                               │
│          ▲                      │ reads                                                     │
│          │                      ▼                                                           │
│   ┌──────┴───────┐   ┌─────────────────────────┐   enterprise filter   ┌───────────────┐   │
│   │ Sync-state DB │◀─│  WorkContactsRepository  │──────────────────────▶│ System broker  │  │
│   │   (Room)      │   │  (ENTERPRISE_*_FILTER)   │   directory=1e9       │ → work profile │  │
│   └───────────────┘   └─────────────────────────┘                       └───────────────┘   │
└───────────────────────────────────────────────────────────────────────────────────────────┘
```

Layers:
- **WorkContactsRepository** — the only code that talks to the enterprise
  filter URIs. Turns raw cursors into `WorkContact` domain objects.
- **Sync-state DB (Room)** — our source of truth for *what we synced and from
  what*. Enables all of Req 5/5a/5b/5c.
- **Sync Engine** — pure-ish logic: given work snapshot + personal state + DB
  baseline, produce a list of actions (create/update/delete/conflict).
- **Personal ContactsProvider writer** — applies actions via batched
  `ContentProviderOperation`.
- **UI** — search & multi-select, settings (the suffix), conflict resolution.

---

## 3. Identity & field model

### 3.1 Stable work key (critical for re-sync)
Android row `_id`s are not stable. Use `ContactsContract.Contacts.LOOKUP_KEY`
as the durable identity of a work contact. Confirmed present in probe output
(e.g. `c-1665r303-2D3D…`). Store it as `workLookupKey`.

Fallback if a lookup key ever churns: a normalized composite of
`display_name` + sorted(phone numbers) + sorted(emails).

### 3.2 Change detection without the Data table
We cannot hash arbitrary fields (no Data access), but the enterprise Contacts
filter gives **`contact_last_updated_timestamp`** (e.g. `1791783319555`).
Use it as the primary work-side change signal, backed by a content hash of
the fields we *can* read (name + phones + emails + photo-uri), for safety.

### 3.3 `WorkContact` domain object (what we can build)
```
WorkContact(
    lookupKey: String,                 // stable id
    displayName: String,               // "Björn Halvarsson"
    familyName: String?, givenName: String?,  // split from display_name_alt "Family, Given"
    phones: List<Phone(number, type, label)>,  // from Phone filter (data1/data2/data3)
    emails: List<Email(address, type, label)>, // from Email filter
    photoUri: Uri?,                    // content://…/contacts_corp/<id>/photo
    workUpdatedTimestamp: Long         // contact_last_updated_timestamp
)
```
Name splitting: `display_name_alt` is `"Family, Given"`. Parse on the last
", " to derive family/given. If no comma, treat whole as given name and leave
family empty (the suffix still appends to family — see Req 3/4).

### 3.4 Sync-state DB (Room)
```
SyncedContact(
    id: Long,                   // local PK
    workLookupKey: String,      // link to work contact (unique)
    personalRawContactId: Long, // the RawContact we created in personal profile
    personalContactId: Long,    // aggregated Contact id (for reads)
    displayNameBase: String,    // family name WITHOUT the suffix (for rename)
    givenName: String?,
    identifierAtSync: String,   // the suffix value used at last write (Req 5-rename)
    workContentHash: String,    // hash of synced fields as last seen on WORK side
    workUpdatedTimestamp: Long, // last seen contact_last_updated_timestamp
    personalContentHash: String,// hash of personal copy right after we wrote it (baseline)
    selected: Boolean,          // currently chosen for sync
    state: SyncState,           // SYNCED | PENDING_DELETE | CONFLICT
    lastSyncedAt: Long
)
```
`workContentHash` vs `personalContentHash` is the three-way-merge baseline
that distinguishes Req 5b (work changed) from Req 5c (both changed).

---

## 4. Requirement-by-requirement design

### Req 1 — Identify as a messaging application
- Declare messaging intent filters (`android.intent.category.APP_MESSAGING`,
  `SENDTO` with `sms`/`smsto`), and optionally request `RoleManager.ROLE_SMS`.
- Hold `READ_CONTACTS` + `WRITE_CONTACTS`.
- **Reality note (from probe):** on the target device the enterprise filter
  worked with plain `READ_CONTACTS`; the messaging identity was not strictly
  required. We keep the messaging posture for compatibility with devices that
  gate cross-profile caller-ID behind messaging apps, but it is not load-bearing.

### Req 2 — Read work contacts & present a selectable list
Because there is no "list all" API, discovery is **search-driven**:
- **Auto-sweep on startup (implemented default):** on launch (once contacts
  permission is granted) the app automatically sweeps the enterprise filter
  with single-character seeds (`a`..`z`, then `0`..`9`) and unions the results,
  de-duplicated by `lookupKey`, to approximate a full list of work contacts
  without any user search. A "Load all" button re-runs it; progress is shown as
  `(done/total)`. This is best-effort — coverage depends on the seed set (names
  not containing any swept character would be missed, which the a–z+0–9 set
  makes unlikely for Latin-script names).
- A search box also allows targeted lookups:
  `Contacts.ENTERPRISE_CONTENT_FILTER_URI/<term>?directory=1000000000`.
- Results shown in a multi-select list; each row shows name + a badge if it is
  already synced. Selection toggles `SyncedContact.selected`.

For each selected contact, the repository also runs the Phone and Email
filters (same term/`directory`) to collect all numbers/emails, keyed by
`contact_id` to group rows.

### Req 3 — Configurable "work contact identifier" suffix
- A single setting `workContactIdentifier` (default e.g. `-wc`), stored in
  DataStore. Validated non-empty on save (it is the deletion safety guard —
  see Req 5a).
- Applied as a suffix to the **family name**:
  `FAMILY_NAME = displayNameBase + workContactIdentifier`.

### Req 4 — On sync, copy details with suffixed surname
On create, write ONE RawContact into a **dedicated local account** (see §5)
with these Data rows:
- `StructuredName`: family = `base + suffix`, given = givenName.
- One `Phone` row per number (with type/label).
- One `Email` row per address (with type/label).
- `Photo`: fetch bytes from `photoUri` (the `contacts_corp` proxy is readable
  from the personal side) and write as the contact photo. Streamed, not inlined.
- Store `displayNameBase`, `identifierAtSync`, hashes, timestamp in the DB.

"All details" = **all available** details (name, phones, emails, photo).
Org/notes/address are not available (see §1). Documented limitation.

### Req 5 (first) — Suffix changed → update existing names
When `workContactIdentifier` changes in settings:
- For every `SyncedContact` where `identifierAtSync != newIdentifier`:
  rewrite `FAMILY_NAME = displayNameBase + newIdentifier`, update
  `identifierAtSync` and `personalContentHash`.
- Done as one batched `ContentProviderOperation` transaction.

### Req 5a — Work contact removed/deselected → delete from personal (safely)
On re-sync, for each tracked `SyncedContact`:
- It is a **deletion candidate** if EITHER:
  - its `workLookupKey` no longer resolves via the enterprise filter
    (re-query its *specific* name/number, not the sweep — avoids false
    "disappeared because it fell out of the sweep"), OR
  - `selected == false` (user deselected it).
- **Safety guard before deleting** (your `-wc` requirement):
  1. the personal RawContact's family name **ends with** the current
     `workContactIdentifier`, AND
  2. the RawContact belongs to our **dedicated local account**, AND
  3. a `SyncedContact` row we created points at it.
  Only if all three hold do we delete. This makes it impossible to delete a
  genuine personal contact.

### Req 5b — Work contact updated → update personal
On re-sync, for a tracked contact still present and selected:
- Compare `workUpdatedTimestamp` (and `workContentHash`) against stored baseline.
- If work changed and the personal copy is **unchanged** since our last write
  (`personalNow == personalContentHash`): overwrite the personal Data rows with
  current work values, re-apply the suffix, refresh baselines.

### Req 5c — Personal copy edited AND work edited → ask the user
Three-way comparison each re-sync:

| work changed? | personal changed? | Action |
|---|---|---|
| no  | no  | nothing |
| yes | no  | **Req 5b** auto-update |
| no  | yes | leave personal edit; only re-apply suffix if needed |
| yes | yes | **Req 5c** → mark `state = CONFLICT`, surface resolution UI |

"Personal changed" = current hash of the personal copy (with suffix stripped
for comparison) differs from stored `personalContentHash`.
Conflict UI offers: *keep personal* / *take work* / *review field-by-field*.
Baselines updated only after the user resolves.

---

## 5. Account / storage decision

Write synced contacts into a **dedicated local (device-only) RawContact
account** — a custom `AccountType` the app owns (or the null/local account):

- Keeps work contacts **out of the user's Google/cloud backup** (avoids
  leaking work data to a personal cloud).
- Gives the deletion safety guard (Req 5a) a strong ownership signal — we only
  ever touch RawContacts in our own account.
- Avoids sync-adapter fights with Google Contacts.

---

## 6. Sync engine flow (re-sync)

```
1. Load all tracked SyncedContacts + settings (current suffix).
2. If suffix changed since last run -> run the rename pass (Req 5-first).
3. Build the "still selected" work set:
     for each tracked/selected contact, re-query its specific identity
     via the enterprise filter (name/number), directory=1000000000.
4. For each tracked contact:
     - not resolvable in work OR deselected -> Req 5a delete (with guard)
     - resolvable -> compute work vs personal vs baseline (Req 5b / 5c table)
5. For each newly-selected, not-yet-tracked work contact -> create (Req 4).
6. Collect conflicts (5c); present resolution UI; apply resolutions.
7. Commit all writes as batched ContentProviderOperations (respect the
   ~500-op applyBatch cap: chunk large batches).
8. Update DB baselines (hashes, timestamps, identifierAtSync, state).
```

---

## 7. Permissions & manifest

- `READ_CONTACTS`, `WRITE_CONTACTS`.
- Messaging identity: `APP_MESSAGING` intent filter; optional `ROLE_SMS` request.
- Runtime permission flow for contacts (classic or AndroidX Activity Result).
- No `INTERACT_ACROSS_PROFILES` — not available without MDM, and not needed for
  the filter channel.

---

## 8. Tech stack

- Kotlin, minSdk 30, targetSdk current.
- Jetpack Compose (UI), Room (sync-state DB), DataStore (settings),
  Coroutines/Flow.
- `ContactsContract` + batched `ContentProviderOperation` for all writes.
- Reuse the probe's enterprise-filter query patterns in
  `WorkContactsRepository`.

---

## 9. Build phases

1. **Repository + search UI** — enterprise filter queries → `WorkContact`
   list; multi-select (Req 2). (Probe already proves the queries.)
2. **Create + suffix + photo** — write RawContact into the dedicated local
   account with name/phones/emails/photo (Req 3, 4).
3. **Sync-state DB + baselines** (§3.4) with hashing + timestamp capture.
4. **Re-sync decisions** — 5a delete-with-guard, 5b update.
5. **Conflict resolution UI** — 5c.
6. **Suffix-change rename pass** — Req 5-first.
7. **Hardening** — batch chunking, photo streaming, dedup, large result sets,
   error recovery, optional alphabet "sweep" discovery.

---

## 10. Risks & limitations

- **Field ceiling (accepted):** name + phones + emails + photo only.
  Org/title/notes/address/website/IM/events are not available without MDM.
  (Postal is a deferred TODO — may be addable via StructuredPostal filter.)
- **Discovery is search/sweep-based**, not a guaranteed-complete list.
- **Deletion detection is heuristic**: mitigated by re-querying each tracked
  contact's *specific* identity (not the sweep) and the strict `-wc` + local-
  account ownership guard before any delete.
- **Depends on cross-profile contact search staying enabled** by the admin.
  Confirmed enabled on the target device today; if an MDM policy change
  disables it, the enterprise filters return nothing and sync halts safely
  (no deletions should be triggered by an empty result — guard: treat a
  total-zero work result as "channel unavailable", not "all deleted").
- Non-numeric phone `data1` values were observed (e.g. `data1=Employee`);
  the writer must tolerate and copy them verbatim rather than assume a number.
```
