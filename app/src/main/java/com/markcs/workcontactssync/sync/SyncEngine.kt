package com.markcs.workcontactssync.sync

import android.content.Context
import com.markcs.workcontactssync.contacts.ContactWriter
import com.markcs.workcontactssync.db.SyncState
import com.markcs.workcontactssync.db.SyncStateStore
import com.markcs.workcontactssync.db.SyncedContact
import com.markcs.workcontactssync.settings.SettingsStore
import com.markcs.workcontactssync.work.WorkContact
import com.markcs.workcontactssync.work.WorkContactsRepository

/**
 * Orchestrates sync between the work profile (read-only, via the enterprise
 * filter) and the personal profile (our owned local account).
 *
 * Implements DESIGN.md §6 flow and the Req 5/5a/5b/5c decision table.
 */
class SyncEngine(
    context: Context,
    private val repo: WorkContactsRepository,
    private val store: SyncStateStore,
    private val writer: ContactWriter,
    private val settings: SettingsStore
) {
    private val appContext = context.applicationContext

    data class SyncReport(
        val created: Int = 0,
        val updated: Int = 0,
        val deleted: Int = 0,
        val renamed: Int = 0,
        val conflicts: List<Conflict> = emptyList(),
        val channelUnavailable: Boolean = false,
        val message: String = ""
    )

    /** A 5c conflict awaiting user resolution. */
    data class Conflict(
        val syncedId: Long,
        val lookupKey: String,
        val displayName: String,
        val work: WorkContact
    )

    /**
     * A durable term that will re-find this contact via the enterprise filter
     * on later re-syncs. Prefer a prefix of the family or display name over the
     * ad-hoc UI search term (which may have been a sweep seed like "a").
     */
    private fun reFindTerm(work: WorkContact, fallback: String): String {
        val basis = work.familyName?.takeIf { it.isNotBlank() }
            ?: work.givenName?.takeIf { it.isNotBlank() }
            ?: work.displayName.takeIf { it.isNotBlank() }
            ?: fallback
        return basis.trim().take(3).ifBlank { fallback }
    }

    /**
     * Add newly-selected work contacts (create) immediately. Called from the
     * selection screen when the user picks contacts to sync.
     */
    suspend fun syncSelection(selected: List<WorkContact>, searchTerm: String): SyncReport {
        val identifier = settings.getIdentifier()
        val label = settings.getLabel()
        var created = 0
        var updated = 0
        for (work in selected) {
            val existing = store.getByLookupKey(work.lookupKey)
            if (existing == null) {
                val res = writer.createContact(work, identifier, label)
                store.upsert(
                    SyncedContact(
                        workLookupKey = work.lookupKey,
                        personalRawContactId = res.rawContactId,
                        personalContactId = res.contactId,
                        displayNameBase = work.familyName ?: "",
                        givenName = work.givenName,
                        searchTerm = reFindTerm(work, searchTerm),
                        identifierAtSync = identifier,
                        labelAtSync = label,
                        workContentHash = work.contentHash(),
                        workUpdatedTimestamp = work.workUpdatedTimestamp,
                        personalContentHash = res.personalContentHash,
                        selected = true,
                        state = SyncState.SYNCED,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
                created++
            } else {
                // already tracked: ensure selected, update if work changed
                store.setSelected(work.lookupKey, true)
                if (work.contentHash() != existing.workContentHash) {
                    val res = writer.updateContact(existing.personalRawContactId, work, identifier, label)
                    store.update(
                        existing.copy(
                            selected = true,
                            workContentHash = work.contentHash(),
                            workUpdatedTimestamp = work.workUpdatedTimestamp,
                            personalContentHash = res.personalContentHash,
                            labelAtSync = label,
                            state = SyncState.SYNCED,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                    updated++
                }
            }
        }
        return SyncReport(created = created, updated = updated)
    }

    /**
     * Delete ALL synced contacts from the personal profile — every contact this
     * app created (which all carry the identifier suffix on their surname).
     *
     * Each delete still goes through the same safety guard as Req 5a
     * (owned-by-us account + family name ends with the suffix), so a genuine
     * personal contact can never be removed. The sync-state rows are cleared
     * regardless, so the DB does not keep pointing at deleted contacts.
     *
     * @return number of personal contacts actually deleted.
     */
    suspend fun deleteAllSynced(): Int {
        val identifier = settings.getIdentifier()
        var deleted = 0
        for (sc in store.getAll()) {
            // Guard against the identifier having been changed after sync:
            // try the current identifier, then the one recorded at sync time.
            val removed = writer.deleteIfOwned(sc.personalRawContactId, identifier) ||
                writer.deleteIfOwned(sc.personalRawContactId, sc.identifierAtSync)
            if (removed) deleted++
            store.delete(sc.id)
        }
        return deleted
    }

    /**
     * Delete the specified synced contacts (by work lookup key) from the
     * personal profile. Same safety guard as Req 5a. Only contacts we have
     * actually synced (present in the DB) are affected; unknown keys are
     * ignored. Returns the number of personal contacts actually deleted.
     */
    suspend fun deleteSelected(lookupKeys: Collection<String>): Int {
        val identifier = settings.getIdentifier()
        var deleted = 0
        for (key in lookupKeys) {
            val sc = store.getByLookupKey(key) ?: continue
            val removed = writer.deleteIfOwned(sc.personalRawContactId, identifier) ||
                writer.deleteIfOwned(sc.personalRawContactId, sc.identifierAtSync)
            if (removed) deleted++
            store.delete(sc.id)
        }
        return deleted
    }

    /**
     * Full re-sync over all tracked contacts (DESIGN.md §6). Applies the
     * suffix-rename pass, then create/update/delete/conflict decisions.
     */
    suspend fun resync(): SyncReport {
        val identifier = settings.getIdentifier()
        val label = settings.getLabel()
        val tracked = store.getAll()

        var renamed = 0
        var updated = 0
        var deleted = 0
        val conflicts = ArrayList<Conflict>()

        // Req 5 (first): suffix-change rename pass.
        for (sc in tracked) {
            if (sc.identifierAtSync != identifier) {
                writer.renameSurname(
                    sc.personalRawContactId, sc.displayNameBase, sc.givenName, identifier
                )
                val newPersonalHash =
                    writer.personalHashFromProvider(sc.personalRawContactId, identifier)
                store.update(
                    sc.copy(
                        identifierAtSync = identifier,
                        personalContentHash = newPersonalHash,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
                renamed++
            }
        }

        // Label-change pass: the label is a Settings value, not work/personal
        // content, so a label change does NOT move either content hash and
        // would otherwise be missed by the change-detection below. Apply it
        // directly (like the suffix rename) to any contact whose applied label
        // differs from the current setting.
        for (sc in store.getAll()) {
            if (sc.labelAtSync != label) {
                writer.applyLabel(sc.personalRawContactId, label)
                store.update(
                    sc.copy(
                        labelAtSync = label,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
                updated++
            }
        }

        // Re-fetch each tracked contact's current work state.
        // Build a map lookupKey -> current WorkContact (null if gone).
        val current = HashMap<String, WorkContact?>()
        var anyWorkResult = false
        for (sc in store.getAll()) {
            val work = repo.refetchByTerm(sc.searchTerm, sc.workLookupKey)
            if (work != null) anyWorkResult = true
            current[sc.workLookupKey] = work
        }

        // Safety: if we have tracked contacts but the whole work channel
        // returned nothing, treat it as "channel unavailable" and do NOT delete.
        if (store.getAll().isNotEmpty() && !anyWorkResult) {
            return SyncReport(
                renamed = renamed,
                channelUnavailable = true,
                message = "Work contacts channel returned nothing; skipped deletes/updates to be safe."
            )
        }

        for (sc in store.getAll()) {
            val work = current[sc.workLookupKey]

            // Req 5a: deleted from work OR deselected -> delete (with guard).
            if (work == null || !sc.selected) {
                val didDelete = writer.deleteIfOwned(sc.personalRawContactId, identifier)
                store.delete(sc.id)
                if (didDelete) deleted++
                continue
            }

            // Changed?
            val workChanged = work.contentHash() != sc.workContentHash
            val personalNow = writer.personalHashFromProvider(sc.personalRawContactId, identifier)
            val personalChanged = personalNow != sc.personalContentHash

            when {
                !workChanged && !personalChanged -> { /* nothing */ }

                workChanged && !personalChanged -> {
                    // Req 5b: work changed, personal untouched -> overwrite.
                    val res = writer.updateContact(sc.personalRawContactId, work, identifier, label)
                    store.update(
                        sc.copy(
                            displayNameBase = work.familyName ?: "",
                            givenName = work.givenName,
                            workContentHash = work.contentHash(),
                            workUpdatedTimestamp = work.workUpdatedTimestamp,
                            personalContentHash = res.personalContentHash,
                            identifierAtSync = identifier,
                            labelAtSync = label,
                            state = SyncState.SYNCED,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                    updated++
                }

                !workChanged && personalChanged -> {
                    // Personal-only edit: leave it; keep baseline aligned to it.
                    store.update(
                        sc.copy(
                            personalContentHash = personalNow,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                }

                else -> {
                    // Req 5c: both changed -> conflict, ask the user.
                    store.update(sc.copy(state = SyncState.CONFLICT))
                    conflicts.add(
                        Conflict(sc.id, sc.workLookupKey, work.displayName, work)
                    )
                }
            }
        }

        return SyncReport(
            updated = updated,
            deleted = deleted,
            renamed = renamed,
            conflicts = conflicts
        )
    }

    /** Resolve a 5c conflict by taking the work version (overwrite personal). */
    suspend fun resolveTakeWork(conflict: Conflict) {
        val identifier = settings.getIdentifier()
        val label = settings.getLabel()
        val sc = store.getByLookupKey(conflict.lookupKey) ?: return
        val res = writer.updateContact(sc.personalRawContactId, conflict.work, identifier, label)
        store.update(
            sc.copy(
                displayNameBase = conflict.work.familyName ?: "",
                givenName = conflict.work.givenName,
                workContentHash = conflict.work.contentHash(),
                workUpdatedTimestamp = conflict.work.workUpdatedTimestamp,
                personalContentHash = res.personalContentHash,
                identifierAtSync = identifier,
                labelAtSync = label,
                state = SyncState.SYNCED,
                lastSyncedAt = System.currentTimeMillis()
            )
        )
    }

    /** Resolve a 5c conflict by keeping the personal edit (accept personal as new baseline). */
    suspend fun resolveKeepPersonal(conflict: Conflict) {
        val identifier = settings.getIdentifier()
        val sc = store.getByLookupKey(conflict.lookupKey) ?: return
        val personalNow = writer.personalHashFromProvider(sc.personalRawContactId, identifier)
        store.update(
            sc.copy(
                workContentHash = conflict.work.contentHash(),
                workUpdatedTimestamp = conflict.work.workUpdatedTimestamp,
                personalContentHash = personalNow,
                state = SyncState.SYNCED,
                lastSyncedAt = System.currentTimeMillis()
            )
        )
    }
}
