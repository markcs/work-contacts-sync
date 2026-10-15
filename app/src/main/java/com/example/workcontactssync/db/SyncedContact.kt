package com.example.workcontactssync.db

/** Lifecycle state of a synced contact (see DESIGN.md §4). */
enum class SyncState { SYNCED, PENDING_DELETE, CONFLICT }

/**
 * Our source of truth for what was synced and from what baseline.
 *
 * workContentHash  = hash of the work contact as of the last sync.
 * personalContentHash = hash of the personal copy right after we wrote it.
 * The pair is the three-way-merge baseline distinguishing Req 5b (work only
 * changed) from Req 5c (both sides changed).
 */
data class SyncedContact(
    val id: Long = 0,
    val workLookupKey: String,
    val personalRawContactId: Long,
    val personalContactId: Long,
    val displayNameBase: String,     // family name WITHOUT the suffix
    val givenName: String?,
    val searchTerm: String,          // a term that re-finds this contact in the enterprise filter
    val identifierAtSync: String,    // suffix value used at last write
    val workContentHash: String,
    val workUpdatedTimestamp: Long,
    val personalContentHash: String,
    val selected: Boolean,
    val state: SyncState,
    val lastSyncedAt: Long
)
