package com.markcs.workcontactssync.work

import android.net.Uri

/** A phone number copied from a work contact. */
data class WorkPhone(
    val number: String,
    val type: Int,
    val label: String?
)

/** An email copied from a work contact. */
data class WorkEmail(
    val address: String,
    val type: Int,
    val label: String?
)

/**
 * A work contact as reconstructed from the enterprise filter URIs.
 *
 * Only the fields the cross-profile channel actually exposes are present:
 * name, phones, emails, photo and a server-side change timestamp. Postal /
 * organization / notes etc. are NOT available via this channel (see DESIGN.md).
 */
data class WorkContact(
    val lookupKey: String,
    val displayName: String,
    val familyName: String?,
    val givenName: String?,
    val photoUri: Uri?,
    val workUpdatedTimestamp: Long,
    val phones: List<WorkPhone> = emptyList(),
    val emails: List<WorkEmail> = emptyList()
) {
    /**
     * A stable content fingerprint of the synced fields. Used to detect
     * work-side changes (together with workUpdatedTimestamp).
     */
    fun contentHash(): String {
        val sb = StringBuilder()
        sb.append("N:").append(displayName).append('|')
        sb.append("F:").append(familyName ?: "").append('|')
        sb.append("G:").append(givenName ?: "").append('|')
        phones.sortedBy { it.number }.forEach {
            sb.append("P:").append(it.number).append(':').append(it.type).append('|')
        }
        emails.sortedBy { it.address }.forEach {
            sb.append("E:").append(it.address).append(':').append(it.type).append('|')
        }
        sb.append("PH:").append(photoUri?.toString() ?: "")
        return ContentHash.sha256(sb.toString())
    }
}

/** Small SHA-256 helper shared by work + personal hashing. */
object ContentHash {
    fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
