package com.jobcrawler.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The jobs that give an address, each waiting on a decision: Send emails the
 * CV now, Ignore drops the job for good, and a tap on the job opens it with
 * its description. Nothing reaches an inbox without a tap here.
 */
class ReviewActivity : Screen() {

    private var rows: List<Pending> = emptyList()
    private val sending = mutableSetOf<Long>()
    private var ready = true
    private lateinit var intro: TextView
    private lateinit var empty: View
    private lateinit var cards: LinearLayout

    /* The second tab: what the Saudi & UAE sources emailed on their own. */
    private var auto = false
    private lateinit var reviewTab: android.widget.Button
    private lateinit var autoTab: android.widget.Button
    private lateinit var reviewPane: View
    private lateinit var autoPane: LinearLayout
    private var autoRows: List<ApplicationsDao.AutoEmail> = emptyList()

    /** The job whose email is open for editing, if any: one at a time. */
    private var editing: Long? = null
    /** What the person typed, kept by row id so a reload or a rebuild does not lose it. */
    private val edits = mutableMapOf<Long, Mailer.Draft>()

    /** A row, the address Send would use and the email it would write, worked out once when the list loads. */
    class Pending(val row: Row, val address: String, val draft: Mailer.Draft)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(this)
        title = "Emails"
        auto = intent.getBooleanExtra(EXTRA_AUTO, false)

        intro = Ui.muted(this, "", 14f).apply { setLineSpacing(0f, 1.15f) }
        val introRow = Ui.row(this).apply {
            gravity = Gravity.TOP
            setPadding(dp(20), dp(16), dp(20), dp(6))
            addView(Ui.icon(this@ReviewActivity, R.drawable.ic_info, size = 20))
            addView(intro, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        }

        // Shown when every job with an address has been sent or ignored.
        empty = Ui.column(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(56), dp(24), dp(24))
            visibility = View.GONE
            addView(Ui.icon(this@ReviewActivity, R.drawable.ic_check, R.color.good, 56).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(getColor(R.color.goodSoft))
                }
                setPadding(dp(15), dp(15), dp(15), dp(15))
            })
            addView(Ui.title(this@ReviewActivity, "Nothing to review", 17f).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
            })
            addView(Ui.muted(this@ReviewActivity, "Jobs that give a hiring address show up here after each run.", 14f).apply {
                gravity = Gravity.CENTER
            })
        }

        // A plain column, not a ListView: the email fields are EditTexts, which a recycling list loses.
        cards = Ui.column(this).apply { setPadding(0, dp(4), 0, dp(16)) }
        reviewPane = Ui.column(this).apply {
            addView(introRow)
            addView(empty)
            addView(cards)
        }
        autoPane = Ui.column(this).apply { setPadding(0, dp(4), 0, dp(16)) }

        // Two tabs in a track: what waits on a tap, and what went on its own.
        reviewTab = segment("To review") { pick(false) }
        autoTab = segment("Sent on their own") { pick(true) }
        val segments = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(getColor(R.color.track))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(reviewTab, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(autoTab, LinearLayout.LayoutParams(0, dp(40), 1f))
        }

        setContentView(Tabs.wrap(this, ScrollView(this).apply {
            addView(Ui.column(this@ReviewActivity).apply {
                addView(segments, Ui.matchWidth().apply {
                    marginStart = dp(16)
                    marginEnd = dp(16)
                    topMargin = dp(8)
                })
                addView(reviewPane)
                addView(autoPane)
            })
        }, Tab.EMAILS))
        pick(auto)
    }

    private fun segment(text: String, onClick: () -> Unit) =
        android.widget.Button(this, null, 0, R.style.ButtonPrimary).apply {
            this.text = text
            textSize = 14f
            minHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
        }

    private fun pick(auto: Boolean) {
        this.auto = auto
        for ((tab, on) in listOf(reviewTab to !auto, autoTab to auto)) {
            tab.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(getColor(if (on) R.color.surface else android.R.color.transparent))
            }
            tab.setTextColor(getColor(if (on) R.color.text else R.color.textMuted))
            tab.isSelected = on
        }
        reviewPane.visibility = if (auto) View.GONE else View.VISIBLE
        autoPane.visibility = if (auto) View.VISIBLE else View.GONE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_AUTO)) pick(intent.getBooleanExtra(EXTRA_AUTO, false))
    }

    // Loaded on every return, so a job sent or ignored on its own screen is gone here too.
    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        io {
            val profile = ApplySettings.load(this)
            val pending = runCatching {
                Store.shared(this).let { store ->
                    store.reviewQueue(Mailer.queueSince()).mapNotNull { row ->
                        val address = Mailer.addressFor(row.item, profile)
                        // Only no-reply or blocked addresses: nothing to decide.
                        if (address == null) store.applications.record(row.item, "email-skipped")
                        address?.let {
                            Pending(row, it, Mailer.Draft(it, CoverNote.subject(row.item), CoverNote.text(row.item, profile, withCv = true)))
                        }
                    }
                }
            }.getOrElse {
                Log.error("Review: could not load: ${it.javaClass.simpleName}: ${it.message}")
                emptyList()
            }
            val ready = profile.mailReady && Cv.name(this) != null
            val sentAlone = runCatching { Store.shared(this).let { it.applications.autoEmails(Mailer.AUTO_SOURCES) } }.getOrElse { emptyList() }
            onUi {
                show(pending, ready)
                showAuto(sentAlone, profile)
            }
        }
    }

    private fun show(pending: List<Pending>, ready: Boolean = this.ready) {
        rows = pending
        this.ready = ready
        render()
        reviewTab.text = "To review · ${rows.size}"
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        intro.text = when {
            rows.isEmpty() -> "Every job with an address has been sent or ignored."
            !ready -> "${rows.size} waiting. Sending needs a Gmail account and a CV, both set in Settings."
            rows.size == 1 -> "1 job gives a hiring address. Nothing is emailed until you tap Send."
            else -> "${rows.size} jobs give a hiring address. Nothing is emailed until you tap Send."
        }
    }

    /** What the Saudi & UAE sources did on their own, newest first; a tap opens the job. */
    private fun showAuto(list: List<ApplicationsDao.AutoEmail>, profile: ApplySettings) {
        autoRows = list
        autoTab.text = "Sent on their own · ${list.count { it.channel == "email" }}"
        reviewTab.text = "To review · ${rows.size}"
        autoPane.removeAllViews()

        val explain = Ui.column(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(getColor(R.color.gulfSoft))
            }
            setPadding(dp(16), dp(14), dp(16), dp(10))
            addView(Ui.tag(this@ReviewActivity, "Saudi & UAE", R.color.gulf, null).apply { background = null; setPadding(0, 0, 0, 0) })
            addView(Ui.body(this@ReviewActivity, when {
                !profile.gulfAutoEmail -> "Off: Saudi/UAE jobs wait under To review like the rest. They never go to Telegram."
                else -> "No review and no limit: every new Saudi/UAE job that gives an address gets your CV from " +
                    "${profile.gulfSender.gmail.ifBlank { "your Gmail" }}. They never go to Telegram."
            }, 14f).apply {
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(4), 0, 0)
            })
            addView(Ui.filterChip(this@ReviewActivity, "Change in Settings") {
                startActivity(SettingsActivity.intent(this@ReviewActivity))
            }.apply {
                background = null
                setTextColor(getColor(R.color.gulf))
                setPadding(0, 0, 0, 0)
            })
        }
        autoPane.addView(explain, Ui.matchWidth().apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            topMargin = dp(12)
            bottomMargin = dp(6)
        })

        if (list.isEmpty()) {
            autoPane.addView(Ui.muted(this, "Nothing sent on its own yet. Each run emails the new Saudi/UAE jobs that give an address.", 14f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(32), dp(24), dp(24))
            })
            return
        }
        for (entry in list) autoPane.addView(autoCard(entry))
    }

    private fun autoCard(entry: ApplicationsDao.AutoEmail): View {
        val sent = entry.channel == "email"
        val note = when (entry.channel) {
            "email" -> listOfNotNull("To ${entry.address ?: "?"}", JobsActivity.ago(entry.createdAt)).joinToString(" · ")
            "email-failed" -> "Refused by ${entry.address ?: "the server"}. Saved to Jobs."
            else -> "Not sent: no address a person reads. Saved to Jobs."
        }
        val badge = Ui.icon(this, if (sent) R.drawable.ic_check else R.drawable.ic_warning, if (sent) R.color.gulf else R.color.warn, 36).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(getColor(if (sent) R.color.gulfSoft else R.color.warnSoft))
            }
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        val text = Ui.column(this).apply {
            addView(Ui.title(this@ReviewActivity, entry.title ?: "Job", 15f))
            entry.company?.let { addView(Ui.muted(this@ReviewActivity, it, 13f).apply { setPadding(0, dp(2), 0, 0) }) }
            addView(Ui.mono(this@ReviewActivity, note, 11.5f, if (sent) R.color.textMuted else R.color.warn).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        val content = Ui.row(this).apply {
            gravity = Gravity.TOP
            addView(badge)
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            isClickable = true
            setOnClickListener { openJob(entry) }
        }
        return Ui.card(this, content, vertical = 5)
    }

    private fun openJob(entry: ApplicationsDao.AutoEmail) {
        io {
            val row = runCatching { Store.shared(this).let { it.findByExternalId(entry.source, entry.externalId) } }.getOrNull()
            onUi {
                if (row == null) toast("That job is no longer stored") else startActivity(JobActivity.intent(this, row.id))
            }
        }
    }

    private fun drop(id: Long) {
        rows = rows.filterNot { it.row.id == id }
        edits.remove(id)
        if (editing == id) editing = null
        show(rows)
    }

    private fun send(pending: Pending) {
        val id = pending.row.id
        if (!sending.add(id)) return
        val draft = edits[id]
        render()
        io {
            val result = runCatching { Store.shared(this).let { Mailer.send(this, it, pending.row, draft) } }
                .getOrElse { Mailer.Result.Failed("${it.javaClass.simpleName}: ${it.message}", permanent = false) }
            onUi {
                sending.remove(id)
                toast(Mailer.describe(result))
                if (Mailer.settles(result)) drop(id) else render()
            }
        }
    }

    private fun ignore(pending: Pending) {
        io { runCatching { Store.shared(this).let { Mailer.ignore(it, pending.row) } } }
        drop(pending.row.id)
        toast("Ignored. It will not be offered again.")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun render() {
        cards.removeAllViews()
        rows.forEach { cards.addView(card(it)) }
    }

    private fun card(pending: Pending): View {
        val item = pending.row.item
        val id = pending.row.id
        val busy = sending.isNotEmpty()
        val mine = id in sending
        val open = editing == id
        val draft = edits[id] ?: pending.draft
        val context = this

        val details = Ui.column(context).apply {
            isClickable = true
            setOnClickListener { startActivity(JobActivity.intent(context, id)) }
            addView(Ui.mono(context, JobsActivity.sourceName(item), 11f, R.color.textMuted))
            addView(Ui.title(context, item.title, 17f).apply { setPadding(0, dp(6), 0, 0) })
            listOfNotNull(item.company, item.location).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                addView(Ui.body(context, it, 14f).apply {
                    setTextColor(getColor(R.color.textMuted))
                    setPadding(0, dp(2), 0, 0)
                })
            }
            addView(Ui.row(context).apply {
                setPadding(0, dp(6), 0, 0)
                addView(Ui.icon(context, R.drawable.ic_mail, size = 14))
                addView(Ui.muted(context, "${draft.to} · read the job first"),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(6)
                    })
            })
        }

        val content = Ui.column(context).apply { addView(details) }

        // The email as Send would write it, every part editable, as on Add a post.
        if (open) {
            val to = editor(draft.to, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            val subject = editor(draft.subject, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            val message = editor(draft.body,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
                minLines = 6
                gravity = Gravity.TOP or Gravity.START
                setLineSpacing(0f, 1.15f)
            }
            val keep = { edits[id] = Mailer.Draft(to.text.toString(), subject.text.toString(), message.text.toString()) }
            listOf(to, subject, message).forEach { it.addTextChangedListener(changed(keep)) }
            content.addView(fieldLabel("To"))
            content.addView(to, Ui.matchWidth())
            content.addView(fieldLabel("Subject"))
            content.addView(subject, Ui.matchWidth())
            content.addView(fieldLabel("Message"))
            content.addView(message, Ui.matchWidth())
            content.addView(Ui.filterChip(context, "Reset the email") {
                edits.remove(id)
                render()
            }.apply { background = null })
        }
        content.addView(Ui.filterChip(context, if (open) "Hide the email" else "Edit the email") {
            editing = if (open) null else id
            render()
        }.apply {
            background = null
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                getDrawable(R.drawable.ic_edit)!!.mutate().apply {
                    setTint(getColor(R.color.softText))
                    setBounds(0, 0, dp(16), dp(16))
                }, null, null, null,
            )
            compoundDrawablePadding = dp(6)
        })

        // One email at a time: while one sends, every button waits.
        val sendButton = Ui.primary(context, if (mine) "Sending…" else "Send CV") { send(pending) }.apply { isEnabled = !busy }
        val ignoreButton = Ui.outline(context, "Ignore") { ignore(pending) }.apply { isEnabled = !busy }
        content.addView(Ui.buttonRow(context, sendButton, ignoreButton))
        return Ui.card(context, content)
    }

    private fun editor(text: String, type: Int) = EditText(this, null, 0, R.style.Field).apply {
        inputType = type
        textSize = 15f
        setText(text)
    }

    private fun changed(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange()
    }

    /** "TO" over a field in the email. */
    private fun fieldLabel(text: String) = Ui.muted(this, text, 12f).apply {
        typeface = Ui.font(this@ReviewActivity, R.font.manrope_bold)
        setPadding(0, dp(14), 0, dp(6))
    }

    companion object {
        private const val EXTRA_AUTO = "auto"

        /** [auto]: open on "Sent on their own". */
        fun intent(context: Context, auto: Boolean = false): Intent =
            Intent(context, ReviewActivity::class.java).putExtra(EXTRA_AUTO, auto)
    }
}
