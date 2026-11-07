package com.jobcrawler.app

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import com.jobcrawler.app.sources.LinkedInPosts
import com.jobcrawler.app.sources.SearchPlan
import com.jobcrawler.app.sources.SerperBudget
import com.jobcrawler.app.sources.YieldLedger

internal fun SettingsActivity.showKeywords() {
    keywordChips.removeAllViews()
    for (keyword in keywords) {
        keywordChips.addView(
            Ui.chip(this, keyword, R.color.text, R.color.background).apply {
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(6))
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }
}

internal fun SettingsActivity.editKeywords() {
    val field = EditText(this, null, 0, R.style.Field).apply {
        setText(keywords.joinToString(", "))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }
    val box = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(field, Ui.matchWidth())
    }
    AlertDialog.Builder(this)
        .setTitle("Keywords")
        .setMessage("Comma separated. Each one is searched on every board.")
        .setView(box)
        .setPositiveButton("Save") { _, _ ->
            keywords = Settings.splitList(field.text.toString()) ?: Defaults.keywords
            save()
            showKeywords()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun SettingsActivity.fieldLabel(text: String) = Ui.muted(this, text, 13f).apply { setPadding(0, dp(10), 0, dp(4)) }

/* Every search and filter list: a few of its entries, and Edit. */
internal fun SettingsActivity.showLists() {
    val search = findViewById<LinearLayout>(R.id.searchLists)
    fill(search, Criteria.search, dividerFirst = true)
    search.addView(divider(), dividerParams())
    search.addView(serperSection())
    fill(findViewById(R.id.filterLists), Criteria.filters, dividerFirst = false)
}

internal fun SettingsActivity.divider() = View(this).apply { setBackgroundColor(getColor(R.color.divider)) }

internal fun SettingsActivity.dividerParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
    topMargin = dp(14)
    bottomMargin = dp(4)
}

/*
 * What the hiring-posts search costs and the two ways to make it cost
 * less: places grouped into one search, and a daily cap. The numbers come
 * from the database, so they are read off the main thread.
 */
internal fun SettingsActivity.serperSection(): View {
    val searches = Criteria[Criteria.Kind.POST_KEYWORDS].size.let { keywords ->
        val places = Criteria[Criteria.Kind.POST_PLACES].size
        keywords * if (SerperPrefs.grouped(this)) (places + SearchPlan.GROUP_SIZE - 1) / SearchPlan.GROUP_SIZE else places
    }
    val cap = SerperPrefs.dailyCap(this)
    val usage = Ui.muted(this, "Reading usage…")

    background({
        Store.shared(this).let { store ->
            SerperBudget(store, cap).usage() to YieldLedger(store, LinkedInPosts.KEY).report().count { it.second.misses >= SearchPlan.COLD_AFTER }
        }
    }) { (used, cold) ->
        usage.text = buildString {
            append("Today ${used.today}${if (cap > 0) " of $cap" else ""} · this month ${used.month}")
            used.balance?.let { append(" · $it credits left") }
            if (cold > 0) append("\n$cold quiet searches asked every ${SearchPlan.COLD_EVERY_DAYS} days until they find a post")
        }
    }

    val grouped = Switch(this).apply {
        setShowText(false); textOn = ""; textOff = ""
        text = label("Group the post places", "One search names up to ${SearchPlan.GROUP_SIZE} places: about a sixth of the searches")
        isChecked = SerperPrefs.grouped(this@serperSection)
        setOnCheckedChangeListener { _, on ->
            SerperPrefs.setGrouped(this@serperSection, on)
            toast(if (on) "Grouped: the next daily search asks fewer, wider searches" else "One search per keyword and place")
            showLists()
        }
    }

    val header = Ui.row(this).apply {
        addView(Ui.title(this@serperSection, "Serper searches"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Ui.filterChip(this@serperSection, "Daily cap") { editSerperCap() }.apply {
            background = null
            setTextColor(getColor(R.color.softText))
        })
    }

    return Ui.column(this).apply {
        setPadding(0, dp(10), 0, 0)
        addView(header)
        addView(Ui.muted(this@serperSection, "$searches paid searches a day, capped at ${if (cap > 0) cap else "no limit"}"))
        addView(usage, Ui.matchWidth().apply { topMargin = dp(4) })
        addView(grouped, Ui.matchWidth().apply { topMargin = dp(8) })
    }
}

internal fun SettingsActivity.editSerperCap() {
    val field = EditText(this, null, 0, R.style.Field).apply {
        setText(SerperPrefs.dailyCap(this@editSerperCap).toString())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
    }
    val box = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(field, Ui.matchWidth())
    }
    AlertDialog.Builder(this)
        .setTitle("Daily cap")
        .setMessage("Paid Serper searches a day. Past it the search stops, sends what it found, and you get one alert. 0 means no limit.")
        .setView(box)
        .setPositiveButton("Save") { _, _ ->
            val cap = field.text.toString().trim().toIntOrNull() ?: return@setPositiveButton toast("Not a number")
            SerperPrefs.setDailyCap(this, cap)
            toast(if (cap > 0) "At most $cap searches a day" else "No daily limit")
            showLists()
        }
        .setNeutralButton("Reset") { _, _ ->
            SerperPrefs.setDailyCap(this, SerperBudget.DEFAULT_DAILY_CAP)
            showLists()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun SettingsActivity.fill(box: LinearLayout, kinds: List<Criteria.Kind>, dividerFirst: Boolean) {
    box.removeAllViews()
    for ((index, kind) in kinds.withIndex()) {
        if (dividerFirst || index > 0) {
            box.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(4)
                })
        }
        box.addView(listRow(kind))
    }
}

internal fun SettingsActivity.listRow(kind: Criteria.Kind): View {
    val entries = Criteria[kind]
    val header = Ui.row(this).apply {
        addView(Ui.title(this@listRow, kind.title),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (Criteria.isEdited(kind)) addView(Ui.chip(this@listRow, "Edited"))
        addView(Ui.filterChip(this@listRow, "Edit") { editList(kind) }.apply {
            background = null
            setTextColor(getColor(R.color.softText))
        })
    }
    val chips = FlowLayout(this, null).apply {
        for (entry in entries.take(SettingsActivity.SHOWN)) {
            addView(Ui.chip(this@listRow, entry, R.color.text, R.color.background).apply {
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (entries.size > SettingsActivity.SHOWN) {
            addView(Ui.chip(this@listRow, "+${entries.size - SettingsActivity.SHOWN} more").apply {
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { editList(kind) }
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    return Ui.column(this).apply {
        setPadding(0, dp(10), 0, 0)
        addView(header)
        addView(Ui.muted(this@listRow, kind.caption))
        addView(chips, Ui.matchWidth().apply { topMargin = dp(8) })
    }
}

internal fun SettingsActivity.editList(kind: Criteria.Kind) {
    val field = EditText(this, null, 0, R.style.Field).apply {
        setText(Criteria[kind].joinToString("\n"))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        minLines = 6
        maxLines = 14
    }
    val box = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(field, Ui.matchWidth())
    }
    AlertDialog.Builder(this)
        .setTitle(kind.title)
        .setMessage("${kind.caption}. One per line; matched as whole words, in any case.")
        .setView(box)
        .setPositiveButton("Save") { _, _ ->
            Criteria.save(this, kind, Criteria.parse(field.text.toString()) ?: emptyList())
            toast(if (Criteria.isEdited(kind)) "Saved. The next run uses it" else "Back to the default list")
            showLists()
        }
        .setNeutralButton("Reset") { _, _ ->
            Criteria.reset(this, kind)
            toast("Back to the default list")
            showLists()
        }
        .setNegativeButton("Cancel", null)
        .show()
}
