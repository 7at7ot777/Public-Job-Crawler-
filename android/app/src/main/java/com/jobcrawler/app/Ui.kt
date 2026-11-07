package com.jobcrawler.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The look of the screens built in code, from the same colours, shapes and
 * styles as the layouts, so every screen matches without AndroidX.
 */
object Ui {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun color(context: Context, id: Int) = context.getColor(id)

    /** A bundled face from res/font: Manrope for words, IBM Plex Mono for numbers. */
    fun font(context: Context, id: Int): Typeface = context.resources.getFont(id)

    /** A rounded card around [content], spaced for a list or a page. */
    fun card(context: Context, content: View, horizontal: Int = 16, vertical: Int = 6): View =
        FrameLayout(context).apply {
            setPadding(dp(context, horizontal), dp(context, vertical), dp(context, horizontal), dp(context, vertical))
            addView(
                content.apply {
                    background = context.getDrawable(R.drawable.card)
                    setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    fun title(context: Context, text: String, size: Float = 16f) = TextView(context).apply {
        this.text = text
        textSize = size
        typeface = font(context, R.font.manrope_bold)
        setTextColor(color(context, R.color.text))
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    fun body(context: Context, text: String, size: Float = 15f) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color(context, R.color.text))
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    fun muted(context: Context, text: String, size: Float = 13f) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color(context, R.color.textMuted))
    }

    /** "WHAT TO SEARCH" over a group of rows. */
    fun sectionLabel(context: Context, text: String) = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 12f
        letterSpacing = 0.08f
        typeface = font(context, R.font.manrope_extrabold)
        setTextColor(color(context, R.color.textMuted))
        setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 6))
    }

    private fun pill(context: Context, fill: Int) = GradientDrawable().apply {
        cornerRadius = dp(context, 100).toFloat()
        setColor(color(context, fill))
    }

    /** A small rounded label, such as the source a job came from. */
    fun chip(context: Context, text: String, textColor: Int = R.color.softText, fill: Int = R.color.soft) =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            typeface = font(context, R.font.manrope_bold)
            setTextColor(color(context, textColor))
            background = pill(context, fill)
            setPadding(dp(context, 10), dp(context, 3), dp(context, 10), dp(context, 3))
        }

    /** A square-cornered capital label: NEW, SAUDI & UAE, PAID. Outlined when [fill] is null. */
    fun tag(context: Context, text: String, textColor: Int, fill: Int?) = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 10.5f
        letterSpacing = 0.05f
        typeface = font(context, R.font.manrope_extrabold)
        setTextColor(color(context, textColor))
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 6).toFloat()
            if (fill != null) setColor(color(context, fill)) else setStroke(dp(context, 1), color(context, textColor))
        }
        setPadding(dp(context, 7), dp(context, 2), dp(context, 7), dp(context, 2))
    }

    /** A count, a time or a source key, in the monospaced face. */
    fun mono(context: Context, text: String, size: Float = 13f, textColor: Int = R.color.text) = TextView(context).apply {
        this.text = text
        textSize = size
        typeface = font(context, R.font.plex_mono_medium)
        setTextColor(color(context, textColor))
    }

    fun outline(context: Context, text: String, onClick: () -> Unit) =
        Button(context, null, 0, R.style.ButtonOutline).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    /** A row of chips. */
    fun chips(context: Context, vararg chips: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((index, chip) in chips.withIndex()) {
            addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) marginStart = dp(context, 6)
            })
        }
    }

    /** A round status dot. */
    fun dot(context: Context, fill: Int, size: Int = 8) = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(context, fill))
        }
        layoutParams = LinearLayout.LayoutParams(dp(context, size), dp(context, size))
    }

    /** A line icon from res/drawable, tinted. */
    fun icon(context: Context, res: Int, tint: Int = R.color.textMuted, size: Int = 20) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color(context, tint))
        layoutParams = LinearLayout.LayoutParams(dp(context, size), dp(context, size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** An icon followed by text, for a job's facts. */
    fun fact(context: Context, iconRes: Int, text: String, textColor: Int = R.color.text) = row(context).apply {
        minimumHeight = dp(context, 32)
        addView(icon(context, iconRes, size = 18))
        addView(body(context, text, 14f).apply {
            setTextColor(color(context, textColor))
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(context, 12) })
    }

    fun primary(context: Context, text: String, onClick: () -> Unit) =
        Button(context, null, 0, R.style.ButtonPrimary).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    fun soft(context: Context, text: String, onClick: () -> Unit) =
        Button(context, null, 0, R.style.ButtonSoft).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    /** A pill that filters a list; selected while its filter is on. */
    fun filterChip(context: Context, text: String, onClick: () -> Unit) =
        Button(context, null, 0, R.style.FilterChip).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    /** Buttons side by side, sharing the width. */
    fun buttonRow(context: Context, vararg buttons: Button) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((index, button) in buttons.withIndex()) {
            addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = dp(context, 10)
                if (index > 0) marginStart = dp(context, 8)
            })
        }
    }

    /** Full width, with the gap above that a button in a card has. */
    fun wide(context: Context) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 10)
        }

    fun matchWidth() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

/*
 * Background work for screens. One pool of reused daemon threads instead of
 * a new Thread per load; results come back through [onUi], which drops them
 * once the screen is closing or gone, so a slow load never touches dead views.
 */
private val screenWork = java.util.concurrent.Executors.newCachedThreadPool { task ->
    Thread(task, "screen-io").apply { isDaemon = true }
}

/** Runs [work] off the main thread. */
fun Activity.io(work: () -> Unit) = screenWork.execute(work)

/** Runs [block] on the main thread, unless this screen is finishing or destroyed by then. */
fun Activity.onUi(block: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) block() }

/** A screen under Home: the bar's back arrow closes it. */
open class Screen : Activity() {
    protected fun showBack() {
        actionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    fun dp(value: Int) = Ui.dp(this, value)
}
