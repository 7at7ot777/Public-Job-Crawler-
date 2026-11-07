package com.jobcrawler.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * A LinkedIn post the person found themselves: shared here from the LinkedIn
 * app, or pasted. The post is read, kept with the jobs, and — when it gives an
 * address — its CV email is one tap away, sent only on that tap.
 */
class AddPostActivity : Screen() {

    private lateinit var body: LinearLayout
    private lateinit var inputCard: View
    private lateinit var field: EditText
    private lateinit var readButton: Button
    private lateinit var pasteButton: Button
    private lateinit var tips: View
    private lateinit var wall: View
    private lateinit var wallText: TextView
    private lateinit var result: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(this)
        title = "Add a post"
        showBack()

        field = EditText(this, null, 0, R.style.Field).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            hint = "https://www.linkedin.com/posts/…"
            textSize = 14f
        }
        readButton = Ui.primary(this, "Read post") { read() }
        pasteButton = Ui.soft(this, "Paste") { paste() }

        inputCard = Ui.card(this, Ui.column(this).apply {
            addView(Ui.title(this@AddPostActivity, "LinkedIn post"))
            addView(Ui.muted(this@AddPostActivity,
                "Its link, its text, or both. The app reads the post, finds the hiring email and drafts your note.").apply {
                setPadding(0, dp(4), 0, dp(10))
                setLineSpacing(0f, 1.15f)
            })
            addView(field, Ui.matchWidth())
            addView(Ui.buttonRow(this@AddPostActivity, readButton, pasteButton))
        }, horizontal = 0)

        tips = Ui.column(this).apply {
            addView(Ui.card(this@AddPostActivity, Ui.row(this@AddPostActivity).apply {
                gravity = Gravity.TOP
                addView(Ui.icon(this@AddPostActivity, R.drawable.ic_send, R.color.softText, 40).apply {
                    background = getDrawable(R.drawable.icon_soft)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                })
                addView(Ui.column(this@AddPostActivity).apply {
                    addView(Ui.title(this@AddPostActivity, "Faster from the LinkedIn app", 15f))
                    addView(Ui.muted(this@AddPostActivity,
                        "On the post, tap Send or Share, then Job Crawler. This screen opens with the post already in it.").apply {
                        setPadding(0, dp(4), 0, 0)
                        setLineSpacing(0f, 1.15f)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            }, horizontal = 0))
            addView(Ui.row(this@AddPostActivity).apply {
                gravity = Gravity.TOP
                setPadding(dp(4), dp(10), dp(4), 0)
                addView(Ui.icon(this@AddPostActivity, R.drawable.ic_info, size = 20))
                addView(Ui.muted(this@AddPostActivity,
                    "LinkedIn sometimes hides a post behind its sign-in page. If that happens, the app asks for the text: on the post, tap ⋯ then Copy text.").apply {
                    setLineSpacing(0f, 1.15f)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            })
        }

        // The sign-in page came back instead of the post: ask for its text.
        wallText = Ui.body(this, "", 14f).apply { setLineSpacing(0f, 1.15f) }
        wall = Ui.row(this).apply {
            gravity = Gravity.TOP
            background = getDrawable(R.drawable.banner)
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.warnSoft))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
            addView(Ui.icon(this@AddPostActivity, R.drawable.ic_warning, R.color.warn, 20))
            addView(wallText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        }

        result = Ui.column(this)

        body = Ui.column(this).apply {
            setPadding(dp(16), dp(10), dp(16), dp(24))
            addView(inputCard)
            addView(wall, Ui.matchWidth().apply { topMargin = dp(6) })
            addView(tips, Ui.matchWidth().apply { topMargin = dp(6) })
            addView(result)
        }
        setContentView(ScrollView(this).apply { addView(body) })

        shared(intent)?.let {
            field.setText(it)
            read()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared(intent)?.let {
            result.removeAllViews()
            field.setText(it)
            read()
        }
    }

    /** What the LinkedIn app's share sheet sent: the post's link, sometimes a line of text with it. */
    private fun shared(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
            .joinToString("\n").trim().ifEmpty { null }
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            toast("Nothing copied yet")
            return
        }
        val current = field.text.toString().trim()
        field.setText(if (current.isEmpty()) text else "$current\n\n$text")
        field.setSelection(field.text.length)
    }

    /** Everything the screen shows after a read, worked out off the main thread. */
    private class Loaded(val row: Row, val address: String?, val outcome: Pair<String, String?>?)

    private fun read() {
        val input = field.text.toString().trim()
        if (input.isEmpty()) {
            toast("Paste the post's link or its text first")
            return
        }
        reading(true)
        io {
            val read = runCatching { PostIntake.read(input) }
                .onFailure { Log.error("Add a post: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            val loaded = (read as? PostIntake.Outcome.Read)?.let { found ->
                runCatching {
                    Store.shared(this).let { store ->
                        val row = PostIntake.save(store, found.item)
                        Log.info("Add a post: ${row.item.title}${row.item.contactEmails.firstOrNull()?.let { " → $it" } ?: ""}")
                        Loaded(row, Mailer.addressFor(row.item, ApplySettings.load(this)), store.applications.emailOutcome(row.item))
                    }
                }.onFailure { Log.error("Add a post: could not store: ${it.message}") }.getOrNull()
            }
            onUi {
                reading(false)
                when {
                    loaded != null -> showPost(loaded)
                    read is PostIntake.Outcome.Walled -> showWall(read)
                    read == PostIntake.Outcome.NotAPost ->
                        toast("No LinkedIn post link there, and too little text to be a post")
                    else -> toast("Could not read the post. Try again, or paste its text")
                }
            }
        }
    }

    private fun reading(on: Boolean) {
        readButton.isEnabled = !on
        pasteButton.isEnabled = !on
        readButton.text = if (on) "Reading…" else "Read post"
    }

    private fun showWall(walled: PostIntake.Outcome.Walled) {
        val known = listOfNotNull(walled.author, walled.postedAt?.let { "posted ${JobsActivity.ago(it) ?: it}" })
        wallText.text = buildString {
            append("LinkedIn showed its sign-in page, not the post. Copy the post's text in the LinkedIn app (⋯ → Copy text), ")
            append("paste it under the link above, and tap Read again.")
            if (known.isNotEmpty()) append("\n\nFound so far: ${known.joinToString(" · ")}")
        }
        wall.visibility = View.VISIBLE
        tips.visibility = View.GONE
        field.requestFocus()
        if (!field.text.endsWith("\n")) field.append("\n\n")
        field.setSelection(field.text.length)
    }

    private fun showPost(loaded: Loaded) {
        val row = loaded.row
        val item = row.item
        inputCard.visibility = View.GONE
        wall.visibility = View.GONE
        tips.visibility = View.GONE
        result.removeAllViews()

        // The post, as read.
        result.addView(Ui.card(this, Ui.column(this).apply {
            addView(Ui.chips(this@AddPostActivity,
                Ui.chip(this@AddPostActivity, "LinkedIn post"),
                Ui.chip(this@AddPostActivity, "Added by you", R.color.textMuted, R.color.background)))
            addView(Ui.title(this@AddPostActivity, item.title, 18f).apply { setPadding(0, dp(10), 0, 0) })
            listOfNotNull(item.company, item.postedAt?.let { JobsActivity.ago(it)?.let { ago -> "posted $ago" } })
                .joinToString(" · ").ifEmpty { null }?.let {
                    addView(Ui.body(this@AddPostActivity, it, 14f).apply { setPadding(0, dp(4), 0, 0) })
                }
            addView(Ui.muted(this@AddPostActivity, item.excerpt.orEmpty(), 14f).apply {
                setPadding(0, dp(8), 0, 0)
                setLineSpacing(0f, 1.25f)
                maxLines = 12
                ellipsize = android.text.TextUtils.TruncateAt.END
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setTextIsSelectable(true)
            })
        }, horizontal = 0))

        val mail = Ui.column(this)
        result.addView(Ui.card(this, mail, horizontal = 0))

        val outcome = loaded.outcome
        when {
            outcome?.first == "email" -> mailDone(mail, "CV already sent", "To ${outcome?.second}", R.color.good)
            outcome != null -> mailDone(mail, "Already settled", when (outcome.first) {
                "email-ignored" -> "You ignored this post earlier"
                "email-failed" -> "${outcome.second} refused the email"
                else -> "No address on it that a person reads"
            }, R.color.textMuted)
            loaded.address != null -> mailReady(mail, row, loaded.address)
            else -> noEmail(mail, row)
        }
    }

    private fun mailHeader(mail: LinearLayout, title: String, note: String, tint: Int, fill: Int, icon: Int = R.drawable.ic_mail) {
        mail.addView(Ui.row(this).apply {
            addView(Ui.icon(this@AddPostActivity, icon, tint, 40).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(getColor(fill))
                }
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })
            addView(Ui.column(this@AddPostActivity).apply {
                addView(Ui.title(this@AddPostActivity, title))
                addView(Ui.muted(this@AddPostActivity, note))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        })
    }

    private fun mailReady(mail: LinearLayout, row: Row, address: String) {
        mailHeader(mail, "Email found", "Check it, add anything, then send", R.color.softText, R.color.soft)

        // The drafted email, every part of it editable: nothing goes out until Send CV.
        val drafted = Mailer.draft(row.item, ApplySettings.load(this))
            ?: Mailer.Draft(address, CoverNote.subject(row.item), CoverNote.text(row.item, ApplySettings.load(this), withCv = true))
        val to = editor(drafted.to, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val subject = editor(drafted.subject, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val message = editor(drafted.body,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            setLineSpacing(0f, 1.15f)
        }

        mail.addView(fieldLabel("To"))
        mail.addView(to, Ui.matchWidth())
        val others = row.item.contactEmails.mapNotNull(Recipients::normalise).distinct() - drafted.to
        if (others.isNotEmpty()) {
            mail.addView(Ui.muted(this, "Also in the post: ${others.joinToString(", ")}", 12f).apply { setPadding(0, dp(4), 0, 0) })
        }
        mail.addView(fieldLabel("Subject"))
        mail.addView(subject, Ui.matchWidth())
        mail.addView(fieldLabel("Message"))
        mail.addView(message, Ui.matchWidth())

        mail.addView(Ui.row(this).apply {
            setPadding(0, dp(6), 0, 0)
            addView(Ui.icon(this@AddPostActivity, R.drawable.ic_file, size = 14))
            addView(Ui.muted(this@AddPostActivity, Cv.name(this@AddPostActivity)?.let { "Attached: $it" } ?: "No CV chosen yet: pick one in Settings"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
            addView(Ui.filterChip(this@AddPostActivity, "Reset the note") {
                subject.setText(drafted.subject)
                message.setText(drafted.body)
            }.apply { background = null })
        })

        val send = Ui.primary(this, "Send CV") {}
        val save = Ui.soft(this, "Save only") {
            toast("Saved to Jobs. It also waits in Emails.")
            finish()
        }
        val footnote = Ui.muted(this, "Saved to Jobs either way. Nothing is emailed until you tap Send CV.", 12f).apply {
            setPadding(0, dp(8), 0, 0)
        }
        mail.addView(Ui.buttonRow(this, send, save))
        mail.addView(footnote)

        send.setOnClickListener {
            send.isEnabled = false
            save.isEnabled = false
            send.text = "Sending…"
            val draft = Mailer.Draft(to.text.toString(), subject.text.toString(), message.text.toString())
            io {
                val sent = runCatching { Store.shared(this).let { Mailer.send(this, it, row, draft) } }
                    .getOrElse { Mailer.Result.Failed("${it.javaClass.simpleName}: ${it.message}", permanent = false) }
                onUi {
                    toast(Mailer.describe(sent))
                    if (Mailer.settles(sent)) {
                        mail.removeAllViews()
                        if (sent is Mailer.Result.Sent) {
                            mailDone(mail, "CV sent", "To ${sent.address} · just now", R.color.good)
                        } else {
                            mailDone(mail, "Not sent", Mailer.describe(sent), R.color.bad)
                        }
                    } else {
                        send.isEnabled = true
                        save.isEnabled = true
                        send.text = "Send CV"
                    }
                }
            }
        }
    }

    private fun editor(text: String, type: Int) = EditText(this, null, 0, R.style.Field).apply {
        inputType = type
        textSize = 15f
        setText(text)
    }

    /** "TO" over a field in the email. */
    private fun fieldLabel(text: String) = Ui.muted(this, text.uppercase(), 12f).apply {
        letterSpacing = 0.06f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun mailDone(mail: LinearLayout, title: String, note: String, tint: Int) {
        val fill = when (tint) {
            R.color.good -> R.color.goodSoft
            R.color.bad -> R.color.badSoft
            else -> R.color.background
        }
        mailHeader(mail, title, note, tint, fill, if (tint == R.color.good) R.drawable.ic_check else R.drawable.ic_mail)
        mail.addView(Ui.soft(this, "Done") { finish() }, Ui.wide(this))
    }

    private fun noEmail(mail: LinearLayout, row: Row) {
        val note = if (row.item.contactEmails.isEmpty()) "Saved to Jobs. Reply on LinkedIn instead."
        else "Only addresses nobody reads, or ones you blocked. Saved to Jobs."
        mailHeader(mail, "No email in this post", note, R.color.textMuted, R.color.background)
        mail.addView(Ui.primary(this, "Copy cover note and open post") {
            startActivity(ApplyActivity.intent(this, row.id))
            finish()
        }.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                getDrawable(R.drawable.ic_copy)!!.mutate().apply { setTint(getColor(R.color.onPrimary)) }, null, null, null,
            )
            compoundDrawablePadding = dp(8)
        }, Ui.wide(this))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        fun intent(context: Context): Intent = Intent(context, AddPostActivity::class.java)
    }
}
