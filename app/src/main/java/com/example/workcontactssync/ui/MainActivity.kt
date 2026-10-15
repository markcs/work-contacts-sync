package com.example.workcontactssync.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.workcontactssync.contacts.ContactWriter
import com.example.workcontactssync.db.SyncStateStore
import com.example.workcontactssync.settings.SettingsStore
import com.example.workcontactssync.sync.SyncEngine
import com.example.workcontactssync.work.WorkContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var repo: WorkContactsRepository
    private lateinit var store: SyncStateStore
    private lateinit var writer: ContactWriter
    private lateinit var settings: SettingsStore
    private lateinit var engine: SyncEngine

    private lateinit var queryInput: EditText
    private lateinit var status: TextView
    private lateinit var adapter: WorkContactsAdapter
    private lateinit var selectAllBtn: Button
    private lateinit var selectionCount: TextView
    private var lastSearchTerm: String = ""

    private val permLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val ok = grants[Manifest.permission.READ_CONTACTS] == true &&
                grants[Manifest.permission.WRITE_CONTACTS] == true
            if (ok) {
                status.text = "Permissions granted."
                doSweep()
            } else {
                status.text = "Contacts permissions are required."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repo = WorkContactsRepository(this)
        store = SyncStateStore(this)
        writer = ContactWriter(this)
        settings = SettingsStore(this)
        engine = SyncEngine(this, repo, store, writer, settings)

        title = "Work Contacts Sync"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        // Search row
        queryInput = EditText(this).apply {
            hint = "Search work contacts (name / number / email)"
        }
        val searchBtn = Button(this).apply {
            text = "Search"
            setOnClickListener { doSearch() }
        }
        val loadAllBtn = Button(this).apply {
            text = "Load all"
            setOnClickListener { doSweep() }
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(queryInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(searchBtn)
            addView(loadAllBtn)
        }

        // Action buttons
        val syncBtn = Button(this).apply {
            text = "Sync selected"
            setOnClickListener { doSyncSelection() }
        }
        val resyncBtn = Button(this).apply {
            text = "Re-sync all"
            setOnClickListener { doResync() }
        }
        val settingsBtn = Button(this).apply {
            text = "Settings"
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        val deleteAllBtn = Button(this).apply {
            text = "Delete all synced"
            setOnClickListener { confirmDeleteAll() }
        }
        val deleteSelectedBtn = Button(this).apply {
            text = "Delete selected"
            setOnClickListener { confirmDeleteSelected() }
        }

        // Selection helpers row (makes bulk-adding easy): Select all toggle + count.
        selectAllBtn = Button(this).apply {
            text = "Select all"
            setOnClickListener { toggleSelectAll() }
        }
        selectionCount = TextView(this).apply {
            setPadding(24, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        val selectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(selectAllBtn)
            addView(selectionCount, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        // Primary sync actions.
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(syncBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(resyncBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(settingsBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        // Destructive actions, kept visually separate.
        val actionRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(deleteSelectedBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(deleteAllBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        status = TextView(this).apply {
            setPadding(0, 12, 0, 12)
            setTextIsSelectable(true)
        }

        adapter = WorkContactsAdapter(mutableListOf()) { _, _ -> updateSelectionUi() }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }

        root.addView(searchRow)
        root.addView(selectionRow)
        root.addView(actionRow)
        root.addView(actionRow2)
        root.addView(status)
        root.addView(list)
        setContentView(root)

        updateSelectionUi()
        ensurePermissions()
    }

    /** Toggle select-all / deselect-all over the currently shown rows. */
    private fun toggleSelectAll() {
        val target = !adapter.allSelected()
        adapter.setAllSelected(target)
        updateSelectionUi()
    }

    /** Keep the Select-all label and the selection count in sync with the list. */
    private fun updateSelectionUi() {
        selectAllBtn.text = if (adapter.allSelected()) "Deselect all" else "Select all"
        selectAllBtn.isEnabled = adapter.hasRows()
        val n = adapter.selectedCount()
        selectionCount.text = if (n == 0) "No contacts selected" else "$n selected"
    }

    private fun ensurePermissions() {
        val needed = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            permLauncher.launch(needed.toTypedArray())
        } else {
            // Already granted: auto-load all work contacts on startup.
            doSweep()
        }
    }

    /**
     * Load (approximately) all work contacts by sweeping the enterprise filter
     * across the alphabet + digits. Runs on startup and from the "Load all"
     * button. Preserves current selections / synced badges.
     */
    private fun doSweep() {
        lastSearchTerm = ""
        status.text = "Loading all work contacts…"
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val results = repo.sweepAll(
                    onProgress = { done, total ->
                        runOnUiThread { status.text = "Loading all work contacts… ($done/$total)" }
                    }
                )
                results.map { wc ->
                    val existing = store.getByLookupKey(wc.lookupKey)
                    WorkRow(
                        contact = wc,
                        selected = existing?.selected ?: false,
                        alreadySynced = existing != null
                    )
                }
            }
            adapter.submit(rows)
            updateSelectionUi()
            status.text = "Loaded ${rows.size} work contact(s). Select contacts and tap Sync."
        }
    }

    private fun doSearch() {
        val term = queryInput.text.toString().trim()
        if (term.isEmpty()) {
            status.text = "Enter a search term (the work directory is filter-only)."
            return
        }
        lastSearchTerm = term
        status.text = "Searching…"
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val results = repo.search(term)
                results.map { wc ->
                    WorkRow(
                        contact = wc,
                        selected = store.getByLookupKey(wc.lookupKey)?.selected ?: false,
                        alreadySynced = store.getByLookupKey(wc.lookupKey) != null
                    )
                }
            }
            adapter.submit(rows)
            updateSelectionUi()
            status.text = "Found ${rows.size} work contact(s) for \"$term\"."
        }
    }

    private fun doSyncSelection() {
        val selected = adapter.selectedContacts()
        if (selected.isEmpty()) {
            status.text = "Select at least one contact to sync."
            return
        }
        status.text = "Syncing ${selected.size} contact(s)…"
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                engine.syncSelection(selected, lastSearchTerm)
            }
            status.text = "Synced: ${report.created} created, ${report.updated} updated."
            doSearch()
        }
    }

    private fun doResync() {
        status.text = "Re-syncing all tracked contacts…"
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { engine.resync() }
            if (report.channelUnavailable) {
                status.text = report.message
                return@launch
            }
            if (report.conflicts.isNotEmpty()) {
                resolveConflictsSequentially(report.conflicts)
            }
            status.text = "Re-sync: ${report.updated} updated, ${report.deleted} deleted, " +
                "${report.renamed} renamed, ${report.conflicts.size} conflict(s)."
        }
    }

    /** Delete ALL synced contacts (every contact carrying the suffix). */
    private fun confirmDeleteAll() {
        lifecycleScope.launch {
            val identifier = withContext(Dispatchers.IO) { settings.getIdentifier() }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Delete all synced contacts?")
                .setMessage(
                    "This removes every contact this app synced into your personal " +
                        "profile (all contacts whose surname ends with \"$identifier\"). " +
                        "Your own personal contacts are not touched. This cannot be undone."
                )
                .setPositiveButton("Delete all") { _, _ ->
                    status.text = "Deleting all synced contacts…"
                    lifecycleScope.launch {
                        val n = withContext(Dispatchers.IO) { engine.deleteAllSynced() }
                        status.text = "Deleted $n synced contact(s)."
                        refreshRowsAfterChange()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Delete only the currently-selected (checked) contacts that are synced. */
    private fun confirmDeleteSelected() {
        val keys = adapter.selectedContacts().map { it.lookupKey }
        if (keys.isEmpty()) {
            status.text = "Select at least one contact to delete."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete selected synced contacts?")
            .setMessage(
                "This removes the ${keys.size} selected contact(s) from your personal " +
                    "profile (only ones this app synced). Your own personal contacts " +
                    "are not touched. This cannot be undone."
            )
            .setPositiveButton("Delete selected") { _, _ ->
                status.text = "Deleting selected…"
                lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) { engine.deleteSelected(keys) }
                    status.text = "Deleted $n selected synced contact(s)."
                    refreshRowsAfterChange()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Refresh the visible list's synced badges / selection after a deletion. */
    private fun refreshRowsAfterChange() {
        if (lastSearchTerm.isBlank()) doSweep() else doSearch()
    }

    private fun resolveConflictsSequentially(conflicts: List<SyncEngine.Conflict>) {
        if (conflicts.isEmpty()) return
        val c = conflicts.first()
        AlertDialog.Builder(this)
            .setTitle("Conflict: ${c.displayName}")
            .setMessage(
                "This contact was changed in BOTH the work profile and your " +
                    "personal copy. Which version should win?"
            )
            .setPositiveButton("Take work") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { engine.resolveTakeWork(c) }
                    resolveConflictsSequentially(conflicts.drop(1))
                }
            }
            .setNegativeButton("Keep personal") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { engine.resolveKeepPersonal(c) }
                    resolveConflictsSequentially(conflicts.drop(1))
                }
            }
            .setCancelable(false)
            .show()
    }
}
