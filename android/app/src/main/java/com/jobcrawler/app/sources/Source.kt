package com.jobcrawler.app.sources

import com.jobcrawler.app.Alert
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Store
import com.jobcrawler.app.StateStore
import com.jobcrawler.app.Limits

/**
 * A source is a key and a fetch. The pipeline never names a concrete one:
 * filtering, storage, dedup and delivery apply to every source unchanged.
 */
interface Source {
    val key: String

    /** Where this source's jobs can be: Egypt, the Gulf, worldwide (Settings groups by [Region.home]). */
    val regions: Set<com.jobcrawler.app.Region> get() = setOf(com.jobcrawler.app.Region.home(key))

    /** The paced lane(s) this source talks through, so the pipeline can bound them. */
    val client: GuestClient

    /** Every lane, for a source that talks to more than one remote. */
    val clients: List<GuestClient> get() = listOf(client)

    /**
     * False when the source has nothing to do this run — a once-a-day source
     * that has had its day. The pipeline then skips it without a log section.
     */
    fun isDue(store: Store): Boolean = true

    /**
     * True for jobs outside Egypt: never posted to the channel, only emailed
     * a CV on their own (Mailer.autoSend) when they give an address.
     */
    val emailOnly: Boolean get() = false

    /**
     * New jobs that give an address are emailed the CV on their own
     * (Mailer.autoSend), whether or not they are also posted. Every
     * [emailOnly] source is; a posted one opts in.
     */
    val autoEmail: Boolean get() = emailOnly

    /** @throws com.jobcrawler.app.BlockedException carrying what was parsed before the block. */
    fun fetch(keywords: List<String>, store: Store): List<Item>

    /**
     * How many jobs the remote listed this run, before the source's own
     * checks (seen before, already opened, too old, wrong title) dropped
     * any; null when the source does not say. A clean run that hands back
     * nothing but listed some is not a broken parser: it keeps no snapshot
     * and raises no alert ([com.jobcrawler.app.Breakage]). A source without
     * it is judged on what [fetch] hands back alone.
     *
     * A source opts in with `override val listed = ListedCount()` and calls
     * `listed.add(n)` as it reads each list. The pipeline resets it before
     * every [fetch] and reads it after, even when fetch throws, so an
     * instance kept across runs never carries a count over.
     */
    val listed: ListedCount? get() = null

    /** Warnings that did not stop the run (a low Serper balance); the pipeline sends them. */
    fun takeAlerts(): List<Alert> = emptyList()
}

/** What a source's remote listed in one run ([Source.listed]); reset by the pipeline before each fetch. */
class ListedCount {
    /** Null until the source reports a list, even an empty one. */
    var count: Int? = null
        private set

    fun add(n: Int) {
        count = (count ?: 0) + n
    }

    fun reset() {
        count = null
    }
}

/**
 * A source that runs less often than the schedule: due once [everyMs] has
 * passed since it last started. The start is recorded, not the finish, so a
 * run cut short waits its turn like any other instead of repeating at once.
 */
class Cadence(
    private val key: String,
    private val everyMs: Long,
    /*
     * Each source has its own WorkManager schedule at about this period, and
     * WorkManager may fire a few minutes early: without slack, that firing
     * finds the source not yet due and it waits a whole extra period.
     */
    private val slackMs: Long = Limits.CADENCE_SLACK_MS,
) {
    fun isDue(store: StateStore, now: Long = System.currentTimeMillis()): Boolean {
        val last = store.getState("$key.lastRun")?.toLongOrNull() ?: return true
        // A clock set back must not stall the source for good.
        return now - last >= everyMs - minOf(slackMs, everyMs / 2) || now < last
    }

    fun started(store: StateStore, now: Long = System.currentTimeMillis()) = store.setState("$key.lastRun", now.toString())

    /** The last run's start as stored, for [restore]. */
    fun last(store: StateStore): String? = store.getState("$key.lastRun")

    /**
     * Hands a turn back: a run the network cut short is not a run, and its
     * retry must find the source due rather than hours away.
     */
    fun restore(store: StateStore, previous: String?) = store.setState("$key.lastRun", previous ?: "0")
}

/**
 * Walks a list longer than one run's slice of time — company boards, career
 * sites — starting where the last run stopped.
 *
 * The position moves past an entry before it is visited, not after: an entry
 * that always times out or is always refused is then passed over on the next
 * lap instead of being the one every run starts on, and never finishes.
 */
class Rotation(private val store: Store, private val key: String) {
    fun <T> walk(entries: List<T>, visit: (T) -> Unit) {
        if (entries.isEmpty()) return
        val start = (store.getState(key)?.toIntOrNull() ?: 0).mod(entries.size)
        for (step in entries.indices) {
            val index = (start + step) % entries.size
            store.setState(key, ((index + 1) % entries.size).toString())
            visit(entries[index])
        }
    }
}

/** Shared by the regex-only parsers, which never build a DOM for a whole page. */
object Html {
    fun detag(fragment: String?): String? {
        if (fragment == null) return null
        val text = fragment
            .replace(Regex("<(script|style|noscript)\\b[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]*>"), " ")
        return clean(decode(text))
    }

    /** The handful of entities that actually show up in this markup. */
    fun decode(value: String?): String? {
        if (value == null) return null
        return value
            .replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
            .replace(Regex("&lt;", RegexOption.IGNORE_CASE), "<")
            .replace(Regex("&gt;", RegexOption.IGNORE_CASE), ">")
            .replace(Regex("&quot;", RegexOption.IGNORE_CASE), "\"")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: it.value }
            .replace(Regex("&#x([0-9a-f]+);", RegexOption.IGNORE_CASE)) {
                it.groupValues[1].toIntOrNull(16)?.let { c -> String(Character.toChars(c)) } ?: it.value
            }
    }

    /** Line breaks kept, tags dropped: for descriptions that arrive as HTML in JSON. */
    fun strip(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return (decode(
            value
                .replace(Regex("<\\s*(br|/p|/li|/h\\d)\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), " "),
        ) ?: "")
            .replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\s*\\n\\s*"), "\n")
            .trim()
    }

    fun clean(value: String?): String? {
        val text = (value ?: "").replace(Regex("\\s+"), " ").trim()
        return text.ifEmpty { null }
    }
}
