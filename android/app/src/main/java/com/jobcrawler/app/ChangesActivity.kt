package com.jobcrawler.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What's new: every update to the app, newest first, one card per day. The
 * ones since this screen was last opened are marked New; tapping an update
 * shows the rest of what its commit says.
 */
class ChangesActivity : Screen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "What's new"
        showBack()

        val list = Ui.column(this).apply { setPadding(0, dp(6), 0, dp(16)) }
        setContentView(ScrollView(this).apply { addView(list) })

        io {
            val changes = Changes.load(this)
            val seenBefore = Changes.lastSeen(this)
            changes.firstOrNull()?.let { Changes.markSeen(this, it.at) }
            onUi { render(list, changes, seenBefore) }
        }
    }

    private fun render(list: LinearLayout, changes: List<Change>, seenBefore: Instant?) {
        val update = FrameLayout(this)
        list.addView(update)
        showUpdate(update, Updater.status(this))

        if (changes.isEmpty()) {
            list.addView(Ui.muted(this, "No updates listed in this build.", 14f).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(dp(24), dp(48), dp(24), dp(24))
            })
            return
        }

        val zone = ZoneId.systemDefault()
        for ((day, group) in changes.groupBy { it.at.atZone(zone).toLocalDate() }) {
            list.addView(Ui.sectionLabel(this, DAY.format(day)))
            val card = Ui.column(this)
            for ((index, change) in group.withIndex()) {
                if (index > 0) {
                    card.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                            topMargin = dp(12)
                            bottomMargin = dp(12)
                        })
                }
                card.addView(entry(change, seenBefore == null || change.at.isAfter(seenBefore)))
            }
            list.addView(Ui.card(this, card, vertical = 4))
        }
    }

    /** The console at the top: whether a newer build waits on Drive, and the button for it. */
    private fun showUpdate(box: FrameLayout, status: Updater.Status, checking: Boolean = false) {
        box.removeAllViews()
        val ready = status.updateReady
        val line = when {
            checking -> "Checking Drive…"
            ready -> "Update on Drive"
            else -> "Up to date"
        }
        val head = if (ready) "${status.latest?.versionName} is ready" else "You have the newest"
        val sub = if (ready) {
            "You have version ${BuildConfig.VERSION_NAME}. The app downloads it and hands it to the installer."
        } else {
            "${BuildConfig.VERSION_NAME} · " + (status.checkedAt?.let { "checked $it" } ?: "checked after runs, from the Drive folder")
        }
        val action = Button(this, null, 0, R.style.ButtonPrimary).apply {
            text = if (ready) "Update now" else "Check again"
            background = getDrawable(R.drawable.button_hero)
            setTextColor(getColor(R.color.heroOnAccent))
            isEnabled = !checking
            alpha = if (checking) 0.6f else 1f
            setOnClickListener {
                if (ready) {
                    startActivity(SettingsActivity.intent(this@ChangesActivity).putExtra(SettingsActivity.EXTRA_UPDATE, true))
                } else {
                    showUpdate(box, status, checking = true)
                    io {
                        val checked = runCatching { Updater.check(this@ChangesActivity) }
                        onUi {
                            checked.exceptionOrNull()?.let {
                                android.widget.Toast.makeText(this@ChangesActivity, it.message ?: "Could not reach Drive", android.widget.Toast.LENGTH_LONG).show()
                            }
                            showUpdate(box, checked.getOrNull() ?: status)
                        }
                    }
                }
            }
        }
        val console = Ui.column(this).apply {
            background = getDrawable(R.drawable.hero)
            setPadding(dp(18), dp(18), dp(18), dp(16))
            addView(Ui.mono(this@ChangesActivity, line.uppercase(), 12f, if (ready) R.color.heroAccent else R.color.heroMuted).apply {
                letterSpacing = 0.04f
            })
            addView(Ui.title(this@ChangesActivity, head, 26f).apply {
                typeface = Ui.font(this@ChangesActivity, R.font.manrope_extrabold)
                setTextColor(getColor(R.color.heroText))
                setPadding(0, dp(10), 0, 0)
            })
            addView(Ui.body(this@ChangesActivity, sub, 14f).apply {
                setTextColor(getColor(R.color.heroMuted))
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(6), 0, 0)
            })
            addView(action, Ui.wide(this@ChangesActivity).apply { topMargin = dp(14) })
        }
        box.addView(console, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(8), dp(16), dp(4))
        })
    }

    private fun entry(change: Change, isNew: Boolean): View = Ui.column(this).apply {
        val head = Ui.row(this@ChangesActivity).apply {
            gravity = android.view.Gravity.TOP
            addView(Ui.title(this@ChangesActivity, change.title, 15f),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (isNew) {
                addView(Ui.tag(this@ChangesActivity, "New", R.color.onPrimary, R.color.primary),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(8)
                    })
            }
        }
        addView(head)
        addView(Ui.muted(this@ChangesActivity, "${TIME.format(change.at.atZone(ZoneId.systemDefault()))} · ${change.hash}", 12f).apply {
            setPadding(0, dp(2), 0, 0)
        })

        if (change.details.isNotEmpty()) {
            val details = Ui.body(this@ChangesActivity, change.details, 14f).apply {
                setTextColor(getColor(R.color.textMuted))
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(8), 0, 0)
                maxLines = COLLAPSED_LINES
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(details)
            setOnClickListener {
                val open = details.maxLines == COLLAPSED_LINES
                details.maxLines = if (open) Int.MAX_VALUE else COLLAPSED_LINES
                details.ellipsize = if (open) null else android.text.TextUtils.TruncateAt.END
            }
        }
    }

    companion object {
        private const val COLLAPSED_LINES = 2
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun intent(context: Context): Intent = Intent(context, ChangesActivity::class.java)
    }
}
