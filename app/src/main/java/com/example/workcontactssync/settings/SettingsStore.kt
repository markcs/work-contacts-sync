package com.example.workcontactssync.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * App settings. The only setting is the "work contact identifier" suffix that
 * is appended to the surname of every synced contact (Req 3/4).
 */
class SettingsStore(private val context: Context) {

    val identifierFlow: Flow<String> =
        context.dataStore.data.map { it[KEY_IDENTIFIER] ?: DEFAULT_IDENTIFIER }

    suspend fun getIdentifier(): String =
        context.dataStore.data.map { it[KEY_IDENTIFIER] ?: DEFAULT_IDENTIFIER }.first()

    /**
     * The suffix is the deletion safety guard (Req 5a), so it must be
     * non-empty. Blank values are rejected.
     */
    suspend fun setIdentifier(value: String) {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "Work contact identifier must not be empty" }
        context.dataStore.edit { it[KEY_IDENTIFIER] = trimmed }
    }

    companion object {
        const val DEFAULT_IDENTIFIER = "-wc"
        private val KEY_IDENTIFIER = stringPreferencesKey("work_contact_identifier")
    }
}
