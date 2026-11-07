package com.jobcrawler.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * One job, with the description the source gave, so applying or sending a CV
 * is decided having read it.
 */
class JobActivity : Screen() {

    private lateinit var body: LinearLayout
    private var url: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(this)
        title = "Job"
        showBack()

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        setContentView(ScrollView(this).apply { addView(body) })

        val id = intent.getLongExtra(EXTRA_ROW, -1)
        io {
            val loaded = runCatching {
                Store.shared(this).let { store -> store.findById(id)?.let { it to store.applications.emailOutcome(it.item) } }
            }.getOrNull()
            val address = loaded?.let { Mailer.addressFor(it.first.item, ApplySettings.load(this)) }
            onUi {
                if (loaded == null) {
                    toast("That job is no longer stored")
                    finish()
                } else {
                    show(loaded.first, address, loaded.second)
                }
            }
        }
    }

    /*
     * Is this a fit? A verdict the score learns from (two 👎 on a company or
     * a title word lower its later jobs), and the mutes, which drop them.
     */
    private fun fitCard(row: Row): View {
        var current = row.copy()
        val up = Ui.soft(this, "") {}
        val down = Ui.soft(this, "") {}
        fun paint() {
            up.text = if (current.feedback == Feedback.UP) "✓ 👍 Good fit" else "👍 Good fit"
            down.text = if (current.feedback == Feedback.DOWN) "✓ 👎 Not relevant" else "👎 Not relevant"
        }
        fun choose(value: String) = FeedbackMenu.verdict(this, current, value) { change ->
            if (change is FeedbackMenu.Change.Verdict) current = current.copy(feedback = change.value)
            paint()
        }
        up.setOnClickListener { choose(Feedback.UP) }
        down.setOnClickListener { choose(Feedback.DOWN) }
        paint()
        val mute = Ui.outline(this, "🔕 Mute company or words") {
            FeedbackMenu.show(this, current) { change -> if (change is FeedbackMenu.Change.Verdict) {
                current = current.copy(feedback = change.value)
                paint()
            } }
        }
        return Ui.column(this).apply {
            addView(Ui.title(this@JobActivity, "Is this a fit?"))
            addView(Ui.buttonRow(this@JobActivity, up, down), Ui.matchWidth().apply { topMargin = dp(8) })
            addView(Ui.buttonRow(this@JobActivity, mute), Ui.matchWidth().apply { topMargin = dp(8) })
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_OPEN, Menu.NONE, "Open posting")
            .setIcon(getDrawable(R.drawable.ic_open)!!.mutate().apply { setTint(getColor(R.color.text)) })
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_OPEN) {
            url?.let(::open)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** @param outcome the job's email so far, as (channel, address); null while none was sent or ignored. */
    private fun show(row: Row, address: String?, outcome: Pair<String, String?>?) {
        val item = row.item
        url = item.url

        // What the job is.
        val gulf = item.source in Mailer.AUTO_SOURCES
        val about = Ui.column(this).apply {
            addView(Ui.row(this@JobActivity).apply {
                addView(Ui.mono(this@JobActivity, JobsActivity.sourceName(item), 12f, R.color.textMuted))
                if (gulf) {
                    addView(Ui.tag(this@JobActivity, "Saudi & UAE", R.color.gulf, R.color.gulfSoft),
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dp(8)
                        })
                }
            })
            addView(Ui.title(this@JobActivity, item.title, 24f).apply {
                typeface = Ui.font(this@JobActivity, R.font.manrope_extrabold)
                setPadding(0, dp(8), 0, 0)
                setTextIsSelectable(true)
            })
            listOfNotNull(item.company, item.location).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                addView(Ui.body(this@JobActivity, it).apply { setPadding(0, dp(4), 0, 0) })
            }
            addView(View(this@JobActivity).apply { setBackgroundColor(getColor(R.color.divider)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(4)
                })

            listOfNotNull(item.workplace, item.employmentType, item.seniority).joinToString(" · ").ifEmpty { null }?.let {
                addView(Ui.fact(this@JobActivity, R.drawable.ic_pin, it))
            }
            item.postedAt?.let { addView(Ui.fact(this@JobActivity, R.drawable.ic_clock, "Posted $it")) }
            item.score?.let { addView(Ui.fact(this@JobActivity, R.drawable.ic_check, "⭐ Fit $it/100")) }
            Extract.Years.of(item)?.let { addView(Ui.fact(this@JobActivity, R.drawable.ic_briefcase, "🧭 Asks for $it")) }
            item.salary?.let { addView(Ui.fact(this@JobActivity, R.drawable.ic_info, "💰 $it", R.color.good)) }
            addView(Ui.fact(this@JobActivity, R.drawable.ic_pin, "${row.region.flag} ${row.region.label}"))
            JobsActivity.ago(row.createdAt)?.let { addView(Ui.fact(this@JobActivity, R.drawable.ic_clock, "Found $it")) }
            if (gulf) {
                addView(Ui.fact(this@JobActivity, R.drawable.ic_send, "Saudi & UAE: emailed, never posted to Telegram", R.color.gulf))
            } else {
                addView(Ui.fact(this@JobActivity, R.drawable.ic_send, JobsActivity.telegramStatus(row.status), JobsActivity.statusColor(row.status)
                    .let { if (it == R.color.textMuted) R.color.text else it }))
            }
            val (mail, mailColor) = when (outcome?.first) {
                "email" -> (if (gulf) "CV sent on its own to ${outcome?.second}" else "CV sent to ${outcome?.second}") to
                    (if (gulf) R.color.gulf else R.color.good)
                "email-ignored" -> "Email: ignored" to R.color.textMuted
                "email-failed" -> "Email refused by ${outcome?.second}" to R.color.bad
                "email-skipped" -> "Email: no address a person reads" to R.color.textMuted
                else -> (address ?: "No email address") to R.color.text
            }
            addView(Ui.fact(this@JobActivity, R.drawable.ic_mail, mail, mailColor))
        }
        body.addView(Ui.card(this, about, horizontal = 0))
        body.addView(Ui.card(this, fitCard(row), horizontal = 0))

        // What can be done with it.
        val note = CoverNote.text(item, ApplySettings.load(this), withCv = false)
        val preview = Ui.body(this, note, 14f).apply {
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
        }
        val toggle = Ui.title(this, "Preview the cover note", 14f).apply {
            setTextColor(getColor(R.color.softText))
            minHeight = dp(40)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val noteBox = Ui.column(this).apply {
            background = getDrawable(R.drawable.inset)
            setPadding(dp(14), dp(4), dp(14), dp(12))
            isClickable = true
            setOnClickListener {
                val open = preview.visibility != View.VISIBLE
                preview.visibility = if (open) View.VISIBLE else View.GONE
                toggle.text = if (open) "Hide the cover note" else "Preview the cover note"
            }
            addView(toggle)
            addView(preview)
        }

        val actions = Ui.column(this).apply {
            addView(Ui.title(this@JobActivity, "Apply"))
            addView(Ui.primary(this@JobActivity, "Copy cover note and open job") {
                startActivity(ApplyActivity.intent(this@JobActivity, row.id))
            }.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    getDrawable(R.drawable.ic_copy)!!.mutate().apply { setTint(getColor(R.color.onPrimary)) }, null, null, null,
                )
                compoundDrawablePadding = dp(8)
            }, Ui.wide(this@JobActivity))
            addView(noteBox, Ui.wide(this@JobActivity))
        }
        body.addView(Ui.card(this, actions, horizontal = 0))

        // Once sent or ignored, a job is settled: no second email from here.
        if (address != null && outcome == null) {
            val send = Ui.primary(this, "Send CV") {}
            val ignore = Ui.outline(this, "Ignore") {}
            actions.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(16)
                    bottomMargin = dp(12)
                })
            actions.addView(Ui.title(this, "Email your CV", 15f))
            actions.addView(Ui.muted(this, "To $address, with the same note"))
            actions.addView(Ui.buttonRow(this, send, ignore))
            send.setOnClickListener {
                send.isEnabled = false
                ignore.isEnabled = false
                send.text = "Sending…"
                io {
                    val result = runCatching { Store.shared(this).let { Mailer.send(this, it, row) } }
                        .getOrElse { Mailer.Result.Failed("${it.javaClass.simpleName}: ${it.message}", permanent = false) }
                    onUi {
                        toast(Mailer.describe(result))
                        if (Mailer.settles(result)) {
                            finish()
                        } else {
                            send.isEnabled = true
                            ignore.isEnabled = true
                            send.text = "Send CV"
                        }
                    }
                }
            }
            ignore.setOnClickListener {
                io { runCatching { Store.shared(this).let { Mailer.ignore(it, row) } } }
                toast("Ignored. It will not be offered again.")
                finish()
            }
        }

        // What the source said about it.
        val description = Ui.column(this).apply {
            addView(Ui.title(this@JobActivity, "Description"))
            addView(Ui.body(this@JobActivity, item.excerpt?.trim()?.ifEmpty { null }
                ?: "The source gave no description. Open the posting to read it.").apply {
                setPadding(0, dp(8), 0, 0)
                setTextIsSelectable(true)
                setLineSpacing(0f, 1.3f)
            })
        }
        body.addView(Ui.card(this, description, horizontal = 0))
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            toast("Nothing on the phone opens $url")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_ROW = "row"
        private const val MENU_OPEN = 1

        fun intent(context: Context, rowId: Long): Intent =
            Intent(context, JobActivity::class.java).putExtra(EXTRA_ROW, rowId)
    }
}
