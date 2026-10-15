package com.markcs.workcontactssync.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * App settings:
 *  - "work contact identifier" suffix appended to the surname of every synced
 *    contact (Req 3/4) — must be non-empty (it is the delete safety guard).
 *  - an optional "label" (contact group title) applied to every synced
 *    contact. May be empty, in which case no label is applied/copied.
 */
class SettingsStore(private val context: Context) {

    val identifierFlow: Flow<String> =
        context.dataStore.data.map { it[KEY_IDENTIFIER] ?: DEFAULT_IDENTIFIER }

    suspend fun getIdentifier(): String =
        context.dataStore.data.map { it[KEY_IDENTIFIER] ?: DEFAULT_IDENTIFIER }.first()

    /**
     * The suffix appended to the surname of every synced contact. It MAY be
     * empty — an empty suffix means "append nothing". Deletion safety does not
     * depend on the suffix: `deleteIfOwned` is primarily guarded by the
     * dedicated-local-account ownership check, so an empty suffix still cannot
     * delete a genuine personal contact (it lives in a different account).
     */
    suspend fun setIdentifier(value: String) {
        context.dataStore.edit { it[KEY_IDENTIFIER] = value.trim() }
    }

    /**
     * An optional group/label title applied to every synced contact. Unlike the
     * identifier suffix this may be empty — an empty label means "no label" and
     * is simply not applied/copied.
     */
    val labelFlow: Flow<String> =
        context.dataStore.data.map { it[KEY_LABEL] ?: DEFAULT_LABEL }

    suspend fun getLabel(): String =
        context.dataStore.data.map { it[KEY_LABEL] ?: DEFAULT_LABEL }.first()

    /** Blank is allowed and means "no label". */
    suspend fun setLabel(value: String) {
        context.dataStore.edit { it[KEY_LABEL] = value.trim() }
    }

    companion object {
        const val DEFAULT_IDENTIFIER = "-wc"
        const val DEFAULT_LABEL = ""
        private val KEY_IDENTIFIER = stringPreferencesKey("work_contact_identifier")
        private val KEY_LABEL = stringPreferencesKey("work_contact_label")
    }
}
