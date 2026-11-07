package com.jobcrawler.app

import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch

/* The experience cap, digest mode, and what the person muted. */
internal fun SettingsActivity.showTuning() {
    tuning.removeAllViews()
    val years = TuningPrefs.maxYears(this)
    tuning.addView(settingRow(
        "Most years of experience",
        if (years > 0) "Jobs asking more than $years years are dropped" else "Off: any experience gets through",
        if (years > 0) "$years years" else "Off",
    ) { pickMaxYears() })

    val digest = Switch(this).apply {
        setShowText(false); textOn = ""; textOff = ""
        text = label(
            "Digest for weaker matches",
            "Score ${TuningPrefs.digestThreshold(this@showTuning)}+ goes out at once; the rest in one message every 3 h",
        )
        isChecked = TuningPrefs.digest(this@showTuning)
        setOnCheckedChangeListener { _, on ->
            TuningPrefs.setDigest(this@showTuning, on)
            toast(if (on) "Weaker matches now wait for the digest" else "Every match goes out on its own again")
        }
    }
    tuning.addView(divider(), dividerParams())
    tuning.addView(digest, Ui.matchWidth())
    tuning.addView(settingRow("Digest threshold", "The score a job needs to skip the digest", "${TuningPrefs.digestThreshold(this)}") {
        pickThreshold()
    })

    tuning.addView(divider(), dividerParams())
    tuning.addView(Ui.title(this, "Muted"))
    val list = Ui.column(this)
    tuning.addView(list, Ui.matchWidth())
    background({ Store.shared(this).let { it.mutes.all() } }) { mutes -> showMutes(list, mutes) }
}

internal fun SettingsActivity.settingRow(title: String, caption: String, value: String, onClick: () -> Unit) = Ui.row(this).apply {
    minimumHeight = dp(56)
    addView(Ui.column(this@settingRow).apply {
        addView(Ui.body(this@settingRow, title, 16f))
        addView(Ui.muted(this@settingRow, caption))
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addView(Ui.filterChip(this@settingRow, value) { onClick() })
}

internal fun SettingsActivity.pickMaxYears() {
    val choices = listOf(0, 1, 2, 3, 4, 5, 7)
    AlertDialog.Builder(this)
        .setTitle("Most years of experience")
        .setItems(choices.map { if (it == 0) "Off" else "$it years" }.toTypedArray()) { _, which ->
            TuningPrefs.setMaxYears(this, choices[which])
            showTuning()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun SettingsActivity.pickThreshold() {
    val choices = listOf(50, 60, 70, 80, 90)
    AlertDialog.Builder(this)
        .setTitle("Digest threshold")
        .setItems(choices.map { "Score $it and up goes out at once" }.toTypedArray()) { _, which ->
            TuningPrefs.setDigestThreshold(this, choices[which])
            showTuning()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Each mute with Remove; a removed one stays as "Unmuted …" with Undo until the screen is left. */
internal fun SettingsActivity.showMutes(list: LinearLayout, mutes: List<Mutes.Entry>) {
    list.removeAllViews()
    if (mutes.isEmpty()) {
        list.addView(Ui.muted(this, "Nothing muted. Long-press a job to mute its company or title words.").apply {
            setPadding(0, dp(4), 0, dp(8))
        })
        return
    }
    for (entry in mutes) {
        val kind = if (entry.kind == Mutes.Kind.COMPANY) "Company" else "Title word"
        val holder = LinearLayout(this)
        lateinit var showMuted: () -> Unit
        val showUnmuted = {
            holder.removeAllViews()
            holder.addView(settingRow("Unmuted ${entry.label}", kind, "Undo") {
                background({ Store.shared(this).let { it.mutes.add(entry.kind.db, entry.value, entry.label); Mutes.load(it) } }) { showMuted() }
            }, Ui.matchWidth())
        }
        showMuted = {
            holder.removeAllViews()
            holder.addView(careerSiteRow(entry.label, "$kind · muted ${JobsActivity.ago(entry.createdAt) ?: ""}") {
                background({ Store.shared(this).let { it.mutes.remove(entry.kind.db, entry.value); Mutes.load(it) } }) { showUnmuted() }
            }, Ui.matchWidth())
        }
        showMuted()
        list.addView(holder, Ui.matchWidth())
    }
}
