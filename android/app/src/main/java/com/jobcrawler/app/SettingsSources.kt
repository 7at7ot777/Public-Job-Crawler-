package com.jobcrawler.app

import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.jobcrawler.app.sources.RemoteFeeds

/**
 * Settings → Remote job feeds: one switch per feed, each saved on its own
 * by [FeedPrefs] and read at the source's next run. The section greys out
 * while the Remote job feeds source itself is off.
 */
// Remote job feeds is back on, so its per-feed switches section is restored.
internal fun SettingsActivity.buildFeedSwitches() {
    val card = findViewById<LinearLayout>(R.id.feedSwitches)
    val main = sourceSwitches.getValue(RemoteFeeds.KEY)
    val off = FeedPrefs.off(this)
    val hint = TextView(this, null, 0, R.style.Caption).apply { setPadding(0, dp(10), 0, dp(6)) }
    card.addView(hint, Ui.matchWidth())
    val feeds = RemoteFeeds.TOGGLEABLE.map { (id, name) ->
        Switch(this, null, 0, R.style.SwitchRow).apply {
            text = label(name, SettingsActivity.FEED_NOTES[id] ?: "Remote jobs open to Egypt")
            isChecked = id !in off
            setOnCheckedChangeListener { _, on -> if (!loading) FeedPrefs.setOn(this@buildFeedSwitches, id, on) }
        }.also { card.addView(it, Ui.matchWidth()) }
    }
    fun follow(on: Boolean) {
        for (feed in feeds) {
            feed.isEnabled = on
            feed.alpha = if (on) 1f else 0.5f
        }
        hint.text = if (on) {
            "Each feed can be switched off on its own. Working Nomads, Arbeitnow and Landing.jobs run whenever Remote job feeds is on."
        } else {
            "Turn on Remote job feeds above to use these."
        }
    }
    follow(main.isChecked)
    main.setOnCheckedChangeListener { _, on ->
        follow(on)
        if (!loading) reschedule(save())
    }
}

/*
 * The source switches, under the region each one searches: Egypt, the
 * Gulf, worldwide. The layout keeps them in one card; this sorts them
 * into groups, so a new source only needs its Region.home.
 */
internal fun SettingsActivity.groupSourcesByRegion() {
    val card = boardsGulf.parent as? LinearLayout ?: return
    val switches = sourceSwitches.entries.toList()
    for ((_, switch) in switches) card.removeView(switch)
    card.removeView(boardsGulf)
    for (region in Region.SHOWN) {
        val members = switches.filter { Region.home(it.key) == region }
        if (members.isEmpty() && region != Region.GULF) continue
        card.addView(Ui.mono(this, "${region.flag}  ${region.label.uppercase()}", 11.5f, R.color.textMuted).apply {
            setPadding(0, dp(if (card.childCount == 0) 10 else 18), 0, dp(2))
        })
        for ((_, switch) in members) card.addView(switch)
        // Saudi/UAE on Egypt's boards is a Gulf choice, whatever source it rides on.
        if (region == Region.GULF) card.addView(boardsGulf)
    }
}

/** The switch's caption, with the source's own interval when the person changed it. */
internal fun SettingsActivity.scheduleNote(key: String, note: String, settings: Settings): String =
    settings.sourceIntervals[key]?.let { "Every ${SettingsActivity.INTERVALS.firstOrNull { i -> i.first == it }?.second ?: "$it min"} (yours) · long-press to change" }
        ?: "$note · long-press to change how often"

internal fun SettingsActivity.reschedule(saved: Settings) {
    if (!scheduleOn.isChecked) return
    background({ Scheduler.start(this, saved.intervalMinutes) }) {}
}

/** A source's own schedule, or back to its default. */
internal fun SettingsActivity.pickSourceInterval(key: String, name: String) {
    val choices = listOf<Pair<Long?, String>>(null to "Its default") + SettingsActivity.INTERVALS.map { it.first to "Every ${it.second}" } + (1440L to "Once a day")
    AlertDialog.Builder(this)
        .setTitle("How often: $name")
        .setItems(choices.map { it.second }.toTypedArray()) { _, which ->
            TuningPrefs.setSourceInterval(this, key, choices[which].first)
            val saved = save()
            sourceSwitches[key]?.text = label(name, scheduleNote(key, SettingsActivity.SOURCE_LABELS.getValue(key).second, Settings.load(this)))
            reschedule(saved)
            toast("$name: ${choices[which].second.lowercase()}")
        }
        .setNegativeButton("Cancel", null)
        .show()
}
