package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Criteria
import com.jobcrawler.app.Filters
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Limits
import com.jobcrawler.app.LinkedInRest
import com.jobcrawler.app.Log
import com.jobcrawler.app.StateStore
import com.jobcrawler.app.Store
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * LinkedIn's public guest job search, every keyword x location pair. Only the
 * logged-out endpoints: no credentials, no cookies, no account.
 *
 * Scheduled runs rotate through the places: each run searches every keyword
 * in ONE place, the next run the next place (see [rotate]). A run costs the
 * same requests however many places there are; a full cycle takes one run per
 * place. Each place keeps its own last clean search, so its window reaches
 * back to it ([windowSince]) and a longer cycle never loses a job.
 *
 * The same search runs a second time as [gulf]: Saudi and UAE places, every
 * three hours over the last day, for the CV email only.
 */
class LinkedInJobs(
    override val client: GuestClient = linkedInClient(),
    override val key: String = KEY,
    private val places: Criteria.Kind = Criteria.Kind.SEARCH_PLACES,
    private val timeWindow: String? = null,
    private val cadence: Cadence? = Cadence(KEY, EVERY_MS),
    override val emailOnly: Boolean = false,
    /*
     * One place per run. Off when the run carries its own window — a run by
     * hand over the last N hours, or the Gulf search — since those want every
     * place at once.
     */
    private val rotate: Boolean = timeWindow == null,
) : Source {
    /** Not while LinkedIn rests after a 429 (see [LinkedInRest]), and not more often than [cadence]. */
    override fun isDue(store: Store) = LinkedInRest.restingUntil(store) == null && (cadence?.isDue(store) ?: true)

    private lateinit var store: Store

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        this.store = store
        LinkedInRest.restingUntil(store)?.let {
            Log.info("$key: LinkedIn rests ${LinkedInRest.describe(store, it)}; skipped, no request sent")
            return emptyList()
        }
        cadence?.started(store)
        val all = Criteria[places]
        val turn = if (rotate) rotationTurn(store, key, all.size) else null
        val searched = if (turn == null) all else listOf(all[turn])
        // One place: back to its own last clean search. Every place: back to the source's.
        val window = timeWindow ?: windowSince(lastOk(store, key, if (turn != null) searched.first() else null))

        if (turn != null) {
            Log.info("$key: ${searched.first()} (place ${turn + 1} of ${all.size}), ${keywords.size} searches, f_TPR $window")
        }

        // The same posting surfaces under several keywords.
        val seen = LinkedHashMap<String, Item>()

        try {
            for (keyword in keywords) {
                for (location in searched) {
                    searchPair(keyword, location, window, seen)
                }
            }
        } catch (error: BlockedException) {
            if (client.wasThrottled) LinkedInRest.after(client, store, key, jobSearch = true)
            // A throttled client gets nothing more: the cards found so far are judged as they are.
            error.partialItems = if (client.wasThrottled) seen.values.toList() else enrich(seen.values.toList(), store)
            throw error
        }

        val items = enrich(seen.values.toList(), store)
        if (client.wasThrottled) LinkedInRest.after(client, store, key, jobSearch = true) else {
            LinkedInRest.clean(store)
            // Only a clean run moves on: a blocked or throttled one searches the same place next time.
            recordClean(store, key, searched, turn, all.size)
        }
        return items
    }

    /** One request more against LinkedIn's hourly share, or a stop for this run when it is used up. */
    private fun get(url: String, query: Map<String, Any?> = emptyMap()): String {
        if (!LinkedInRest.take(store)) {
            throw BlockedException("LinkedIn: this hour's ${LinkedInRest.HOURLY_CAP} requests are used; the rest waits for the next run")
        }
        return client.get(url, query)
    }

    private fun searchPair(keyword: String, location: String, window: String, seen: MutableMap<String, Item>) {
        for (page in 0 until MAX_PAGES) {
            val html = get(
                SEARCH_URL,
                mapOf(
                    "keywords" to keyword,
                    "location" to location,
                    "start" to page * PAGE_SIZE,
                    // The hour before the run: a 30-minute schedule overlaps
                    // every tick, and dedup keeps the repeats out.
                    "f_TPR" to window,
                    // 1 internship, 2 entry, 3 associate, 4 mid-senior.
                    "f_E" to "1,2,3,4",
                ),
            )

            val parsed = parseCards(html, keyword, location, key)
            if (parsed.isEmpty()) return

            for (item in parsed) seen.putIfAbsent(item.externalId, item)
        }
    }

    /**
     * Open each card that could still pass for its description and level. A
     * card is only a title — not enough to judge seniority or find a contact.
     * Cards already stored are skipped: the store would discard them anyway.
     */
    private fun enrich(items: List<Item>, store: Store): List<Item> {
        // Newest first, and only a few: each page is one more request against LinkedIn's patience.
        val candidates = items.filter { Filters.couldPass(it) && store.findByExternalId(key, it.externalId) == null }
            .sortedByDescending { it.postedAt ?: "" }
            .take(MAX_DETAILS)

        for (item in candidates) {
            try {
                val (description, criteria) = parseDetail(get(DETAIL_URL + item.externalId))
                item.excerpt = description
                item.seniority = criteria["seniority level"]
                item.employmentType = criteria["employment type"]
            } catch (error: BlockedException) {
                // A block is about the client, not this page. The tail is
                // judged on its card alone; the next run's window covers it.
                Log.warn("LinkedIn detail fetches stopped at a block: ${error.message}")
                return items
            }
        }

        return items
    }

    companion object {
        const val KEY = "linkedin_jobs"
        const val GULF_KEY = "gulf_jobs"
        const val SEARCH_URL = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"
        const val DETAIL_URL = "https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/"
        private const val PAGE_SIZE = 25
        private const val MAX_PAGES = 1

        /*
         * Every 3 minutes, one place a run (9 keywords, 9 searches). WorkManager
         * will not repeat periodic work more often than every 15 minutes, so the
         * runs in between are chained one-time work (Scheduler.chainNext).
         * LinkedInRest.HOURLY_CAP still bounds the requests an hour, so a faster
         * cadence simply rests sooner once the cap is reached.
         */
        const val EVERY_MS = 3 * 60_000L

        /** Minutes between chained runs: [EVERY_MS], for the scheduler. */
        const val EVERY_MINUTES = EVERY_MS / 60_000L

        /** Job pages opened a run, newest first; the rest are judged on their card. */
        const val MAX_DETAILS = 10

        // A day, searched every three hours: dedup drops the overlap, and a gap never loses a job.
        private const val GULF_TIME_WINDOW = "r86400"

        /** LinkedIn's lane: 6-10 s between requests, the first one too, and one 429 ends the run (see [LinkedInRest]). */
        fun linkedInClient(label: String = "LinkedIn") =
            GuestClient(label, delayMs = 6_000, throttleBudget = 1, jitterMs = 4_000, paceFirst = true)

        /** The most a run by hand may ask for: a week. */
        const val MAX_HOURS = 168

        /** A run by hand that asked for the last [hours]: LinkedIn's f_TPR, in seconds. */
        fun windowForHours(hours: Int): String = "r${hours.coerceIn(1, MAX_HOURS) * 3_600}"

        /**
         * The search window: everything since the last clean run plus ten
         * minutes, between one hour and a day — so a rest after a 429 loses
         * no job, and a normal run asks for one hour. Rotating, "the last
         * clean run" is the place's own, one cycle back: 6 places at 5
         * minutes is 30 minutes, inside the hour; 12 places is 60, plus ten.
         */
        fun windowSince(lastOk: Long?, now: Long = System.currentTimeMillis()): String {
            val seconds = if (lastOk == null) 3_600L else ((now - lastOk) / 1000 + 600).coerceIn(3_600L, 86_400L)
            return "r$seconds"
        }

        /** This run's place, by index into [count] places; null when there is nothing to rotate. */
        fun rotationTurn(state: StateStore, key: String, count: Int): Int? {
            if (count < 2) return null
            val next = state.getState(rotationKey(key))?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
            return next % count
        }

        /**
         * When [location] was last searched cleanly, else the source's last
         * clean run: a place just added (or the first rotating run after an
         * update) starts from the source's window rather than from nothing.
         */
        fun lastOk(state: StateStore, key: String, location: String?): Long? {
            val own = location?.let { state.getState(lastOkKey(key, it))?.toLongOrNull() }
            return own ?: state.getState("$key.lastOk")?.toLongOrNull()
        }

        /** A clean run: the places it searched are fresh, and the rotation moves to the next one. */
        fun recordClean(
            state: StateStore,
            key: String,
            searched: List<String>,
            turn: Int?,
            count: Int,
            now: Long = System.currentTimeMillis(),
        ) {
            state.setState("$key.lastOk", now.toString())
            for (location in searched) state.setState(lastOkKey(key, location), now.toString())
            if (turn != null && count > 0) state.setState(rotationKey(key), ((turn + 1) % count).toString())
        }

        private fun lastOkKey(key: String, location: String) = "$key.lastOk.${location.trim().lowercase()}"

        private fun rotationKey(key: String) = "$key.rotationNext"

        fun gulf() = LinkedInJobs(
            key = GULF_KEY,
            places = Criteria.Kind.GULF_SEARCH_PLACES,
            timeWindow = GULF_TIME_WINDOW,
            cadence = Cadence(GULF_KEY, Limits.SLOW_SOURCE_EVERY_MS),
            emailOnly = true,
        )

        /** The search fragment: a list of <li> cards, padded with promos. */
        fun parseCards(html: String, keyword: String?, market: String?, source: String = KEY): List<Item> {
            if (html.isBlank()) return emptyList()

            val doc = Jsoup.parseBodyFragment("<ul>$html</ul>")
            return doc.select("li").mapNotNull { card ->
                runCatching { parseCard(card, keyword, market, source) }.getOrNull()
            }
        }

        private fun parseCard(card: Element, keyword: String?, market: String?, source: String): Item? {
            val externalId = externalIdOf(card) ?: return null
            val url = attr(card, "a.base-card__full-link", "href") ?: attr(card, "a[href*=/jobs/view/]", "href") ?: return null
            val title = text(card, ".base-search-card__title") ?: text(card, ".sr-only") ?: return null

            return Item(
                source = source,
                externalId = externalId,
                title = title,
                // The tracking query makes identical jobs look different.
                url = url.substringBefore('?').trim(),
                company = text(card, ".base-search-card__subtitle a") ?: text(card, ".base-search-card__subtitle"),
                location = text(card, ".job-search-card__location"),
                market = market,
                postedAt = attr(card, "time[datetime]", "datetime")?.let(::dateToIso),
                keyword = keyword,
            )
        }

        /** data-entity-urn="urn:li:jobPosting:4012345678", else the id in the URL. */
        private fun externalIdOf(card: Element): String? {
            attr(card, "[data-entity-urn]", "data-entity-urn")?.let { urn ->
                Regex("(\\d+)\\s*$").find(urn)?.let { return it.groupValues[1] }
            }
            attr(card, "a[href*=/jobs/view/]", "href")?.let { href ->
                Regex("/jobs/view/(?:[^/?]*-)?(\\d+)").find(href)?.let { return it.groupValues[1] }
            }
            return null
        }

        /** Description and the "Seniority level / Employment type" criteria. */
        fun parseDetail(html: String): Pair<String?, Map<String, String>> {
            if (html.isBlank()) return null to emptyMap()

            val doc = Jsoup.parse(html)
            val description = Html.clean(doc.selectFirst(".description__text, .show-more-less-html__markup")?.text())

            val criteria = buildMap {
                for (row in doc.select(".description__job-criteria-item")) {
                    val label = Html.clean(row.selectFirst(".description__job-criteria-subheader")?.text())?.lowercase()
                    val value = Html.clean(row.selectFirst(".description__job-criteria-text")?.text())
                    if (label != null && value != null) put(label, value)
                }
            }

            return description to criteria
        }

        private fun dateToIso(value: String): String? =
            runCatching { LocalDate.parse(value.trim()).atStartOfDay().toInstant(ZoneOffset.UTC).toString() }
                .recoverCatching { Instant.parse(value.trim()).toString() }
                .getOrNull()

        private fun text(card: Element, selector: String) = Html.clean(card.selectFirst(selector)?.text())

        private fun attr(card: Element, selector: String, name: String) =
            Html.clean(card.selectFirst(selector)?.attr(name))
    }
}
