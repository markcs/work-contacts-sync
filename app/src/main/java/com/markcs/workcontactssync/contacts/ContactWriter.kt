package com.markcs.workcontactssync.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import com.markcs.workcontactssync.work.ContentHash
import com.markcs.workcontactssync.work.WorkContact
import java.io.ByteArrayOutputStream

/**
 * Writes synced contacts into the PERSONAL profile's ContactsProvider, into a
 * dedicated local (device-only) account that this app owns. Using our own
 * account:
 *   - keeps work contacts out of any cloud/Google backup, and
 *   - is a strong ownership signal for the deletion safety guard (Req 5a).
 */
class ContactWriter(context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    data class WriteResult(
        val rawContactId: Long,
        val contactId: Long,
        val personalContentHash: String
    )

    /** The family name actually written = base + suffix. */
    private fun suffixedFamily(base: String, identifier: String) = base + identifier

    /**
     * Create a new personal RawContact for a work contact (Req 4).
     * Returns the new raw/aggregate ids and the baseline personal hash.
     *
     * @param label optional group/label title; when non-blank, the contact is
     *   added to a group with that title (in our local account) so it shows up
     *   under that label. Blank means no label is applied.
     */
    fun createContact(work: WorkContact, identifier: String, label: String = ""): WriteResult {
        val ops = ArrayList<ContentProviderOperation>()
        val rawIndex = 0

        ops.add(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .withValue(RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                .build()
        )

        val family = suffixedFamily(work.familyName ?: "", identifier)
        ops.add(
            dataInsert(rawIndex)
                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.GIVEN_NAME, work.givenName)
                .withValue(StructuredName.FAMILY_NAME, family)
                .withValue(StructuredName.DISPLAY_NAME, buildDisplayName(work.givenName, family))
                .build()
        )

        for (p in work.phones) {
            ops.add(
                dataInsert(rawIndex)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, p.number)
                    .withValue(Phone.TYPE, p.type)
                    .withValue(Phone.LABEL, p.label)
                    .build()
            )
        }
        for (e in work.emails) {
            ops.add(
                dataInsert(rawIndex)
                    .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, e.address)
                    .withValue(Email.TYPE, e.type)
                    .withValue(Email.LABEL, e.label)
                    .build()
            )
        }

        val photoBytes = work.photoUri?.let { readPhotoBytes(it) }
        if (photoBytes != null) {
            ops.add(
                dataInsert(rawIndex)
                    .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                    .withValue(Photo.PHOTO, photoBytes)
                    .build()
            )
        }

        val groupId = groupIdForLabel(label)
        if (groupId != null) {
            ops.add(
                dataInsert(rawIndex)
                    .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(GroupMembership.GROUP_ROW_ID, groupId)
                    .build()
            )
        }

        val results = applyBatchChunked(ops)
        val rawContactId = ContentUris.parseId(results.first().uri!!)
        val contactId = lookupContactId(rawContactId)
        return WriteResult(rawContactId, contactId, personalHash(work))
    }

    /**
     * Overwrite an existing personal RawContact's data rows with the current
     * work values and re-apply the suffix (Req 5b). Implemented as delete all
     * data rows + reinsert, which is simplest and correct for our owned rows.
     */
    fun updateContact(rawContactId: Long, work: WorkContact, identifier: String, label: String = ""): WriteResult {
        val ops = ArrayList<ContentProviderOperation>()
        // remove existing data rows for this raw contact
        ops.add(
            ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection("${Data.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()))
                .build()
        )
        val family = suffixedFamily(work.familyName ?: "", identifier)
        ops.add(
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValue(Data.RAW_CONTACT_ID, rawContactId)
                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.GIVEN_NAME, work.givenName)
                .withValue(StructuredName.FAMILY_NAME, family)
                .withValue(StructuredName.DISPLAY_NAME, buildDisplayName(work.givenName, family))
                .build()
        )
        for (p in work.phones) {
            ops.add(
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValue(Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, p.number)
                    .withValue(Phone.TYPE, p.type)
                    .withValue(Phone.LABEL, p.label)
                    .build()
            )
        }
        for (e in work.emails) {
            ops.add(
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValue(Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, e.address)
                    .withValue(Email.TYPE, e.type)
                    .withValue(Email.LABEL, e.label)
                    .build()
            )
        }
        val photoBytes = work.photoUri?.let { readPhotoBytes(it) }
        if (photoBytes != null) {
            ops.add(
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValue(Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                    .withValue(Photo.PHOTO, photoBytes)
                    .build()
            )
        }
        val groupId = groupIdForLabel(label)
        if (groupId != null) {
            ops.add(
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValue(Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(GroupMembership.GROUP_ROW_ID, groupId)
                    .build()
            )
        }
        applyBatchChunked(ops)
        val contactId = lookupContactId(rawContactId)
        return WriteResult(rawContactId, contactId, personalHash(work))
    }

    /**
     * Rename pass when the suffix changes (Req 5-first): rewrite the family
     * name to base + newIdentifier. Returns the new personal hash input family.
     */
    fun renameSurname(rawContactId: Long, base: String, givenName: String?, newIdentifier: String) {
        val family = suffixedFamily(base, newIdentifier)
        val ops = arrayListOf(
            ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                .withSelection(
                    "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                    arrayOf(rawContactId.toString(), StructuredName.CONTENT_ITEM_TYPE)
                )
                .withValue(StructuredName.FAMILY_NAME, family)
                .withValue(StructuredName.DISPLAY_NAME, buildDisplayName(givenName, family))
                .build()
        )
        applyBatchChunked(ops)
    }

    /**
     * Delete a synced personal contact (Req 5a) ONLY if the safety guard passes:
     *   1. the RawContact is in OUR dedicated local account, AND
     *   2. its family name ends with the expected suffix.
     * Returns true if deleted, false if the guard blocked it.
     */
    fun deleteIfOwned(rawContactId: Long, expectedIdentifier: String): Boolean {
        if (!isOwnedByUs(rawContactId)) return false
        if (!familyEndsWith(rawContactId, expectedIdentifier)) return false

        val uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId)
            .buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "false")
            .build()
        resolver.delete(uri, null, null)
        return true
    }

    // --- guards & lookups ---

    /**
     * Resolve the group row id for a label title in our own local account,
     * creating the group if necessary. Returns null for a blank label (meaning
     * "no label", so no GroupMembership row is written).
     *
     * The group lives in the same dedicated local account as the contacts, so
     * it stays device-local and out of cloud backup, consistent with the rest
     * of the synced data.
     */
    private fun groupIdForLabel(label: String): Long? {
        val title = label.trim()
        if (title.isEmpty()) return null

        resolver.query(
            Groups.CONTENT_URI,
            arrayOf(Groups._ID),
            "${Groups.ACCOUNT_TYPE} = ? AND ${Groups.ACCOUNT_NAME} = ? AND ${Groups.TITLE} = ? AND ${Groups.DELETED} = 0",
            arrayOf(ACCOUNT_TYPE, ACCOUNT_NAME, title),
            null
        )?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }

        val uri = resolver.insert(
            Groups.CONTENT_URI,
            android.content.ContentValues().apply {
                put(Groups.ACCOUNT_TYPE, ACCOUNT_TYPE)
                put(Groups.ACCOUNT_NAME, ACCOUNT_NAME)
                put(Groups.TITLE, title)
                put(Groups.GROUP_VISIBLE, 1)
            }
        ) ?: return null
        return ContentUris.parseId(uri)
    }

    private fun isOwnedByUs(rawContactId: Long): Boolean {
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts._ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val type = c.getString(0)
                val name = c.getString(1)
                return type == ACCOUNT_TYPE && name == ACCOUNT_NAME
            }
        }
        return false
    }

    private fun familyEndsWith(rawContactId: Long, identifier: String): Boolean {
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(StructuredName.FAMILY_NAME),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), StructuredName.CONTENT_ITEM_TYPE),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val family = c.getString(0) ?: return false
                return family.endsWith(identifier)
            }
        }
        return false
    }

    private fun lookupContactId(rawContactId: Long): Long {
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.CONTACT_ID),
            "${RawContacts._ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return 0L
    }

    /**
     * Current hash of the personal copy (name/phones/emails/photo-presence),
     * with the suffix stripped so it is comparable to the work content hash.
     * Reads the live data rows for the raw contact.
     */
    fun personalHashFromProvider(rawContactId: Long, identifier: String): String {
        var family: String? = null
        var given: String? = null
        val phones = ArrayList<Pair<String, Int>>()
        val emails = ArrayList<Pair<String, Int>>()
        var hasPhoto = false
        resolver.query(
            Data.CONTENT_URI,
            null,
            "${Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        )?.use { c ->
            val mimeIdx = c.getColumnIndex(Data.MIMETYPE)
            while (c.moveToNext()) {
                when (c.getString(mimeIdx)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        family = c.getString(c.getColumnIndex(StructuredName.FAMILY_NAME))
                        given = c.getString(c.getColumnIndex(StructuredName.GIVEN_NAME))
                    }
                    Phone.CONTENT_ITEM_TYPE -> {
                        val n = c.getString(c.getColumnIndex(Phone.NUMBER))
                        val t = c.getInt(c.getColumnIndex(Phone.TYPE))
                        if (n != null) phones.add(n to t)
                    }
                    Email.CONTENT_ITEM_TYPE -> {
                        val a = c.getString(c.getColumnIndex(Email.ADDRESS))
                        val t = c.getInt(c.getColumnIndex(Email.TYPE))
                        if (a != null) emails.add(a to t)
                    }
                    Photo.CONTENT_ITEM_TYPE -> hasPhoto = true
                }
            }
        }
        val baseFamily = family?.removeSuffix(identifier) ?: ""
        return personalHashRaw(baseFamily, given, phones, emails, hasPhoto)
    }

    /** Baseline hash computed from the work contact we just wrote. */
    private fun personalHash(work: WorkContact): String {
        val baseFamily = work.familyName ?: ""
        return personalHashRaw(
            baseFamily,
            work.givenName,
            work.phones.map { it.number to it.type },
            work.emails.map { it.address to it.type },
            work.photoUri != null
        )
    }

    private fun personalHashRaw(
        baseFamily: String,
        given: String?,
        phones: List<Pair<String, Int>>,
        emails: List<Pair<String, Int>>,
        hasPhoto: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("F:").append(baseFamily).append('|')
        sb.append("G:").append(given ?: "").append('|')
        phones.sortedBy { it.first }.forEach { sb.append("P:").append(it.first).append(':').append(it.second).append('|') }
        emails.sortedBy { it.first }.forEach { sb.append("E:").append(it.first).append(':').append(it.second).append('|') }
        sb.append("PH:").append(if (hasPhoto) "1" else "0")
        return ContentHash.sha256(sb.toString())
    }

    // --- helpers ---

    private fun dataInsert(rawContactBackRef: Int) =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawContactBackRef)

    private fun buildDisplayName(given: String?, family: String): String =
        listOfNotNull(given?.ifBlank { null }, family.ifBlank { null }).joinToString(" ")

    private fun readPhotoBytes(uri: Uri): ByteArray? = try {
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var read = input.read(chunk)
            while (read >= 0) {
                buffer.write(chunk, 0, read)
                read = input.read(chunk)
            }
            buffer.toByteArray()
        }
    } catch (t: Throwable) {
        null
    }

    /** applyBatch has a ~500-op cap; chunk larger batches. */
    private fun applyBatchChunked(
        ops: List<ContentProviderOperation>
    ): Array<android.content.ContentProviderResult> {
        if (ops.size <= MAX_BATCH) {
            return resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
        }
        val results = ArrayList<android.content.ContentProviderResult>()
        var i = 0
        while (i < ops.size) {
            val slice = ArrayList(ops.subList(i, minOf(i + MAX_BATCH, ops.size)))
            results.addAll(resolver.applyBatch(ContactsContract.AUTHORITY, slice))
            i += MAX_BATCH
        }
        return results.toTypedArray()
    }

    companion object {
        // Dedicated local (device-only) account that this app owns.
        const val ACCOUNT_TYPE = "com.markcs.workcontactssync.local"
        const val ACCOUNT_NAME = "Work Contacts (synced)"
        private const val MAX_BATCH = 450
    }
}
