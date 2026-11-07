package com.jobcrawler.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import java.time.Duration
import java.time.Instant

/**
 * Every job the filters let through, newest first: the same rows the channel
 * gets, read from the phone's own store, so nothing is fetched to show them.
 * A tap opens the job, with its description and the Apply and Send buttons.
 */
class JobsActivity : Screen() {

    private var all: List<Row> = emptyList()
    private var shown: List<Row> = emptyList()
    private var emailed: Set<Long> = emptySet()
    private var handAdded: Set<Long> = emptySet()
    // What was new when the screen opened stays marked for the whole visit.
    private var seenBefore: Instant? = null

    private lateinit var header: TextView
    private lateinit var source: Spinner
    private lateinit var region: Spinner
    private lateinit var newOnly: Button
    private lateinit var withEmail: Button
    private lateinit var bestFirst: Button
    private val adapter = RowsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(this)
        title = "Jobs"

        seenBefore = lastSeen(this)
        markSeen(this)

        header = Ui.muted(this, "").apply { setPadding(dp(20), dp(12), dp(20), dp(4)) }
        source = pill(SOURCES.map { it.second })
        region = pill(REGIONS.map { it.second })
        newOnly = Ui.filterChip(this, "New") {
            newOnly.isSelected = !newOnly.isSelected
            refilter()
        }
        withEmail = Ui.filterChip(this, "Has email") {
            withEmail.isSelected = !withEmail.isSelected
            refilter()
        }
        bestFirst = Ui.filterChip(this, "Best first") {
            bestFirst.isSelected = !bestFirst.isSelected
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("sortBest", bestFirst.isSelected).apply()
            refilter()
        }
        bestFirst.isSelected = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("sortBest", false)

        // The filters sit in the bar's colour, under the title.
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
            addView(region, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
            addView(source, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
            addView(newOnly, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
            addView(withEmail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
            addView(bestFirst, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
        }
        // Rows open on a tap of the whole row: they hold no buttons of their own.
        val list = ListView(this).apply {
            adapter = this@JobsActivity.adapter
            // The cards carry their own spacing.
            divider = null
            dividerHeight = 0
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(16))
            setOnItemClickListener { _, _, position, _ ->
                startActivity(JobActivity.intent(this@JobsActivity, shown[position].id))
            }
        }

        // A long press: is it a fit, or mute its company or words.
        list.setOnItemLongClickListener { _, _, position, _ ->
            val row = shown.getOrNull(position) ?: return@setOnItemLongClickListener false
            FeedbackMenu.show(this, row) { changed -> onFeedback(row, changed) }
            true
        }

        // Five filters do not fit a phone's width: they scroll sideways.
        val filterStrip = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filters)
        }
        setContentView(Tabs.wrap(this, LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(filterStrip)
            addView(header)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }, Tab.JOBS))
    }

    // Loaded on every return, so a job emailed on its own screen shows it here.
    override fun onResume() {
        super.onResume()
        io {
            val loaded = runCatching {
                Store.shared(this).let { store ->
                    val rows = store.recentJobs()
                    Triple(rows, rows.filter { store.applications.emailOutcome(it.item)?.first == "email" }.map { it.id }.toSet(), store.handAddedIds())
                }
            }.getOrElse {
                Log.error("Jobs: could not load: ${it.javaClass.simpleName}: ${it.message}")
                Triple(emptyList<Row>(), emptySet(), emptySet())
            }
            onUi {
                all = loaded.first
                emailed = loaded.second
                handAdded = loaded.third
                refilter()
            }
        }
    }

    /** A spinner in a filter pill: it shows the short item; the list that opens uses full-height rows. */
    private fun pill(choices: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@JobsActivity, android.R.layout.simple_spinner_item, choices).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        background = getDrawable(R.drawable.filter_chip)
        setPadding(dp(14), 0, dp(14), 0)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refilter()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** After the menu: a mute hides what it matches at once; a verdict re-reads the row. */
    private fun onFeedback(row: Row, changed: FeedbackMenu.Change) {
        all = when (changed) {
            is FeedbackMenu.Change.Verdict -> all.map { if (it.id == row.id) it.copy(feedback = changed.value) else it }
            FeedbackMenu.Change.Muted -> all.filter { Mutes.reasonFor(it.item) == null }
        }
        refilter()
    }

    private fun isNew(row: Row): Boolean {
        val since = seenBefore ?: return true
        return row.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() }?.isAfter(since) ?: false
    }

    private fun refilter() {
        val key = SOURCES.getOrNull(source.selectedItemPosition)?.first
        val where = REGIONS.getOrNull(region.selectedItemPosition)?.first
        shown = all.filter { row ->
            (key == null || row.item.source == key) &&
                (where == null || row.region == where) &&
                (!newOnly.isSelected || isNew(row)) &&
                (!withEmail.isSelected || row.item.contactEmails.isNotEmpty())
        }
        // Newest first is how the store hands them over; best first ties on the newer.
        if (bestFirst.isSelected) shown = shown.sortedByDescending { it.item.score ?: -1 }
        adapter.notifyDataSetChanged()

        val fresh = all.count(::isNew)
        header.text = when {
            all.isEmpty() -> "No jobs yet. Each run adds the ones that pass the filters."
            shown.isEmpty() -> "Nothing matches these filters."
            else -> "${shown.size} of ${all.size} jobs" + if (fresh > 0) " · $fresh new since your last look" else ""
        }
    }

    private inner class RowsAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = shown[position]
            val item = row.item
            val context = this@JobsActivity

            val gulf = item.source in Mailer.AUTO_SOURCES
            val content = Ui.column(context).apply {
                // The source, the lane, and on the far side whether it is new.
                addView(Ui.row(context).apply {
                    addView(Ui.mono(context, sourceName(item), 11f, R.color.textMuted))
                    if (gulf) {
                        addView(Ui.tag(context, "Saudi & UAE", R.color.gulf, R.color.gulfSoft),
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                marginStart = dp(8)
                            })
                    }
                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    when (row.feedback) {
                        Feedback.UP -> addView(Ui.mono(context, "👍", 12f), gap())
                        Feedback.DOWN -> addView(Ui.mono(context, "👎", 12f), gap())
                    }
                    item.score?.let { addView(Ui.mono(context, "⭐ $it", 11.5f, R.color.textMuted), gap()) }
                    if (isNew(row)) addView(Ui.tag(context, "New", R.color.onPrimary, R.color.primary))
                })

                addView(Ui.title(context, item.title, 17f).apply { setPadding(0, dp(6), 0, 0) })
                listOfNotNull(item.company, item.location).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    addView(Ui.body(context, it, 14f).apply {
                        setTextColor(getColor(R.color.textMuted))
                        setPadding(0, dp(2), 0, 0)
                    })
                }

                val chips = mutableListOf<View>()
                if (row.region != Region.EGYPT && !gulf) chips += Ui.chip(context, "${row.region.flag} ${row.region.label}")
                Extract.Years.of(item)?.let { chips += Ui.chip(context, "🧭 $it") }
                item.salary?.let { chips += Ui.chip(context, "💰 $it", R.color.good, R.color.goodSoft) }
                if (row.id in handAdded) chips += Ui.chip(context, "Added by you", R.color.textMuted, R.color.background)
                when {
                    row.id in emailed -> chips += Ui.chip(context, if (gulf) "CV sent on its own" else "CV sent")
                    item.contactEmails.isNotEmpty() -> chips += Ui.chip(context, "Has email · to review", R.color.warn, R.color.warnSoft)
                }
                if (chips.isNotEmpty()) addView(Ui.chips(context, *chips.toTypedArray()).apply { setPadding(0, dp(8), 0, 0) })

                // When it was found and what Telegram did with it, coloured by how it went.
                val tone = if (gulf) R.color.gulf else statusColor(row.status)
                val status = if (gulf) "Emailed, not posted" else telegramStatus(row.status)
                addView(Ui.row(context).apply {
                    setPadding(0, dp(8), 0, 0)
                    addView(Ui.dot(context, tone, 6))
                    addView(Ui.mono(context, listOfNotNull(ago(row.createdAt), status).joinToString(" · "), 11.5f, tone),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dp(6)
                        })
                })
            }
            return Ui.card(context, content, vertical = 5)
        }

        private fun gap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        }
    }

    companion object {
        private const val PREFS = "jobs"

        /** The filter's choices: a source key and its name; null for all of them. */
        private val SOURCES: List<Pair<String?, String>> =
            listOf<Pair<String?, String>>(null to "All sources") +
                Defaults.sources.map { it to (SettingsActivity.SOURCE_LABELS[it]?.first ?: it) }

        private val REGIONS: List<Pair<Region?, String>> =
            listOf<Pair<Region?, String>>(null to "All regions") + Region.SHOWN.map { it to "${it.flag} ${it.label}" }

        fun intent(context: Context): Intent = Intent(context, JobsActivity::class.java)

        fun lastSeen(context: Context): Instant? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lastSeen", null)
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }

        private fun markSeen(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("lastSeen", Instant.now().toString())
                .apply()
        }

        fun sourceName(item: Item): String = when (item.source) {
            "tanqeeb" -> item.board?.let { "Tanqeeb ($it)" } ?: "Tanqeeb"
            else -> SOURCES.firstOrNull { it.first == item.source }?.second ?: item.source
        }

        fun telegramStatus(status: String): String = when (status) {
            Status.SENT -> "In Telegram"
            Status.PENDING -> "Waiting for Telegram"
            Status.FAILED -> "Telegram failed · retries next run"
            else -> "Not sent to Telegram"
        }

        fun statusColor(status: String): Int = when (status) {
            Status.PENDING -> R.color.warn
            Status.FAILED -> R.color.bad
            else -> R.color.textMuted
        }

        /** "5 min ago", "3 h ago", "2 d ago". */
        fun ago(iso: String?, now: Instant = Instant.now()): String? {
            val at = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            val minutes = Duration.between(at, now).toMinutes().coerceAtLeast(0)
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 48 * 60 -> "${minutes / 60} h ago"
                else -> "${minutes / (24 * 60)} d ago"
            }
        }
    }
}
