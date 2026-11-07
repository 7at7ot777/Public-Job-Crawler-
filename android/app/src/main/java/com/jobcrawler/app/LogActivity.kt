package com.jobcrawler.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalTime

/**
 * The scraping log, newest first, one card per run: a run's header says when
 * it started, how many sources it walked and how long it took, and its
 * warnings and errors stand out. Copy puts all of it on the clipboard.
 */
class LogActivity : Screen() {

    private lateinit var runs: LinearLayout
    private lateinit var all: Button
    private lateinit var problems: Button
    private var raw: String? = null
    private var problemsOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(this)
        title = "Log"

        all = Ui.filterChip(this, "All") { pick(false) }
        problems = Ui.filterChip(this, "Problems only") { pick(true) }
        all.isSelected = true
        val copy = Ui.primary(this, "Copy") { copy() }.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                getDrawable(R.drawable.ic_copy)!!.mutate().apply { setTint(getColor(R.color.onPrimary)) }, null, null, null,
            )
            compoundDrawablePadding = dp(6)
            minHeight = dp(36)
            background = getDrawable(R.drawable.filter_chip)
            isSelected = true
        }

        val send = Ui.primary(this, "Send") { send() }.apply {
            minHeight = dp(36)
            background = getDrawable(R.drawable.filter_chip)
            isSelected = true
        }

        val bar = Ui.row(this).apply {
            setBackgroundColor(getColor(R.color.bar))
            setPadding(dp(16), dp(4), dp(16), dp(12))
            addView(all, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
            addView(problems, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(8) })
            addView(android.view.View(this@LogActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { topMargin = 0 })
            addView(copy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                topMargin = 0
                marginStart = dp(8)
            })
        }

        runs = Ui.column(this).apply { setPadding(0, dp(6), 0, dp(16)) }
        setContentView(Tabs.wrap(this, LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(ScrollView(this@LogActivity).apply { addView(runs) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }, Tab.LOG))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE, "Refresh")
            .setIcon(getDrawable(R.drawable.ic_refresh)!!.mutate().apply { setTint(getColor(R.color.text)) })
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(Menu.NONE, MENU_CLEAR, Menu.NONE, "Clear the log")
            .setIcon(getDrawable(R.drawable.ic_trash)!!.mutate().apply { setTint(getColor(R.color.text)) })
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(Menu.NONE, MENU_SNAPSHOTS, Menu.NONE, "Snapshots")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_REFRESH -> {
            raw = null
            load()
            true
        }
        MENU_CLEAR -> {
            confirmClear()
            true
        }
        MENU_SNAPSHOTS -> {
            snapshots()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /* While the screen is open the log follows a run as it happens. */
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            load()
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

    private fun pick(onlyProblems: Boolean) {
        problemsOnly = onlyProblems
        all.isSelected = !onlyProblems
        problems.isSelected = onlyProblems
        raw?.let { render(it) }
    }

    private fun load() {
        io {
            // The file is capped at a few hundred lines, so all of it fits on screen.
            val text = Log.tail(Int.MAX_VALUE)
            onUi {
                // Unchanged text is left alone, so the scroll and a selection survive the ticker.
                if (text == raw) return@onUi
                raw = text
                render(text)
            }
        }
    }

    private class Line(val time: String, val level: String, val text: String)
    private class Run(val title: String, val meta: String, val lines: List<Line>)

    /** Newest first, a run's lines sit above its "Run started" line: that line closes the group. */
    private fun group(text: String): List<Run> {
        val result = mutableListOf<Run>()
        var current = mutableListOf<Line>()
        val pattern = Regex("^\\d\\d-\\d\\d (\\d\\d:\\d\\d:\\d\\d) ([IWE]) (.*)$")

        for (entry in text.lines()) {
            val match = pattern.find(entry) ?: continue
            val (time, level, message) = match.destructured
            if (message == "Run started") {
                result += close(current, time)
                current = mutableListOf()
            } else {
                current += Line(time, level, message)
            }
        }
        if (current.isNotEmpty()) result += Run("Earlier", "${current.size} lines", current)
        return result
    }

    private fun close(lines: List<Line>, start: String): Run {
        val sources = lines.count { it.text.startsWith("── ") }
        val end = lines.firstOrNull()?.time
        val took = if (end == null) null else runCatching {
            val seconds = (LocalTime.parse(end).toSecondOfDay() - LocalTime.parse(start).toSecondOfDay()).let { if (it < 0) it + 86_400 else it }
            if (seconds >= 60) "${seconds / 60} min ${seconds % 60} s" else "$seconds s"
        }.getOrNull()
        return Run(
            "Run ${start.take(5)}",
            listOfNotNull(if (sources > 0) "$sources ${if (sources == 1) "source" else "sources"}" else null, took).joinToString(" · "),
            lines,
        )
    }

    private fun render(text: String) {
        runs.removeAllViews()
        val grouped = group(text)
            .map { run -> Run(run.title, run.meta, if (problemsOnly) run.lines.filter { it.level != "I" } else run.lines) }
            .filter { it.lines.isNotEmpty() }

        if (grouped.isEmpty()) {
            runs.addView(Ui.muted(this, if (problemsOnly) "No warnings or errors in the log." else "No log yet.", 14f).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(dp(24), dp(48), dp(24), dp(24))
            })
            return
        }

        for (run in grouped) {
            val worst = when {
                run.lines.any { it.level == "E" } -> R.color.heroBad
                run.lines.any { it.level == "W" } -> R.color.heroWarn
                else -> R.color.heroAccent
            }
            val header = Ui.row(this).apply {
                setPadding(0, 0, 0, dp(8))
                addView(Ui.dot(this@LogActivity, worst))
                addView(Ui.title(this@LogActivity, run.title, 14f).apply { setTextColor(getColor(R.color.heroText)) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
                addView(Ui.mono(this@LogActivity, run.meta, 11f, R.color.heroMuted))
            }
            val body = TextView(this).apply {
                typeface = Ui.font(this@LogActivity, R.font.plex_mono)
                textSize = 11.5f
                setLineSpacing(0f, 1.25f)
                setTextColor(getColor(R.color.heroText))
                setTextIsSelectable(true)
                this.text = spans(run.lines)
            }
            // Each run is a console block, dark in both modes.
            val block = Ui.column(this).apply {
                addView(header)
                addView(body)
            }
            runs.addView(Ui.card(this, block, horizontal = 12, vertical = 5))
            block.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(getColor(R.color.hero))
            }
        }
    }

    /** One line per entry: the time muted, the level marked, warnings and errors in their colours. */
    private fun spans(lines: List<Line>): CharSequence = SpannableStringBuilder().apply {
        for ((index, line) in lines.withIndex()) {
            if (index > 0) append("\n")
            var start = length
            append(line.time).append(" ")
            setSpan(ForegroundColorSpan(getColor(R.color.heroMuted)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            start = length
            append(" ${line.level} ")
            val ink = when (line.level) {
                "E" -> R.color.heroBad
                "W" -> R.color.heroWarn
                else -> R.color.heroMuted
            }
            setSpan(ForegroundColorSpan(getColor(ink)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            append(" ")
            start = length
            append(line.text)
            if (line.level != "I") {
                setSpan(ForegroundColorSpan(getColor(ink)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun copy() {
        val text = raw.orEmpty()
        if (text.isEmpty()) return toast("The log is empty")
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Crawler log", text))
        toast("Log copied. Paste it anywhere.")
    }

    /** The log as shown (all, or problems only) to Telegram as chat messages, from the jobs bot. */
    private fun send() {
        val settings = Settings.load(this)
        if (!settings.telegramReady) return toast("Telegram is not set up in this build")
        val text = raw.orEmpty().let { if (problemsOnly) LogExport.problems(it) else it }
        if (text.isBlank()) return toast("Nothing to send")
        toast("Sending the log to Telegram…")
        io {
            val error = runCatching {
                val telegram = Telegram(settings.botToken, Telegram.logChat())
                for ((index, message) in LogExport.messages(BuildConfig.VERSION_NAME, text, problemsOnly).withIndex()) {
                    // Telegram allows about one message a second to a chat; a 429 says how long to wait.
                    if (index > 0) Thread.sleep(1_100)
                    try {
                        telegram.send(message)
                    } catch (e: TelegramRateLimit) {
                        Thread.sleep(e.retryAfterSeconds * 1_000 + 500)
                        telegram.send(message)
                    }
                }
            }.exceptionOrNull()
            onUi { toast(if (error == null) "Log sent to Telegram" else "Could not send: ${error.message}") }
        }
    }

    /*
     * Pages a source parsed nothing from (Snapshots): one tap sends the file
     * to Telegram, to turn into a parser test.
     */
    private fun snapshots() {
        val files = Snapshots.list(java.io.File(filesDir, Snapshots.DIR))
        if (files.isEmpty()) return toast("No snapshots: every source parsed something from what it got")
        val labels = files.map { "${it.name} · ${it.length() / 1024} KB · ${JobsActivity.ago(java.time.Instant.ofEpochMilli(it.lastModified()).toString()) ?: ""}" }
        AlertDialog.Builder(this)
            .setTitle("Send a snapshot to Telegram")
            .setItems(labels.toTypedArray()) { _, which -> sendSnapshot(files[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendSnapshot(file: java.io.File) {
        val settings = Settings.load(this)
        if (!settings.telegramReady) return toast("Telegram is not set up in this build")
        io {
            val error = runCatching {
                val bytes = file.readBytes()
                Telegram(settings.botToken, Telegram.logChat()).sendDocument(file.name, bytes, LogExport.snapshotCaption(file.name, bytes.size))
            }.exceptionOrNull()
            onUi { toast(if (error == null) "Snapshot sent to Telegram" else "Could not send: ${error.message}") }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Clear the log?")
            .setPositiveButton("Clear") { _, _ ->
                io {
                    Log.clear()
                    onUi {
                        raw = null
                        load()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MENU_REFRESH = 1
        private const val MENU_CLEAR = 2
        private const val MENU_SNAPSHOTS = 3

        fun intent(context: Context): Intent = Intent(context, LogActivity::class.java)
    }
}
