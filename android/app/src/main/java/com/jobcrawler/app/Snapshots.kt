package com.jobcrawler.app

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The page a source got when it parsed nothing from a good answer: usually
 * the site changed its markup. Kept on the phone, the newest [KEEP] per
 * source, so it can be sent from the log screen and become a parser test.
 */
object Snapshots {
    const val KEEP = 3
    const val MAX_CHARS = 300_000
    const val DIR = "snapshots"

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.of("Africa/Cairo"))

    /** "wuzzuf-20261102-0847.html"; ".json" when the body is JSON. */
    fun fileName(key: String, at: Instant, body: String): String {
        val trimmed = body.trimStart()
        val ext = if (trimmed.startsWith("{") || trimmed.startsWith("[")) "json" else "html"
        return "$key-${stamp.format(at)}.$ext"
    }

    /** The files of [key] past the newest [keep]; other sources' files are never touched. */
    fun toDelete(names: List<String>, key: String, keep: Int = KEEP): List<String> =
        names.filter { owner(it) == key }.sortedDescending().drop(keep)

    /** The source a file belongs to: the name up to its date. */
    private fun owner(name: String): String? = Regex("""^(.+)-\d{8}-\d{4}\.(html|json)$""").find(name)?.groupValues?.get(1)

    fun save(dir: File, key: String, body: String, at: Instant = Instant.now()): File {
        dir.mkdirs()
        val file = File(dir, fileName(key, at, body))
        file.writeText(body.take(MAX_CHARS))
        val names = dir.list()?.toList().orEmpty()
        for (old in toDelete(names, key)) File(dir, old).delete()
        return file
    }

    /** Newest first. */
    fun list(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
}
