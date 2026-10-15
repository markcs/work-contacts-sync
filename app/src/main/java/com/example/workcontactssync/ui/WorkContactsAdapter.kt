package com.example.workcontactssync.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.workcontactssync.work.WorkContact

/** Row model: a work contact + whether it's selected + whether already synced. */
data class WorkRow(
    val contact: WorkContact,
    var selected: Boolean,
    val alreadySynced: Boolean
)

class WorkContactsAdapter(
    private val rows: MutableList<WorkRow>,
    private val onToggle: (WorkRow, Boolean) -> Unit
) : RecyclerView.Adapter<WorkContactsAdapter.VH>() {

    class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val check: CheckBox = root.getChildAt(0) as CheckBox
        val text: TextView = (root.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
        val sub: TextView = (root.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 20, 24, 20)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val cb = CheckBox(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply { textSize = 16f })
        col.addView(TextView(ctx).apply { textSize = 13f; alpha = 0.7f })
        row.addView(cb)
        row.addView(col)
        return VH(row)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        holder.text.text = r.contact.displayName +
            if (r.alreadySynced) "  ✓ synced" else ""
        val phones = r.contact.phones.joinToString(", ") { it.number }
        val emails = r.contact.emails.joinToString(", ") { it.address }
        holder.sub.text = listOf(phones, emails).filter { it.isNotBlank() }.joinToString(" · ")
        holder.sub.visibility = if (holder.sub.text.isBlank()) View.GONE else View.VISIBLE

        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = r.selected
        holder.check.setOnCheckedChangeListener { _, isChecked ->
            r.selected = isChecked
            onToggle(r, isChecked)
        }
    }

    override fun getItemCount(): Int = rows.size

    fun submit(newRows: List<WorkRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    fun selectedContacts(): List<WorkContact> = rows.filter { it.selected }.map { it.contact }

    fun selectedCount(): Int = rows.count { it.selected }

    fun hasRows(): Boolean = rows.isNotEmpty()

    /** True if every row is currently selected (used to toggle the button label). */
    fun allSelected(): Boolean = rows.isNotEmpty() && rows.all { it.selected }

    /** Select or clear all currently-shown rows. */
    fun setAllSelected(selected: Boolean) {
        rows.forEach { it.selected = selected }
        notifyDataSetChanged()
    }
}
