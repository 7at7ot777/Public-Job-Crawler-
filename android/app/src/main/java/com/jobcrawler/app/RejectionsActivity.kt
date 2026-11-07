package com.jobcrawler.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.Instant

/** Why jobs were dropped, source by source: the last run and the last seven days. */
class RejectionsActivity : Screen() {

    private lateinit var body: LinearLayout
    private var all: List<Rejections.Breakdown> = emptyList()
    private var region: Region? = null
    private val chips = mutableMapOf<Region?, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(this)
        title = "Why jobs were dropped"
        showBack()

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            for (choice in listOf<Region?>(null) + Region.SHOWN) {
                val chip = Ui.filterChip(this@RejectionsActivity, choice?.let { "${it.flag} ${it.label}" } ?: "All") {
                    region = choice
                    show()
                }
                chips[choice] = chip
                addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) })
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(android.widget.HorizontalScrollView(this@RejectionsActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(filters)
            })
            addView(ScrollView(this@RejectionsActivity).apply { addView(body) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        io {
            val now = Instant.now()
            val loaded = runCatching {
                Rejections.build(Store.shared(this).runs.since(now.minusSeconds(7 * 86_400)), now)
            }.getOrElse {
                Log.error("Rejections: could not load: ${it.message}")
                emptyList()
            }
            onUi {
                all = loaded
                show()
            }
        }
    }

    private fun show() {
        chips.forEach { (choice, chip) -> chip.isSelected = choice == region }
        body.removeAllViews()
        val shown = all.filter { region == null || Region.home(it.source) == region }
        if (shown.isEmpty()) {
            body.addView(Ui.muted(this, "No runs recorded in the last seven days yet. Each run adds its reasons here.").apply {
                setPadding(dp(4), dp(12), dp(4), 0)
            })
            return
        }
        for (b in shown) body.addView(Ui.card(this, card(b), horizontal = 0))
    }

    private fun card(b: Rejections.Breakdown): View = Ui.column(this).apply {
        val name = SettingsActivity.SOURCE_LABELS[b.source]?.first ?: b.source
        addView(Ui.title(this@RejectionsActivity, name))
        addView(Ui.muted(this@RejectionsActivity, listOfNotNull(
            JobsActivity.ago(b.lastAt?.toString())?.let { "last run $it" },
            "parsed ${b.lastParsed} → matched ${b.lastMatched}",
            "${b.weekTotal} dropped in 7 days",
        ).joinToString(" · ")))
        addView(Ui.row(this@RejectionsActivity).apply {
            setPadding(0, dp(10), 0, dp(2))
            addView(Ui.mono(this@RejectionsActivity, "REASON", 11f, R.color.textMuted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.mono(this@RejectionsActivity, "LAST", 11f, R.color.textMuted), column())
            addView(Ui.mono(this@RejectionsActivity, "7 DAYS", 11f, R.color.textMuted), column())
        })
        for ((reason, count) in Rejections.sorted(b.week)) {
            addView(Ui.row(this@RejectionsActivity).apply {
                setPadding(0, dp(4), 0, dp(4))
                addView(Ui.body(this@RejectionsActivity, Rejections.describe(reason), 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.mono(this@RejectionsActivity, (b.last[reason] ?: 0).toString(), 13f), column())
                addView(Ui.mono(this@RejectionsActivity, count.toString(), 13f), column())
            })
        }
    }

    private fun column() = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }

    companion object {
        fun intent(context: Context): Intent = Intent(context, RejectionsActivity::class.java)
    }
}
