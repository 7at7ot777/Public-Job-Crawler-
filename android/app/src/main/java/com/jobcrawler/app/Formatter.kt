package com.jobcrawler.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Telegram HTML messages, as src/formatter.js renders a job. */
object Formatter {
    private const val TITLE_MAX = 200
    private const val FIELD_MAX = 100
    private const val EXCERPT_MAX = 600

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Africa/Cairo"))

    /*
     * Telegram's HTML mode needs '&', '<' and '>' escaped in text. Quotes are
     * left alone: escaping them mangles apostrophes in company names.
     */
    fun escape(value: String?, max: Int? = null): String {
        var text = value ?: ""
        if (max != null && text.length > max) text = text.take(max - 1) + "…"
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    /** Only https links become anchors; anything else is dropped. */
    private fun link(url: String, label: String): String {
        if (!url.startsWith("https://")) return ""
        return "<a href=\"${escape(url).replace("\"", "&quot;")}\">${escape(label)}</a>"
    }

    private fun formatPostedAt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return try {
            dateFormat.format(Instant.parse(value))
        } catch (_: Exception) {
            value
        }
    }

    fun format(item: Item): String = if (item.itemType == "post") formatPost(item) else formatJob(item)

    private fun formatPost(item: Item): String {
        val lines = mutableListOf("📣 <b>${escape(item.title, TITLE_MAX)}</b>", "")

        item.company?.let { lines += "👤 ${escape(it, FIELD_MAX)}" }
        factLines(item, lines)

        // A post's heading is its own opening line: a short post would otherwise print twice.
        val excerpt = item.excerpt
        val heading = item.title.removeSuffix("…").trim().lowercase()
        if (excerpt != null && (heading.isEmpty() || !excerpt.trim().lowercase().startsWith(heading))) {
            lines += ""
            lines += escape(excerpt, EXCERPT_MAX)
        }

        formatPostedAt(item.postedAt)?.let {
            lines += ""
            lines += "🕒 Posted $it"
        }
        item.keyword?.let { lines += "🔎 Matched: <i>${escape(it, FIELD_MAX)}</i>" }

        contactLines(item, lines)
        lines += ""
        lines += link(item.url, openLabel(item))
        return join(lines)
    }

    private fun formatJob(item: Item): String {
        val lines = mutableListOf("🧑‍💻 <b>${escape(item.title, TITLE_MAX)}</b>", "")

        item.company?.let { lines += "🏢 <b>${escape(it, FIELD_MAX)}</b>" }
        item.location?.let { lines += "📍 ${escape(it, FIELD_MAX)}" }
        formatPostedAt(item.postedAt)?.let { lines += "🕒 Posted $it" }
        item.seniority?.let { lines += "📊 ${escape(it, FIELD_MAX)}" }
        factLines(item, lines)
        item.workplace?.let { lines += "🏠 ${escape(it, FIELD_MAX)}" }
        item.board?.let { lines += "🗂 via ${escape(it, FIELD_MAX)}" }
        // The lane of a job abroad: why it is here at all.
        item.market?.takeIf { it.startsWith("🌍") || it.startsWith("✈️") }?.let { lines += escape(it, FIELD_MAX) }
        // How well a job abroad fits the CV, and where it is.
        if (Mailer.isAbroad(item)) {
            val region = com.jobcrawler.app.sources.AbroadFit.regionOf(item.location)
            lines += "🎯 CV fit ${com.jobcrawler.app.sources.AbroadFit.score(item)}/100" + (region?.let { " · $it" } ?: "")
        }
        // A Saudi/UAE job in Egypt's channel: the country up front, whatever the place line says.
        item.market?.takeIf { Gulf.isGulf(it) }?.let { lines += escape(Gulf.flagged(it), FIELD_MAX) }
        item.keyword?.let { lines += "🔎 Matched: <i>${escape(it, FIELD_MAX)}</i>" }

        contactLines(item, lines)
        lines += ""
        lines += link(item.url, openLabel(item))
        return join(lines)
    }

    /** Fit, experience and pay, when known: what decides whether to open it. */
    private fun factLines(item: Item, lines: MutableList<String>) {
        item.score?.let { lines += "⭐ $it/100" }
        Extract.Years.of(item)?.let { lines += "🧭 $it" }
        item.salary?.let { lines += "💰 ${escape(it, FIELD_MAX)}" }
    }

    /** Contacts are the point of the whole filter, so they get their own block. */
    private fun contactLines(item: Item, lines: MutableList<String>) {
        if (item.contactEmails.isNotEmpty()) {
            lines += ""
            lines += "📧 <code>${escape(item.contactEmails.joinToString(", "), FIELD_MAX * 2)}</code>"
        }
        if (item.contactPhones.isNotEmpty()) {
            if (item.contactEmails.isEmpty()) lines += ""
            lines += "📱 <code>${escape(item.contactPhones.joinToString(", "), FIELD_MAX * 2)}</code>"
        }
        // One channel for every region: the tag is what a search or a filter in Telegram keys on.
        lines += ""
        lines += Region.of(item).tag
    }

    /** Trailing blanks dropped, so a missing link leaves no dangling newlines. */
    private fun join(lines: MutableList<String>): String {
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n")
    }

    private fun openLabel(item: Item): String {
        val url = item.url
        return when {
            "linkedin.com" in url -> "Open on LinkedIn ↗"
            "wuzzuf.net" in url -> "Open on Wuzzuf ↗"
            "recruitera.ai" in url -> "Open on Recruitera ↗"
            "workable.com" in url -> "Open on Workable ↗"
            "jobright.ai" in url -> "Open on Jobright ↗"
            "relocate.me" in url -> "Open on Relocate.me ↗"
            "glassdoor." in url -> "Open on Glassdoor ↗"
            "lever.co" in url -> "Open on Lever ↗"
            "greenhouse.io" in url -> "Open on Greenhouse ↗"
            "ashbyhq.com" in url -> "Open on Ashby ↗"
            "bamboohr.com" in url -> "Open on BambooHR ↗"
            "tanqeeb.com" in url ->
                if (item.board != null) "Open on Tanqeeb (from ${item.board}) ↗" else "Open on Tanqeeb ↗"
            else -> "Open the posting ↗"
        }
    }
}
