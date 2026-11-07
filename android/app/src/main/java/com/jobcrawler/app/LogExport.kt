package com.jobcrawler.app

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The log as the Log screen's Send hands it to Telegram: chat messages, all of it or only the problems. */
object LogExport {
    // "11-02 08:47:15 W LinkedIn throttled …": the third field is the level.
    private val LINE = Regex("""^\S+\s+\S+\s+([IWE])\s""")

    /** Warnings and errors, plus each run's start line so they stay grouped by run. */
    fun problems(text: String): String = text.lineSequence().filter { line ->
        val level = LINE.find(line)?.groupValues?.get(1)
        level == "W" || level == "E" || line.contains("Run started")
    }.joinToString("\n")

    fun fileName(now: LocalDateTime = LocalDateTime.now()): String =
        "crawler-log-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.txt"

    fun caption(version: String, text: String, onlyProblems: Boolean): String {
        val lines = text.lineSequence().count { it.isNotBlank() }
        return "Job Crawler $version log · $lines lines · ${if (onlyProblems) "problems only" else "all"}"
    }

    /**
     * The log as Telegram messages: a caption header, then the lines in
     * <pre> blocks, each message under Telegram's 4096 characters. Lines are
     * never split across messages (one longer than a message is cut). Past
     * [maxMessages] the oldest lines (the log's tail) are left out and the
     * header says so, so one tap cannot flood the chat.
     */
    fun messages(version: String, text: String, onlyProblems: Boolean, maxMessages: Int = 15): List<String> {
        val budget = MESSAGE_LIMIT - "<pre></pre>".length
        val chunks = mutableListOf<StringBuilder>()
        var omitted = 0
        for (raw in text.lineSequence().filter { it.isNotBlank() }) {
            var line = escape(raw)
            if (line.length > budget) line = escape(raw.take(budget / 2)) + "…"
            val current = chunks.lastOrNull()
            if (omitted > 0) {
                omitted++
            } else if (current != null && current.length + 1 + line.length <= budget) {
                current.append('\n').append(line)
            } else if (chunks.size < maxMessages) {
                chunks += StringBuilder(line)
            } else {
                omitted++
            }
        }
        val header = buildString {
            append("<b>").append(escape(caption(version, text, onlyProblems))).append("</b>")
            if (chunks.size > 1) append(" · ${chunks.size} messages")
            if (omitted > 0) append("\n$omitted older lines left out")
        }
        return listOf(header) + chunks.map { "<pre>$it</pre>" }
    }

    private const val MESSAGE_LIMIT = 4096

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** "Snapshot wuzzuf · 182 KB · fixture for ParsersTest". */
    fun snapshotCaption(name: String, bytes: Int): String {
        val source = Regex("""^(.+)-\d{8}-\d{4}\.\w+$""").find(name)?.groupValues?.get(1) ?: name
        return "Snapshot $source · ${maxOf(1, bytes / 1024)} KB · fixture for ParsersTest"
    }
}
