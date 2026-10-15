package com.markcs.workcontactssync.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Hand-written SQLite persistence for the sync-state table.
 *
 * Implements the SyncedContact schema from DESIGN.md directly (no Room/KSP,
 * to keep the build free of annotation-processor version coupling). The DAO
 * surface is the same set of operations the sync engine needs.
 */
class SyncStateStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_LOOKUP TEXT NOT NULL UNIQUE,
                $COL_RAW_ID INTEGER NOT NULL,
                $COL_CONTACT_ID INTEGER NOT NULL,
                $COL_NAME_BASE TEXT NOT NULL,
                $COL_GIVEN TEXT,
                $COL_SEARCH TEXT NOT NULL,
                $COL_IDENTIFIER TEXT NOT NULL,
                $COL_WORK_HASH TEXT NOT NULL,
                $COL_WORK_TS INTEGER NOT NULL,
                $COL_PERSONAL_HASH TEXT NOT NULL,
                $COL_SELECTED INTEGER NOT NULL,
                $COL_STATE TEXT NOT NULL,
                $COL_LAST_SYNCED INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun upsert(c: SyncedContact): Long {
        val values = c.toValues()
        return writableDatabase.insertWithOnConflict(
            TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun update(c: SyncedContact) {
        writableDatabase.update(TABLE, c.toValues(), "$COL_ID = ?", arrayOf(c.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun getAll(): List<SyncedContact> {
        val out = ArrayList<SyncedContact>()
        readableDatabase.query(TABLE, null, null, null, null, null, "$COL_NAME_BASE ASC")
            .use { c -> while (c.moveToNext()) out.add(c.toSynced()) }
        return out
    }

    fun getByLookupKey(lookupKey: String): SyncedContact? {
        readableDatabase.query(
            TABLE, null, "$COL_LOOKUP = ?", arrayOf(lookupKey), null, null, null
        ).use { c -> return if (c.moveToFirst()) c.toSynced() else null }
    }

    fun setSelected(lookupKey: String, selected: Boolean) {
        val v = ContentValues().apply { put(COL_SELECTED, if (selected) 1 else 0) }
        writableDatabase.update(TABLE, v, "$COL_LOOKUP = ?", arrayOf(lookupKey))
    }

    private fun SyncedContact.toValues() = ContentValues().apply {
        if (id != 0L) put(COL_ID, id)
        put(COL_LOOKUP, workLookupKey)
        put(COL_RAW_ID, personalRawContactId)
        put(COL_CONTACT_ID, personalContactId)
        put(COL_NAME_BASE, displayNameBase)
        put(COL_GIVEN, givenName)
        put(COL_SEARCH, searchTerm)
        put(COL_IDENTIFIER, identifierAtSync)
        put(COL_WORK_HASH, workContentHash)
        put(COL_WORK_TS, workUpdatedTimestamp)
        put(COL_PERSONAL_HASH, personalContentHash)
        put(COL_SELECTED, if (selected) 1 else 0)
        put(COL_STATE, state.name)
        put(COL_LAST_SYNCED, lastSyncedAt)
    }

    private fun Cursor.toSynced() = SyncedContact(
        id = getLong(getColumnIndexOrThrow(COL_ID)),
        workLookupKey = getString(getColumnIndexOrThrow(COL_LOOKUP)),
        personalRawContactId = getLong(getColumnIndexOrThrow(COL_RAW_ID)),
        personalContactId = getLong(getColumnIndexOrThrow(COL_CONTACT_ID)),
        displayNameBase = getString(getColumnIndexOrThrow(COL_NAME_BASE)),
        givenName = getStringOrNull(COL_GIVEN),
        searchTerm = getString(getColumnIndexOrThrow(COL_SEARCH)),
        identifierAtSync = getString(getColumnIndexOrThrow(COL_IDENTIFIER)),
        workContentHash = getString(getColumnIndexOrThrow(COL_WORK_HASH)),
        workUpdatedTimestamp = getLong(getColumnIndexOrThrow(COL_WORK_TS)),
        personalContentHash = getString(getColumnIndexOrThrow(COL_PERSONAL_HASH)),
        selected = getInt(getColumnIndexOrThrow(COL_SELECTED)) == 1,
        state = SyncState.valueOf(getString(getColumnIndexOrThrow(COL_STATE))),
        lastSyncedAt = getLong(getColumnIndexOrThrow(COL_LAST_SYNCED))
    )

    private fun Cursor.getStringOrNull(col: String): String? {
        val idx = getColumnIndexOrThrow(col)
        return if (isNull(idx)) null else getString(idx)
    }

    companion object {
        private const val DB_NAME = "sync_state.db"
        private const val DB_VERSION = 1

        private const val TABLE = "synced_contact"
        private const val COL_ID = "id"
        private const val COL_LOOKUP = "work_lookup_key"
        private const val COL_RAW_ID = "personal_raw_contact_id"
        private const val COL_CONTACT_ID = "personal_contact_id"
        private const val COL_NAME_BASE = "display_name_base"
        private const val COL_GIVEN = "given_name"
        private const val COL_SEARCH = "search_term"
        private const val COL_IDENTIFIER = "identifier_at_sync"
        private const val COL_WORK_HASH = "work_content_hash"
        private const val COL_WORK_TS = "work_updated_ts"
        private const val COL_PERSONAL_HASH = "personal_content_hash"
        private const val COL_SELECTED = "selected"
        private const val COL_STATE = "state"
        private const val COL_LAST_SYNCED = "last_synced_at"
    }
}
