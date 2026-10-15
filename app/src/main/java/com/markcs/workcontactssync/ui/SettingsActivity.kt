package com.markcs.workcontactssync.ui

import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.markcs.workcontactssync.BuildConfig
import com.markcs.workcontactssync.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen (its own page). Holds the "work contact identifier" surname
 * suffix option and shows the app build date.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Work contact identifier (surname suffix)"
            textSize = 18f
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "Appended to the surname of every synced contact to avoid " +
                "overwriting personal contacts. Changing it will rename existing " +
                "synced contacts on the next Re-sync all. It must not be empty — " +
                "it is also the safety guard that prevents deleting your own contacts."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, 0, 0, 16)
        })

        val input = EditText(this).apply {
            hint = "e.g. -wc"
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(input)

        // --- Label setting ---
        root.addView(TextView(this).apply {
            text = "Label"
            textSize = 18f
            setPadding(0, 32, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "Optional. When set, this label (a contact group) is added to " +
                "every contact copied into your personal profile, so you can find " +
                "them grouped together. Leave empty to apply no label."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, 0, 0, 16)
        })

        val labelInput = EditText(this).apply {
            hint = "e.g. Work"
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(labelInput)

        val saveBtn = Button(this).apply {
            text = "Save"
            setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "Identifier cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val labelValue = labelInput.text.toString().trim()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        settings.setIdentifier(value)
                        settings.setLabel(labelValue)
                    }
                    val labelMsg = if (labelValue.isEmpty()) "no label" else "label \"$labelValue\""
                    Toast.makeText(
                        this@SettingsActivity,
                        "Saved \"$value\" ($labelMsg). Run Re-sync all to apply.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        root.addView(saveBtn)

        // Build date, shown at the bottom of the settings page.
        root.addView(TextView(this).apply {
            text = "Build date: ${BuildConfig.BUILD_TIME}"
            textSize = 13f
            alpha = 0.6f
            setTextIsSelectable(true)
            setPadding(0, 48, 0, 0)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                // push to bottom-ish by giving it weight; gravity set below
            }
            gravity = android.view.Gravity.BOTTOM
        })

        setContentView(root)

        // Prefill the current identifier and label.
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { settings.getIdentifier() }
            input.setText(current)
            input.setSelection(current.length)
            val currentLabel = withContext(Dispatchers.IO) { settings.getLabel() }
            labelInput.setText(currentLabel)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
