package com.jobcrawler.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The four screens on the bottom bar. Settings, a job and Add a post open over them. */
enum class Tab(val label: String, val icon: Int) {
    HOME("Home", R.drawable.ic_home),
    JOBS("Jobs", R.drawable.ic_briefcase),
    EMAILS("Emails", R.drawable.ic_mail),
    LOG("Log", R.drawable.ic_terminal),
}

/**
 * The bottom bar. Home stays at the bottom of the back stack and at most one
 * other tab sits on it, so Back from any tab goes Home and Back from Home
 * leaves the app, whichever tabs were tapped in between.
 */
object Tabs {
    private const val BAR_DP = 76

    /** [content] above the bar; [overlay], such as Home's floating button, sits over the content's lower end. */
    fun wrap(activity: Activity, content: View, current: Tab, overlay: View? = null): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar(activity, current), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, BAR_DP)))
        }
        return FrameLayout(activity).apply {
            setBackgroundColor(activity.getColor(R.color.background))
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (overlay != null) {
                addView(overlay, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
                ).apply {
                    marginEnd = Ui.dp(activity, 16)
                    bottomMargin = Ui.dp(activity, BAR_DP + 16)
                })
            }
        }
    }

    private fun bar(activity: Activity, current: Tab) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        background = activity.getDrawable(R.drawable.tab_bar)
        val pad = Ui.dp(activity, 8)
        setPadding(pad, pad, pad, Ui.dp(activity, 10))
        for (tab in Tab.entries) {
            addView(item(activity, tab, tab == current), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun item(activity: Activity, tab: Tab, selected: Boolean) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isSelected = selected
        contentDescription = tab.label
        background = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let { attrs ->
            attrs.getDrawable(0).also { attrs.recycle() }
        }
        setOnClickListener { open(activity, tab, current = if (selected) tab else null) }

        val pill = FrameLayout(activity).apply {
            if (selected) {
                background = GradientDrawable().apply {
                    cornerRadius = Ui.dp(activity, 15).toFloat()
                    setColor(activity.getColor(R.color.soft))
                }
            }
            addView(
                Ui.icon(activity, tab.icon, if (selected) R.color.softText else R.color.textMuted, 20).apply {
                    imageTintList = ColorStateList.valueOf(activity.getColor(if (selected) R.color.softText else R.color.textMuted))
                },
                FrameLayout.LayoutParams(Ui.dp(activity, 20), Ui.dp(activity, 20), Gravity.CENTER),
            )
        }
        addView(pill, LinearLayout.LayoutParams(Ui.dp(activity, 56), Ui.dp(activity, 30)))
        addView(TextView(activity).apply {
            text = tab.label
            textSize = 12f
            typeface = Ui.font(activity, if (selected) R.font.manrope_extrabold else R.font.manrope_semibold)
            setTextColor(activity.getColor(if (selected) R.color.text else R.color.textMuted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(activity, 4)
        })
    }

    /** Opens [tab] from the tab screen [activity] (which shows [current], or null to work it out). */
    fun open(activity: Activity, tab: Tab, current: Tab? = null, intent: Intent? = null) {
        val here = current ?: tabOf(activity)
        if (tab == here && intent == null) return
        val leavingHome = here == Tab.HOME
        when {
            tab == Tab.HOME -> if (activity.isTaskRoot) activity.startActivity(home(activity))
            else -> activity.startActivity(intent ?: intentFor(activity, tab))
        }
        if (!leavingHome && tab != here) activity.finish()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    private fun tabOf(activity: Activity): Tab? = when (activity) {
        is MainActivity -> Tab.HOME
        is JobsActivity -> Tab.JOBS
        is ReviewActivity -> Tab.EMAILS
        is LogActivity -> Tab.LOG
        else -> null
    }

    private fun home(activity: Activity) =
        Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun intentFor(activity: Activity, tab: Tab): Intent = when (tab) {
        Tab.HOME -> home(activity)
        Tab.JOBS -> JobsActivity.intent(activity)
        Tab.EMAILS -> ReviewActivity.intent(activity)
        Tab.LOG -> LogActivity.intent(activity)
    }
}
