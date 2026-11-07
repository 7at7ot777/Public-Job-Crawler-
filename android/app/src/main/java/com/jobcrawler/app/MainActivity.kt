package com.jobcrawler.app

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.work.WorkInfo
import com.jobcrawler.app.sources.Ats
import com.jobcrawler.app.sources.Glassdoor
import com.jobcrawler.app.sources.Jobright
import com.jobcrawler.app.sources.LinkedInJobs
import com.jobcrawler.app.sources.LinkedInPosts
import com.jobcrawler.app.sources.RelocateMe
import com.jobcrawler.app.sources.SerperBudget
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Home: is it running, when did it last run, and what is waiting. Settings
 * live on their own screen behind the gear. The crawling itself happens in
 * [CrawlWorker], not here.
 */
class MainActivity : Activity() {

    private lateinit var banner: View
    private lateinit var bannerIcon: ImageView
    private lateinit var bannerText: TextView
    private lateinit var statusDot: View
    private lateinit var statusLine: TextView
    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var offlineNote: View
    private lateinit var pause: Button
    private lateinit var statNew: TextView
    private lateinit var statReview: TextView
    private lateinit var statGulf: TextView
    private lateinit var sourceRows: LinearLayout
    private lateinit var gulfCard: View
    private lateinit var gulfRows: LinearLayout
    private lateinit var serperCard: View
    private lateinit var serperToday: TextView
    private lateinit var serperBar: ProgressBar
    private lateinit var serperNote: TextView
    private lateinit var footer: TextView

    private var scheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The bottom bar under the page, and "Add a post" floating over its lower end.
        val page = layoutInflater.inflate(R.layout.activity_main, null)
        setContentView(Tabs.wrap(this, page, Tab.HOME, addPostButton()))
        Log.init(this)

        banner = findViewById(R.id.banner)
        bannerIcon = findViewById(R.id.bannerIcon)
        bannerText = findViewById(R.id.bannerText)
        statusDot = findViewById(R.id.statusDot)
        statusLine = findViewById(R.id.statusLine)
        headline = findViewById(R.id.headline)
        subline = findViewById(R.id.subline)
        offlineNote = findViewById(R.id.offlineNote)
        pause = findViewById(R.id.pause)
        statNew = findViewById(R.id.statNew)
        statReview = findViewById(R.id.statReview)
        statGulf = findViewById(R.id.statGulf)
        sourceRows = findViewById(R.id.sourceRows)
        gulfCard = findViewById(R.id.gulfCard)
        gulfRows = findViewById(R.id.gulfRows)
        serperCard = findViewById(R.id.serperCard)
        serperToday = findViewById(R.id.serperToday)
        serperBar = findViewById(R.id.serperBar)
        serperNote = findViewById(R.id.serperNote)
        findViewById<LinearLayout>(R.id.egyptHead).addView(Ui.tag(this, "Egypt", R.color.softText, R.color.soft), 0)
        findViewById<LinearLayout>(R.id.gulfHead).addView(Ui.tag(this, "Saudi & UAE", R.color.gulf, R.color.gulfSoft), 0)
        footer = findViewById(R.id.footer)

        // A schedule from an older build still carries its network constraint.
        io {
            runCatching { Scheduler.upgrade(this) }
            catchUpAfterForceStop()
        }

        findViewById<Button>(R.id.runNow).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_refresh, R.color.heroOnAccent), null, null, null)
            setOnClickListener {
                Scheduler.runNow(this@MainActivity)
                Net.init(this@MainActivity)
                toast(if (Net.isOnline()) "Run queued" else "Run queued. It starts once the phone is online.")
                refresh()
            }
        }
        pause.setOnClickListener {
            if (scheduled) {
                Scheduler.stop(this)
                toast("Paused: no runs until you start it again")
            } else {
                val interval = Settings.load(this).intervalMinutes
                Scheduler.start(this, interval)
                toast("Started: every ${SettingsActivity.INTERVALS.firstOrNull { it.first == interval }?.second ?: "$interval minutes"}")
            }
            refresh()
        }
        findViewById<View>(R.id.bannerAction).setOnClickListener { bannerAction() }
        findViewById<View>(R.id.statNewTile).setOnClickListener { Tabs.open(this, Tab.JOBS) }
        findViewById<View>(R.id.statReviewTile).setOnClickListener { Tabs.open(this, Tab.EMAILS) }
        findViewById<View>(R.id.statGulfTile).setOnClickListener {
            Tabs.open(this, Tab.EMAILS, intent = ReviewActivity.intent(this, auto = true))
        }
        findViewById<View>(R.id.footer).setOnClickListener { startActivity(ChangesActivity.intent(this)) }
    }

    /** Home's floating button: paste or share a LinkedIn post. */
    private fun addPostButton() = Button(this, null, 0, R.style.ButtonPrimary).apply {
        text = "Add a post"
        background = getDrawable(R.drawable.fab)
        minHeight = Ui.dp(this@MainActivity, 52)
        elevation = Ui.dp(this@MainActivity, 6).toFloat()
        setPadding(Ui.dp(this@MainActivity, 16), 0, Ui.dp(this@MainActivity, 20), 0)
        compoundDrawablePadding = Ui.dp(this@MainActivity, 8)
        setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_plus, R.color.onPrimary), null, null, null)
        setOnClickListener { startActivity(AddPostActivity.intent(this@MainActivity)) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, "Settings")
            .setIcon(tinted(R.drawable.ic_settings, R.color.text))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SETTINGS) {
            startActivity(SettingsActivity.intent(this))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /* While the screen is open the status follows a run as it happens. */
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ticker.postDelayed(this, 5_000)
        }
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    /** What the banner's button does: the banner shown last decides. */
    private var bannerAction: () -> Unit = { startActivity(SettingsActivity.intent(this)) }

    /**
     * Opening the app is the first moment anything can run after a force
     * stop. WorkManager puts the schedule back by itself; this also starts the
     * run that was missed, rather than waiting out the next interval.
     */
    private fun catchUpAfterForceStop() {
        runCatching { ExitWatch.check(this) }.getOrNull() ?: return
        if (!runCatching { Scheduler.isScheduled(this) }.getOrDefault(false)) return
        Scheduler.runNow(this)
        Log.info("Opened after a force stop: the schedule is back on, and a run is starting")
        onUi { refresh() }
    }

    private fun refresh() {
        io {
            val snapshot = runCatching { HomeState.load(this, Store.shared(this)) }.getOrNull() ?: return@io
            onUi { show(snapshot) }
        }
    }

    private fun show(s: HomeState) {
        scheduled = s.scheduled
        val now = Instant.now()
        val intervalName = SettingsActivity.INTERVALS.firstOrNull { it.first == s.settings.intervalMinutes }?.second
            ?: "${s.settings.intervalMinutes} min"

        // Banner: the most serious thing the person has to fix.
        bannerAction = { startActivity(SettingsActivity.intent(this)) }
        when {
            !s.settings.telegramReady -> showBanner(R.color.badSoft, R.drawable.ic_warning, R.color.bad,
                "No Telegram bot in this build.", "Matches are kept on the phone but not posted.", "See connections")
            s.update.updateReady -> {
                showBanner(R.color.soft, R.drawable.ic_refresh, R.color.softText,
                    "Update ${s.update.latest?.versionName} is ready.", "Tap to install it (this is ${BuildConfig.VERSION_NAME}).",
                    "Update now")
                bannerAction = { startActivity(SettingsActivity.intent(this).putExtra(SettingsActivity.EXTRA_UPDATE, true)) }
            }
            !s.backupOn -> showBanner(R.color.warnSoft, R.drawable.ic_warning, R.color.warn,
                "Backup is off.", "Uninstalling the app would forget which jobs were already sent.", "Set up backup")
            else -> banner.visibility = View.GONE
        }

        val (dotColor, line) = when {
            s.running -> R.color.heroAccent to "Searching now…"
            s.offline -> R.color.heroWarn to "Waiting for network"
            s.scheduled -> R.color.heroAccent to "Running · every $intervalName"
            else -> R.color.heroWarn to "Paused"
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(dotColor))
        statusLine.setTextColor(getColor(dotColor))
        statusLine.text = line

        val report = s.report
        // A run handed over by an outage: Home says so until the resume starts.
        val waitingOnline = s.offline && !s.running
        offlineNote.visibility = if (waitingOnline) View.VISIBLE else View.GONE
        headline.text = when {
            waitingOnline -> "Paused while offline"
            report == null -> "No run yet"
            else -> "Last run ${ago(report.ended, now)}"
        }
        subline.text = if (waitingOnline) offlineLine(report) else buildString {
            if (report != null) append("${clock(report.started)} – ${clock(report.ended)}")
            if (s.scheduled && report != null) {
                val next = report.ended.plus(Duration.ofMinutes(s.settings.intervalMinutes))
                val minutes = Duration.between(now, next).toMinutes()
                append(if (minutes > 0) " · next in about ${minutes} min" else " · next run due")
            } else if (!s.scheduled) {
                if (isNotEmpty()) append(" · ")
                append("Start it to search automatically")
            }
        }

        pause.text = if (s.scheduled) "Pause" else "Start"
        pause.setCompoundDrawablesRelativeWithIntrinsicBounds(
            tinted(if (s.scheduled) R.drawable.ic_pause else R.drawable.ic_play, R.color.heroText), null, null, null,
        )

        statNew.text = s.fresh.toString()
        statReview.text = s.waiting.toString()
        statGulf.text = s.gulfSentToday.toString()

        showSources(s, now)
        showDropped(s)
        showSerper(s.serper)

        footer.text = SpannableString(
            "Applied: ${s.applied["link"] ?: 0} tapped, ${s.applied["email"] ?: 0} CVs emailed · ${s.stored} stored\n" +
                "Job Crawler ${BuildConfig.VERSION_NAME} · What's new" + if (s.updates > 0) " (${s.updates})" else "",
        ).apply {
            val link = indexOf("Job Crawler")
            setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.softText)), link, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), link, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Today's paid searches against the cap; hidden while no hiring-posts source is on. */
    /* Under the sources: the week's top reasons jobs were dropped, and the full breakdown a tap away. */
    private fun showDropped(s: HomeState) {
        if (s.dropped.isEmpty()) return
        sourceRows.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 1)).apply {
                topMargin = Ui.dp(this@MainActivity, 10)
                bottomMargin = Ui.dp(this@MainActivity, 8)
            })
        sourceRows.addView(Ui.row(this).apply {
            addView(Ui.title(this@MainActivity, "Dropped this week", 14f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.filterChip(this@MainActivity, "See why") { startActivity(RejectionsActivity.intent(this@MainActivity)) })
        })
        for ((reason, count) in s.dropped.take(3)) {
            sourceRows.addView(Ui.row(this).apply {
                setPadding(0, Ui.dp(this@MainActivity, 3), 0, Ui.dp(this@MainActivity, 3))
                addView(Ui.muted(this@MainActivity, Rejections.describe(reason), 13f),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.mono(this@MainActivity, count.toString(), 12f, R.color.textMuted))
            })
        }
    }

    private fun showSerper(usage: SerperBudget.Usage?) {
        // Hidden while no source is on the Serper lane (OFF 2026-11-05).
        val shown = usage != null && HomeState.PAID_SOURCES.isNotEmpty()
        serperCard.visibility = if (shown) View.VISIBLE else View.GONE
        if (!shown || usage == null) return
        val cap = usage.dailyCap
        serperToday.text = if (cap > 0) "${usage.today} / $cap today" else "${usage.today} today"
        serperBar.progress = if (cap > 0) (usage.today * 100 / cap).coerceIn(0, 100) else 0
        serperNote.text = listOfNotNull(
            usage.balance?.let { "$it credits left" },
            "this month ${usage.month}",
            if (SerperPrefs.grouped(this)) "grouped places" else "one search per place",
        ).joinToString(" · ")
    }

    /** Where the outage cut the last run short, if it did; a run that started offline never began. */
    private fun offlineLine(report: RunReport?): String {
        val waiting = "Picks up as soon as the phone is online"
        val cut = report?.takeIf { it.notReached.isNotEmpty() } ?: return waiting
        val last = cut.sources.lastOrNull() ?: return waiting
        val name = SettingsActivity.SOURCE_LABELS[last.key]?.first ?: last.key
        return "Stopped after $name at ${clock(cut.ended)} · picks up as soon as the phone is online"
    }

    /** [lead] in medium weight, then the rest, and [action] (to Settings) under it. */
    private fun showBanner(fill: Int, icon: Int, tint: Int, lead: String, text: String, action: String) {
        banner.visibility = View.VISIBLE
        banner.backgroundTintList = ColorStateList.valueOf(getColor(fill))
        bannerIcon.setImageResource(icon)
        bannerIcon.imageTintList = ColorStateList.valueOf(getColor(tint))
        bannerText.text = SpannableString("$lead $text").apply {
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, lead.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        findViewById<Button>(R.id.bannerAction).apply {
            this.text = action
            setTextColor(getColor(tint))
        }
    }

    /* One row per switched-on source: what the last run found, or when it runs next. */
    private fun showSources(s: HomeState, now: Instant) {
        sourceRows.removeAllViews()
        gulfRows.removeAllViews()
        gulfCard.visibility = if (s.settings.sources.any { it in Mailer.AUTO_SOURCES }) View.VISIBLE else View.GONE
        val ran = s.report?.sources?.associateBy { it.key } ?: emptyMap()
        val notReached = s.report?.notReached.orEmpty().toSet()

        for ((key, label) in SettingsActivity.SOURCE_LABELS) {
            if (key !in s.settings.sources) continue
            val result = ran[key]
            val manual = s.sourceRuns[key]
            val (dot, note, noteColor, count) = when {
                manual == WorkInfo.State.RUNNING -> Row(R.color.primary, "Running now, by hand…", R.color.softText, "…")
                manual != null -> Row(
                    R.color.primary,
                    if (s.running) "Queued · runs after the current run" else if (s.online) "Queued · starting" else "Queued · runs once online",
                    R.color.softText,
                    "…",
                )
                result == null && key in notReached -> Row(
                    R.color.warn,
                    if (s.offline) "Not reached · resumes once online" else "Not reached: the network went away",
                    R.color.warn,
                    "—",
                )
                result == null -> Row(R.color.divider, notDue(key, s, now), R.color.textMuted, "—")
                result.blocked -> Row(R.color.warn, "Stopped: ${short(result.error)}", R.color.warn, "${result.parsed} · ${result.stored}")
                result.error != null -> Row(R.color.bad, "Error: ${short(result.error)}", R.color.bad, "— · —")
                result.parsed == 0 -> Row(R.color.good, "Nothing new to read", R.color.textMuted, "0 · 0")
                else -> Row(
                    R.color.good,
                    "${result.parsed} found, ${result.matched} ${if (result.matched == 1) "match" else "matches"}",
                    R.color.textMuted,
                    "${result.parsed} · ${result.stored}",
                )
            }.let { row ->
                // A result from a run by hand says when, since it is newer than the run above.
                val at = result?.ranAt
                if (manual == null && at != null) row.copy(note = "${row.note} · by hand ${ago(at, now)}") else row
            }

            // Saudi & UAE sources have their own card: they email, they never post.
            val rows = if (key in Mailer.AUTO_SOURCES) gulfRows else sourceRows
            rows.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)))
            rows.addView(Ui.row(this).apply {
                minimumHeight = Ui.dp(this@MainActivity, 56)
                addView(Ui.dot(this@MainActivity, dot))
                addView(Ui.column(this@MainActivity).apply {
                    addView(Ui.row(this@MainActivity).apply {
                        addView(Ui.title(this@MainActivity, label.first, 15f))
                        if (key in HomeState.PAID_SOURCES) {
                            addView(Ui.tag(this@MainActivity, "Paid", R.color.warn, null),
                                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                    marginStart = Ui.dp(this@MainActivity, 6)
                                })
                        }
                    })
                    addView(Ui.muted(this@MainActivity, note, 12f).apply { setTextColor(getColor(noteColor)) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Ui.dp(this@MainActivity, 12)
                })
                addView(Ui.mono(this@MainActivity, count, 13f))
                addView(runButton(key, label.first, queued = manual != null))
            })
        }
    }

    /** Runs one source once, now, due or not: for a turn missed while the phone was offline. */
    private fun runButton(key: String, name: String, queued: Boolean) = ImageView(this).apply {
        setImageResource(R.drawable.ic_play)
        imageTintList = ColorStateList.valueOf(getColor(if (queued) R.color.textMuted else R.color.primary))
        val pad = Ui.dp(this@MainActivity, 11)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 42), Ui.dp(this@MainActivity, 42)).apply {
            marginStart = Ui.dp(this@MainActivity, 4)
        }
        contentDescription = "Run $name once now"
        isEnabled = !queued
        alpha = if (queued) 0.5f else 1f
        background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let { attrs ->
            attrs.getDrawable(0).also { attrs.recycle() }
        }
        setOnClickListener {
            // The posts searches are billed per query: ask first.
            if (key in HomeState.PAID_SOURCES) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Run $name now?")
                    .setMessage("This is the paid search. It runs today's searches again, within the daily cap.")
                    .setPositiveButton("Run") { _, _ -> runSource(key, name) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else if (key == LinkedInJobs.KEY) {
                askHours(key, name)
            } else {
                runSource(key, name)
            }
        }
    }

    /**
     * LinkedIn Jobs by hand: how far back to search. A quick choice runs at
     * once; a typed number runs on Run; Auto keeps the usual window, the time
     * since its last clean run.
     */
    private fun askHours(key: String, name: String) {
        val field = EditText(this, null, 0, R.style.Field).apply {
            hint = "Hours, 1–${LinkedInJobs.MAX_HOURS}"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val quick = FlowLayout(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 20), 0)
            addView(quick, Ui.matchWidth())
            addView(Ui.muted(this@MainActivity, "Or type any number of hours:", 13f).apply {
                setPadding(0, Ui.dp(this@MainActivity, 14), 0, Ui.dp(this@MainActivity, 4))
            })
            addView(field, Ui.matchWidth())
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Run $name: how far back?")
            .setMessage("Jobs posted in the last…")
            .setView(box)
            .setPositiveButton("Run", null)
            .setNeutralButton("Auto") { _, _ -> runSource(key, name) }
            .setNegativeButton("Cancel", null)
            .create()

        for (hours in QUICK_HOURS) {
            quick.addView(Ui.chip(this, hoursLabel(hours), R.color.primary).apply {
                textSize = 14f
                setPadding(Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 8))
                contentDescription = "Last ${hoursLabel(hours)}"
                setOnClickListener {
                    dialog.dismiss()
                    runSource(key, name, hours)
                }
            })
        }

        dialog.show()
        // Set after show, so a bad number keeps the dialog open instead of closing it.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val hours = field.text.toString().trim().toIntOrNull()
            if (hours == null || hours !in 1..LinkedInJobs.MAX_HOURS) {
                toast("Hours must be a whole number from 1 to ${LinkedInJobs.MAX_HOURS}")
                return@setOnClickListener
            }
            dialog.dismiss()
            runSource(key, name, hours)
        }
    }

    private fun runSource(key: String, name: String, hours: Int? = null) {
        Scheduler.runSource(this, key, hours)
        Net.init(this)
        val what = if (hours != null) "$name (last ${hoursLabel(hours)})" else name
        toast(if (Net.isOnline()) "$what queued: runs once, now" else "$what queued. It runs once the phone is online.")
        refresh()
    }

    private data class Row(val dot: Int, val note: String, val noteColor: Int, val count: String)

    /** For a source the last run skipped: when its turn comes. */
    private fun notDue(key: String, s: HomeState, now: Instant): String = when {
        s.linkedInRest != null && key == LinkedInJobs.KEY ->
            "LinkedIn rests until ${LinkedInRest.clock(s.linkedInRest)}" +
                (s.linkedInRestReason?.let { " after $it" } ?: " after a throttle")
        key == LinkedInJobs.KEY -> "Every 15 min · runs next time it is due"
        else -> notDueByCadence(key, s, now)
    }

    private fun notDueByCadence(key: String, s: HomeState, now: Instant): String = when (key) {
        LinkedInPosts.KEY, LinkedInPosts.GULF_KEY, Glassdoor.KEY -> "Daily after 07:00"
        Jobright.KEY -> "Hourly · runs next time it is due"
        RelocateMe.KEY -> "Every 3 h · runs next time it is due"
        com.jobcrawler.app.sources.HnHiring.KEY -> "Daily · runs next time it is due"
        com.jobcrawler.app.sources.JobApis.KEY -> if (com.jobcrawler.app.sources.JobApis.Keys.fromBuild().any) "Twice a day · runs next time it is due" else "Needs a free key in .env (Adzuna, Jooble or Careerjet)"
        Ats.KEY -> {
            val last = s.slowLastRun[key]
            if (last == null) "Twice a day · runs next time" else {
                val next = Instant.ofEpochMilli(last + Ats.EVERY_MS)
                if (next.isAfter(now)) "Twice a day · next at ${clock(next)}" else "Twice a day · runs next time"
            }
        }
        com.jobcrawler.app.sources.Recruitera.KEY -> {
            val last = s.slowLastRun[key]
            if (last == null) "Every 6 h · runs next time" else {
                val next = Instant.ofEpochMilli(last + com.jobcrawler.app.sources.Recruitera.EVERY_MS)
                if (next.isAfter(now)) "Every 6 h · next at ${clock(next)}" else "Every 6 h · runs next time"
            }
        }
        in HomeState.SLOW_SOURCES -> {
            val last = s.slowLastRun[key]
            if (last == null) "Every 3 h · runs next time" else {
                val next = Instant.ofEpochMilli(last + Limits.SLOW_SOURCE_EVERY_MS)
                if (next.isAfter(now)) "Every 3 h · next at ${clock(next)}" else "Every 3 h · runs next time"
            }
        }
        else -> "Not in the last run"
    }

    private fun short(message: String?): String =
        (message ?: "unknown").replace(Regex("\\s+"), " ").let { if (it.length > 60) it.take(57) + "…" else it }

    private fun clock(at: Instant): String = CLOCK.format(at.atZone(ZoneId.systemDefault()))

    private fun ago(at: Instant, now: Instant): String {
        val minutes = Duration.between(at, now).toMinutes().coerceAtLeast(0)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    private fun tinted(res: Int, color: Int) = getDrawable(res)!!.mutate().apply {
        setTint(getColor(color))
        val size = Ui.dp(this@MainActivity, 18)
        setBounds(0, 0, size, size)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val MENU_SETTINGS = 1
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** The quick choices for a LinkedIn Jobs run by hand, in hours; any other can be typed. */
        private val QUICK_HOURS = listOf(1, 6, 10, 24, 48, 168)

        private fun hoursLabel(hours: Int) = if (hours == 168) "1 week" else "${hours}h"
    }
}
