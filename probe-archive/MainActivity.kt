package com.example.workcontactsprobe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Minimal probe to discover what the cross-profile ("enterprise") contacts
 * directory returns on THIS device/MDM.
 *
 * It queries the ENTERPRISE_CONTENT_FILTER_URI variants (Contacts, Phone, Email)
 * with a user-supplied search term and dumps:
 *   - every column the cursor exposes (the real projection the OEM returns)
 *   - the non-null values for each matched row
 *   - the enterprise directories visible via the Directory table
 *
 * Result tells us how much of "copy all details" is actually achievable
 * through this (no-MDM-required) channel before we design the full app.
 *
 * Deliberately uses only the platform SDK (android.app.Activity + classic
 * permission APIs) and NO androidx libraries, to keep the probe tiny and
 * dependency-free.
 */
class MainActivity : Activity() {

    private lateinit var queryInput: EditText
    private lateinit var output: TextView

    private val reqReadContacts = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val buildStamp = "Work Contacts Probe — built ${BuildConfig.BUILD_TIME}"
        title = buildStamp
        val header = TextView(this).apply {
            text = buildStamp
            setTextIsSelectable(true)
            textSize = 16f
            setPadding(0, 0, 0, 16)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        queryInput = EditText(this).apply {
            hint = "Search term (part of a work contact's name)"
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        val probeBtn = Button(this).apply {
            text = "Probe enterprise contacts"
            setOnClickListener { runProbe(queryInput.text.toString().trim()) }
        }

        output = TextView(this).apply {
            setTextIsSelectable(true)
            // NOTE: do NOT set a ScrollingMovementMethod here. It conflicts with
            // setTextIsSelectable(true) and makes the text non-selectable. We put
            // the TextView inside a ScrollView for scrolling instead.
        }

        val scroller = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            addView(output)
        }

        root.addView(header)
        root.addView(queryInput)
        root.addView(probeBtn)
        root.addView(scroller)
        setContentView(root)

        ensurePermission()
    }

    private fun hasPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermission() {
        if (!hasPermission()) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), reqReadContacts)
        } else {
            append("READ_CONTACTS already granted. Enter a name/number and tap Probe.\n")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqReadContacts) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) append("READ_CONTACTS granted. Enter a name/number and tap Probe.\n")
            else append("READ_CONTACTS DENIED. The probe cannot query contacts.\n")
        }
    }

    private fun runProbe(term: String) {
        output.text = ""
        if (!hasPermission()) {
            append("No READ_CONTACTS permission.\n")
            ensurePermission()
            return
        }
        if (term.isEmpty()) {
            append("Enter a search term first (the enterprise filter requires one).\n")
            return
        }

        append("=== Probing with term: \"$term\" ===\n")

        val enterpriseDirs = probeDirectories()

        if (enterpriseDirs.isEmpty()) {
            append(
                "\n  No enterprise (work) directory found (no id >= " +
                    "${ContactsContract.Directory.ENTERPRISE_DEFAULT}). " +
                    "Cross-profile contacts are not exposed here.\n"
            )
            // Still try the bare ENTERPRISE_DEFAULT in case the directory row was hidden.
            enterpriseDirs.add(ContactsContract.Directory.ENTERPRISE_DEFAULT)
            append("  Falling back to directory=${ContactsContract.Directory.ENTERPRISE_DEFAULT}\n")
        }

        // ENTERPRISE_DEFAULT (1000000000) is the real work directory. The other
        // enterprise ids (…001 "local invisible", …008 carrier) are edge/noise,
        // so we probe the default and only fall back if it wasn't present.
        val targetDir =
            if (enterpriseDirs.contains(ContactsContract.Directory.ENTERPRISE_DEFAULT))
                ContactsContract.Directory.ENTERPRISE_DEFAULT
            else enterpriseDirs.first()

        append("\n########## directory=$targetDir ##########\n")

        val contactIds = probeFilter(
            label = "Contacts.ENTERPRISE_CONTENT_FILTER_URI",
            uri = withDirectory(
                Uri.withAppendedPath(
                    ContactsContract.Contacts.ENTERPRISE_CONTENT_FILTER_URI, Uri.encode(term)
                ),
                targetDir
            ),
            // Contacts cursor exposes the aggregate contact id in "_id".
            idColumnName = ContactsContract.Contacts._ID
        )

        probeFilter(
            label = "Phone.ENTERPRISE_CONTENT_FILTER_URI",
            uri = withDirectory(
                Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Phone.ENTERPRISE_CONTENT_FILTER_URI,
                    Uri.encode(term)
                ),
                targetDir
            )
        )

        probeFilter(
            label = "Email.ENTERPRISE_CONTENT_FILTER_URI",
            uri = withDirectory(
                Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Email.ENTERPRISE_CONTENT_FILTER_URI,
                    Uri.encode(term)
                ),
                targetDir
            )
        )

        // The whole point of this run: for every matched work contact, dump
        // EVERY data row (all mimetypes) so we can see if postal/org/note/
        // website/IM/event/structured-name are available via this channel.
        append("\n========== ALL DATA ROWS PER CONTACT (every mimetype) ==========\n")
        if (contactIds.isEmpty()) {
            append("  (no contact ids collected from the Contacts filter)\n")
        }
        for (cid in contactIds) {
            probeAllDataForContact(cid, targetDir)
        }

        append("\n=== Probe complete ===\n")
    }

    /**
     * Dumps all Data rows for one work contact, grouped by mimetype, via the
     * generic Data table under the enterprise directory. This is what tells us
     * whether "copy ALL details" is achievable (postal, org, note, etc.).
     */
    private fun probeAllDataForContact(contactId: Long, dirId: Long) {
        append("\n--- all data for contact_id=$contactId ---\n")
        val uri = ContactsContract.Data.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY, dirId.toString())
            .build()
        append("  uri: $uri (selection: CONTACT_ID=$contactId)\n")
        try {
            contentResolver.query(
                uri,
                null,
                "${ContactsContract.Data.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            ).use { c ->
                if (c == null) {
                    append("  (null cursor — generic Data not queryable in enterprise dir)\n")
                    return
                }
                append("  row count: ${c.count}\n")
                val mimeCol = c.getColumnIndex(ContactsContract.Data.MIMETYPE)
                val seenMimes = sortedSetOf<String>()
                var n = 0
                while (c.moveToNext() && n < 60) {
                    n++
                    val mime = if (mimeCol >= 0) c.getString(mimeCol) ?: "?" else "?"
                    seenMimes.add(mime)
                    val sb = StringBuilder("  [$mime] ")
                    // dump the data1..data15 payload columns (the actual field values)
                    for (i in 0 until c.columnCount) {
                        val name = c.getColumnName(i)
                        if (name.startsWith("data") || name == "mimetype") {
                            val v = safeString(c, i)
                            if (v != null && v.isNotEmpty()) sb.append("$name=$v  ")
                        }
                    }
                    append(sb.toString().trimEnd() + "\n")
                }
                append("  >>> mimetypes present: ${seenMimes.joinToString()}\n")
            }
        } catch (t: Throwable) {
            append("  ERROR: ${t.javaClass.simpleName}: ${t.message}\n")
        }
    }

    /** The enterprise filter URIs require the target directory as a query parameter. */
    private fun withDirectory(uri: Uri, directoryId: Long): Uri =
        uri.buildUpon()
            .appendQueryParameter(
                ContactsContract.DIRECTORY_PARAM_KEY, directoryId.toString()
            )
            .build()

    /**
     * Lists directories and returns the ids of any enterprise/work directories
     * (ids in the reserved range >= Directory.ENTERPRISE_DEFAULT).
     */
    private fun probeDirectories(): MutableList<Long> {
        append("\n--- Directory table ---\n")
        val enterpriseIds = mutableListOf<Long>()
        try {
            contentResolver.query(
                ContactsContract.Directory.ENTERPRISE_CONTENT_URI,
                null, null, null, null
            ).use { c ->
                if (c == null) {
                    append("  (null cursor)\n")
                    return enterpriseIds
                }
                append("  columns: ${c.columnNames.joinToString()}\n")
                val idCol = c.getColumnIndex(ContactsContract.Directory._ID)
                var n = 0
                while (c.moveToNext() && n < 50) {
                    n++
                    val sb = StringBuilder("  dir[$n]: ")
                    for (i in 0 until c.columnCount) {
                        val v = safeString(c, i)
                        if (v != null) sb.append("${c.getColumnName(i)}=$v  ")
                    }
                    append(sb.toString().trimEnd() + "\n")

                    if (idCol >= 0 && !c.isNull(idCol)) {
                        val id = c.getLong(idCol)
                        if (id >= ContactsContract.Directory.ENTERPRISE_DEFAULT) {
                            enterpriseIds.add(id)
                        }
                    }
                }
                if (n == 0) append("  (no directories returned)\n")
            }
        } catch (t: Throwable) {
            append("  ERROR querying directories: ${t.javaClass.simpleName}: ${t.message}\n")
        }
        append("  -> enterprise directory ids: ${enterpriseIds.ifEmpty { "none" }}\n")
        return enterpriseIds
    }

    private fun probeFilter(
        label: String,
        uri: Uri,
        idColumnName: String? = null
    ): List<Long> {
        append("\n--- $label ---\n")
        append("  uri: $uri\n")
        val ids = linkedSetOf<Long>()
        try {
            // projection = null -> ask the provider for its full/default column set,
            // which is exactly what we want to discover.
            contentResolver.query(uri, null, null, null, null).use { c ->
                if (c == null) {
                    append("  (null cursor — URI not supported or blocked)\n")
                    return emptyList()
                }
                append("  returned columns (${c.columnCount}): ${c.columnNames.joinToString()}\n")
                append("  row count: ${c.count}\n")

                val idCol = if (idColumnName != null) c.getColumnIndex(idColumnName) else -1
                var n = 0
                while (c.moveToNext() && n < 25) {
                    n++
                    if (idCol >= 0 && !c.isNull(idCol)) ids.add(c.getLong(idCol))
                    val sb = StringBuilder("  row[$n]: ")
                    for (i in 0 until c.columnCount) {
                        val v = safeString(c, i)
                        if (v != null && v.isNotEmpty()) {
                            sb.append("${c.getColumnName(i)}=$v  ")
                        }
                    }
                    append(sb.toString().trimEnd() + "\n")
                }
                if (n == 0) append("  (no matching rows)\n")
            }
        } catch (t: Throwable) {
            append("  ERROR: ${t.javaClass.simpleName}: ${t.message}\n")
        }
        if (idColumnName != null) {
            append("  -> collected contact ids: ${ids.ifEmpty { "none" }}\n")
        }
        return ids.toList()
    }

    private fun safeString(c: Cursor, i: Int): String? = try {
        when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_BLOB -> "<blob ${c.getBlob(i)?.size ?: 0} bytes>"
            else -> c.getString(i)
        }
    } catch (t: Throwable) {
        "<unreadable:${t.javaClass.simpleName}>"
    }

    private fun append(s: String) {
        output.append(s)
    }
}
