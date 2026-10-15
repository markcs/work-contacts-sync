package com.markcs.workcontactssync.work

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Directory

/**
 * Reads work-profile contacts through the Android cross-profile ("enterprise")
 * contact filter URIs. This is the only class that talks to those URIs.
 *
 * Confirmed by the probe: querying from the personal profile with
 * READ_CONTACTS and ?directory=ENTERPRISE_DEFAULT returns matched work
 * contacts (name/photo/timestamp) plus their phones and emails. The generic
 * Data table is NOT queryable cross-profile, so only these fields exist.
 */
class WorkContactsRepository(context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    /**
     * Search work contacts by a filter term (name / number / email fragment).
     * The enterprise URIs are filter-only; an empty term yields no results.
     */
    fun search(term: String): List<WorkContact> {
        if (term.isBlank()) return emptyList()

        val base = queryContacts(term)
        if (base.isEmpty()) return emptyList()

        return base.map { loadDetailsFor(it) }
    }

    /**
     * Attach a contact's phones/emails.
     *
     * IMPORTANT: the Phone/Email enterprise filter URIs match the *filter term*
     * against the phone/email strings, NOT against the owning contact. So
     * filtering by the caller's search term (e.g. a sweep seed like "t", or a
     * name fragment) usually returns NONE of a contact's phone rows — a phone
     * number almost never contains the letters of a name. That is why "Load
     * all" showed names with empty phone fields.
     *
     * The reliable approach is to filter the Phone/Email channel by a term
     * derived from THIS contact's own name (which is guaranteed to match the
     * contact), then keep only the rows that actually belong to this contact,
     * joined on the stable LOOKUP_KEY (the synthetic CONTACT_ID is not stable
     * across separate enterprise queries).
     */
    private fun loadDetailsFor(base: Base): WorkContact {
        val probes = detailProbeTerms(base.displayName, base.displayNameAlt)
        val phones = LinkedHashMap<String, WorkPhone>()
        val emails = LinkedHashMap<String, WorkEmail>()
        for (probe in probes) {
            for (row in queryPhones(probe)) {
                if (row.belongsTo(base)) phones.putIfAbsent(row.dedupeKey(), row.value)
            }
            for (row in queryEmails(probe)) {
                if (row.belongsTo(base)) emails.putIfAbsent(row.dedupeKey(), row.value)
            }
        }
        return base.copy(phones = phones.values.toList(), emails = emails.values.toList())
            .toWorkContact()
    }

    /** A detail row tagged with both candidate join keys from its own query. */
    private class DetailRow<T>(
        val lookupKey: String?,
        val contactId: Long,
        val value: T,
        private val natural: String
    ) {
        /** A row belongs to a contact if EITHER cross-query key matches. */
        fun belongsTo(base: Base): Boolean =
            (lookupKey != null && lookupKey == base.lookupKey) || contactId == base.contactId
        fun dedupeKey(): String = natural
    }

    /**
     * Terms to probe the Phone/Email channel for THIS contact's own rows.
     *
     * We cannot filter the detail channel by the caller's search term (a phone
     * number never contains a name letter). Instead we filter by tokens of the
     * contact's own name, which match the contact via its name index. We try
     * BOTH name tokens (e.g. "Alexys" AND "Perez"): some records are only
     * returned by the enterprise Phone/Email filter under the family-name
     * token, not the given-name token, so probing only the first token missed
     * them (that was why e.g. "Alexys Perez" came back with empty details).
     */
    private fun detailProbeTerms(displayName: String, displayNameAlt: String?): List<String> {
        val source = displayName.ifBlank { displayNameAlt?.replace(",", " ") ?: "" }
        return source.trim()
            .split(Regex("\\s+"))
            .map { it.filter(Char::isLetterOrDigit) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** Re-fetch a single known work contact to check it still exists / changed. */
    fun refetchByTerm(term: String, lookupKey: String): WorkContact? =
        search(term).firstOrNull { it.lookupKey == lookupKey }

    /**
     * Approximate a full listing of work contacts by sweeping the enterprise
     * filter with single-character seed terms (a..z, 0..9). The enterprise URIs
     * are filter-only — there is no "list all" API — so this is the standard
     * best-effort workaround. Results are unioned and de-duplicated by lookupKey.
     *
     * Details (phones/emails) are loaded ONCE per unique contact, AFTER the
     * union, via that contact's own name (see loadDetailsFor). Loading details
     * inside the per-seed loop would (a) repeat the work for every seed a
     * contact's name matches and (b) attach the wrong/no rows, since a seed
     * letter does not match a phone number string.
     *
     * @param onProgress optional callback(done, total) for UI feedback.
     * @param isCancelled optional cancellation check, polled between terms.
     */
    fun sweepAll(
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): List<WorkContact> {
        val seeds = SWEEP_SEEDS
        // Phase 1: union matched contacts (name/photo/timestamp only) by seed.
        val matched = LinkedHashMap<String, Base>()
        seeds.forEachIndexed { index, seed ->
            if (isCancelled?.invoke() == true) return emptyList()
            for (b in queryContacts(seed)) {
                matched.putIfAbsent(b.lookupKey, b)
            }
            onProgress?.invoke(index + 1, seeds.size)
        }
        // Phase 2: load each unique contact's details exactly once.
        return matched.values
            .map { loadDetailsFor(it) }
            .sortedBy { it.displayName.lowercase() }
    }

    // --- internal: Contacts filter ---

    private data class Base(
        val contactId: Long,
        val lookupKey: String,
        val displayName: String,
        val displayNameAlt: String?,
        val photoUri: Uri?,
        val updated: Long
    ) {
        fun copy(
            phones: List<WorkPhone>,
            emails: List<WorkEmail>
        ): BaseWithData = BaseWithData(this, phones, emails)
    }

    private data class BaseWithData(
        val base: Base,
        val phones: List<WorkPhone>,
        val emails: List<WorkEmail>
    ) {
        fun toWorkContact(): WorkContact {
            val (family, given) = splitName(base.displayNameAlt, base.displayName)
            return WorkContact(
                lookupKey = base.lookupKey,
                displayName = base.displayName,
                familyName = family,
                givenName = given,
                photoUri = base.photoUri,
                workUpdatedTimestamp = base.updated,
                phones = phones,
                emails = emails
            )
        }
    }

    private fun queryContacts(term: String): List<Base> {
        val uri = withEnterpriseDirectory(
            Uri.withAppendedPath(Contacts.ENTERPRISE_CONTENT_FILTER_URI, Uri.encode(term))
        )
        val out = ArrayList<Base>()
        resolver.query(uri, null, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndex(Contacts._ID)
            val lookupIdx = c.getColumnIndex(Contacts.LOOKUP_KEY)
            val nameIdx = c.getColumnIndex(Contacts.DISPLAY_NAME_PRIMARY)
            val altIdx = c.getColumnIndex(Contacts.DISPLAY_NAME_ALTERNATIVE)
            val photoIdx = c.getColumnIndex(Contacts.PHOTO_URI)
            val updIdx = c.getColumnIndex(Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
            while (c.moveToNext()) {
                val lookup = str(c, lookupIdx) ?: continue
                out.add(
                    Base(
                        contactId = longAt(c, idIdx),
                        lookupKey = lookup,
                        displayName = str(c, nameIdx) ?: "",
                        displayNameAlt = str(c, altIdx),
                        photoUri = str(c, photoIdx)?.let { Uri.parse(it) },
                        updated = longAt(c, updIdx)
                    )
                )
            }
        }
        return out
    }

    private fun queryPhones(term: String): List<DetailRow<WorkPhone>> {
        val uri = withEnterpriseDirectory(
            Uri.withAppendedPath(Phone.ENTERPRISE_CONTENT_FILTER_URI, Uri.encode(term))
        )
        val out = ArrayList<DetailRow<WorkPhone>>()
        resolver.query(uri, null, null, null, null)?.use { c ->
            val lookupIdx = c.getColumnIndex(Phone.LOOKUP_KEY)
            val cidIdx = c.getColumnIndex(Phone.CONTACT_ID)
            val numIdx = c.getColumnIndex(Phone.NUMBER)   // data1
            val typeIdx = c.getColumnIndex(Phone.TYPE)     // data2
            val labelIdx = c.getColumnIndex(Phone.LABEL)   // data3
            while (c.moveToNext()) {
                val number = str(c, numIdx) ?: continue
                val type = intAt(c, typeIdx)
                out.add(
                    DetailRow(
                        lookupKey = str(c, lookupIdx),
                        contactId = longAt(c, cidIdx),
                        value = WorkPhone(number, type, str(c, labelIdx)),
                        natural = "$number:$type"
                    )
                )
            }
        }
        return out
    }

    private fun queryEmails(term: String): List<DetailRow<WorkEmail>> {
        val uri = withEnterpriseDirectory(
            Uri.withAppendedPath(Email.ENTERPRISE_CONTENT_FILTER_URI, Uri.encode(term))
        )
        val out = ArrayList<DetailRow<WorkEmail>>()
        resolver.query(uri, null, null, null, null)?.use { c ->
            val lookupIdx = c.getColumnIndex(Email.LOOKUP_KEY)
            val cidIdx = c.getColumnIndex(Email.CONTACT_ID)
            val addrIdx = c.getColumnIndex(Email.ADDRESS)  // data1
            val typeIdx = c.getColumnIndex(Email.TYPE)      // data2
            val labelIdx = c.getColumnIndex(Email.LABEL)    // data3
            while (c.moveToNext()) {
                val addr = str(c, addrIdx) ?: continue
                val type = intAt(c, typeIdx)
                out.add(
                    DetailRow(
                        lookupKey = str(c, lookupIdx),
                        contactId = longAt(c, cidIdx),
                        value = WorkEmail(addr, type, str(c, labelIdx)),
                        natural = "$addr:$type"
                    )
                )
            }
        }
        return out
    }

    private fun withEnterpriseDirectory(uri: Uri): Uri =
        uri.buildUpon()
            .appendQueryParameter(
                ContactsContract.DIRECTORY_PARAM_KEY,
                Directory.ENTERPRISE_DEFAULT.toString()
            )
            .build()

    // --- cursor helpers ---

    private fun str(c: Cursor, idx: Int): String? =
        if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else null

    private fun longAt(c: Cursor, idx: Int): Long =
        if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else 0L

    private fun intAt(c: Cursor, idx: Int): Int =
        if (idx >= 0 && !c.isNull(idx)) c.getInt(idx) else 0

    companion object {
        /** Seed terms for sweepAll: a..z then 0..9. */
        val SWEEP_SEEDS: List<String> =
            ('a'..'z').map { it.toString() } + ('0'..'9').map { it.toString() }

        /**
         * display_name_alt is "Family, Given". Split on the last ", ".
         * If there is no comma, treat the whole as the given name.
         */
        fun splitName(alt: String?, fallback: String): Pair<String?, String?> {
            if (alt.isNullOrBlank()) return null to fallback
            val idx = alt.lastIndexOf(", ")
            return if (idx >= 0) {
                val family = alt.substring(0, idx).trim()
                val given = alt.substring(idx + 2).trim()
                (family.ifBlank { null }) to (given.ifBlank { null })
            } else {
                null to alt.trim()
            }
        }
    }
}
