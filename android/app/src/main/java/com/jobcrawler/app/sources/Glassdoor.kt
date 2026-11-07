package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.BuildConfig
import com.jobcrawler.app.Dates
import com.jobcrawler.app.Filters
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Gulf
import com.jobcrawler.app.Item
import com.jobcrawler.app.Log
import com.jobcrawler.app.StateStore
import com.jobcrawler.app.Store
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Glassdoor job listings, found through Google (Serper), since glassdoor.com
 * itself refuses crawlers and its phone app cannot be reached from another
 * app. Only what Google shows is read — the title ("Acme hiring Backend
 * Developer Job in Cairo"), the snippet and the link; the listing page is
 * never opened.
 *
 * Two kinds of search, once a day after 07:00 Cairo, a handful of paid
 * queries counted in the same [SerperBudget] as the hiring posts:
 *  - Egypt: posted like any Egypt job, and the Egypt location rule applies
 *    (an Egyptian place has to be named);
 *  - abroad: kept only in a lane — visa sponsorship / relocation, or remote
 *    open worldwide — and naming the CV's stack; emailed the CV on its own
 *    when an address shows, from the second Gmail;
 *  - Saudi Arabia and the UAE, with the switch on: posted like Egypt's,
 *    marked with the country.
 */
class Glassdoor(
    private val apiKey: String = BuildConfig.SERPER_API_KEY,
    override val client: GuestClient = GuestClient(
        "Serper",
        headers = mapOf("X-API-KEY" to apiKey, "Accept" to "application/json"),
        delayMs = 1_000,
    ),
    private val dailyCap: Int = SerperBudget.DEFAULT_DAILY_CAP,
    /** Two more searches a day: Saudi Arabia and the UAE, posted like Egypt's. */
    private val gulf: Boolean = false,
) : Source {
    override val key = KEY
    override val regions get() = com.jobcrawler.app.Region.reach(KEY, gulf)

    /** Abroad jobs that give an address get the CV on their own; Egypt ones wait on the review screen. */
    override val autoEmail: Boolean = true

    override fun isDue(store: Store): Boolean {
        if (apiKey.isBlank()) return false
        val now = ZonedDateTime.now(CAIRO)
        return now.toLocalTime() >= START && store.getState(DAY_KEY) != now.toLocalDate().toString()
    }

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        // Spent when the run starts: a run cut short is not paid for twice.
        store.setState(DAY_KEY, ZonedDateTime.now(CAIRO).toLocalDate().toString())
        return search(store)
    }

    /** The searches themselves, against any [StateStore]. */
    fun search(store: StateStore): List<Item> {
        val budget = SerperBudget(store, dailyCap)
        val kept = LinkedHashMap<String, Item>()
        val rejected = sortedMapOf<String, Int>()

        try {
            for (query in queries(gulf)) {
                budget.spend()
                val body = client.postJson(SERPER_URL, request(query))
                if (body.isBlank()) continue
                for (result in parseSerper(body)) {
                    val judged = judge(result, query)
                    val reason = judged.rejected
                        ?: if (Dates.isOlderThan(judged.item.postedAt, MAX_AGE_DAYS)) "too-old" else null
                    if (reason != null) rejected.merge(reason, 1, Int::plus)
                    else kept.putIfAbsent(judged.item.externalId, judged.item)
                }
            }
        } catch (error: BlockedException) {
            // Its alert (out of credits, the cap) is the pipeline's to send.
            error.partialItems = kept.values.toList()
            throw error
        } finally {
            Log.info("Glassdoor: ${kept.size} listings kept | dropped $rejected")
        }
        return kept.values.toList()
    }

    enum class Scope { EGYPT, ABROAD, GULF }

    data class Query(val text: String, val scope: Scope, val country: String)

    data class Result(val url: String, val title: String?, val snippet: String?, val postedAt: String?)

    data class Judged(val item: Item, val rejected: String?)

    companion object {
        const val KEY = "glassdoor"
        private const val SERPER_URL = "https://google.serper.dev/search"
        private const val DAY_KEY = "$KEY.day"
        private val CAIRO = ZoneId.of("Africa/Cairo")
        private val START = LocalTime.of(7, 0)

        /* Google's week; a listing older than this is likely filled or reposted. */
        private const val TIME_WINDOW = "qdr:w"
        private const val MAX_AGE_DAYS = 7

        const val REMOTE_LANE = "🌍 Remote, open worldwide"
        const val VISA_LANE = "✈️ Visa sponsorship / relocation"

        // Glassdoor's country sites; Google takes an OR of site: filters.
        private const val SITES = "(site:glassdoor.com OR site:glassdoor.co.uk OR site:glassdoor.de OR site:glassdoor.ca OR site:glassdoor.nl OR site:glassdoor.ie)"
        private const val STACK = "(Laravel OR PHP OR \"Node.js\" OR NestJS OR Golang)"

        /** Eight paid searches a day: three in Egypt, five abroad (two of them Canada and Australia). */
        val QUERIES = listOf(
            Query("site:glassdoor.com inurl:job-listing (Laravel OR PHP) Egypt", Scope.EGYPT, "eg"),
            Query("site:glassdoor.com inurl:job-listing (\"Node.js\" OR NestJS OR Golang) Egypt", Scope.EGYPT, "eg"),
            Query("site:glassdoor.com inurl:job-listing \"backend developer\" Egypt", Scope.EGYPT, "eg"),
            Query("$SITES inurl:job-listing $STACK \"visa sponsorship\"", Scope.ABROAD, "gb"),
            Query("$SITES inurl:job-listing $STACK relocation package", Scope.ABROAD, "de"),
            Query("$SITES inurl:job-listing $STACK remote (worldwide OR anywhere OR EMEA)", Scope.ABROAD, "us"),
            Query("site:glassdoor.ca inurl:job-listing $STACK (\"visa sponsorship\" OR LMIA OR remote)", Scope.ABROAD, "ca"),
            Query("site:glassdoor.com.au inurl:job-listing $STACK (\"visa sponsorship\" OR 482 OR remote)", Scope.ABROAD, "au"),
        )

        private const val GULF_STACK = "(Laravel OR PHP OR \"Node.js\" OR NestJS OR Golang OR \"backend developer\")"

        /** With the Saudi/UAE switch on: one search each, under that country's `gl`. */
        val GULF_QUERIES = listOf(
            Query("site:glassdoor.com inurl:job-listing $GULF_STACK (\"Saudi Arabia\" OR Riyadh OR Jeddah)", Scope.GULF, "sa"),
            Query("site:glassdoor.com inurl:job-listing $GULF_STACK (UAE OR Dubai OR \"Abu Dhabi\")", Scope.GULF, "ae"),
        )

        fun queries(gulf: Boolean) = if (gulf) QUERIES + GULF_QUERIES else QUERIES

        fun request(query: Query): String = JSONObject()
            .put("q", query.text)
            .put("gl", query.country)
            .put("hl", "en")
            .put("num", 10)
            .put("page", 1)
            .put("tbs", TIME_WINDOW)
            .toString()

        /** A single listing, never a search page such as /Job/egypt-php-jobs-SRCH_…. */
        fun isListing(url: String) = Regex("""glassdoor\.[a-z.]+/(job-listing/|partner/jobListing)""", RegexOption.IGNORE_CASE).containsMatchIn(url)

        /** @throws BlockedException when Serper answers with anything but results: out of credits, bad key. */
        fun parseSerper(body: String): List<Result> {
            val payload = runCatching { JSONObject(body.trim()) }.getOrNull()
                ?: throw BlockedException("Serper did not return JSON: ${body.take(200).replace(Regex("\\s+"), " ")}")
            val organic = payload.optJSONArray("organic")
            if (organic == null) {
                val message = payload.optString("message")
                if (message.isNotEmpty()) throw BlockedException("Serper refused the request: $message", alert = "serper-refused")
                return emptyList()
            }
            return (0 until organic.length()).mapNotNull { i ->
                val result = organic.optJSONObject(i) ?: return@mapNotNull null
                val url = Html.clean(result.optString("link"))?.takeIf(::isListing) ?: return@mapNotNull null
                Result(
                    url = url,
                    title = Html.clean(result.optString("title")),
                    snippet = Html.clean(result.optString("snippet")),
                    postedAt = Dates.toIso(result.optString("date").ifEmpty { null }),
                )
            }
        }

        /** The listing id: ?jl=1009… on every Glassdoor site, else the listing's path. */
        fun idOf(url: String): String? =
            Regex("""[?&]jl=(\d+)""").find(url)?.groupValues?.get(1)
                ?: url.substringAfter("job-listing/", "").substringBefore('?').ifEmpty { null }

        data class Title(val role: String, val company: String?, val place: String?)

        /**
         * "Acme hiring Backend Developer Job in Cairo, Cairo | Glassdoor", or
         * "Backend Developer - Acme - Cairo | Glassdoor".
         */
        fun splitTitle(raw: String?): Title? {
            val text = raw?.replace(Regex("""\s*[|\-–]\s*Glassdoor.*$""", RegexOption.IGNORE_CASE), "")?.trim()?.ifEmpty { null } ?: return null
            Regex("""^(.+?)\s+hiring\s+(.+?)\s+Job\s+in\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.let {
                val (company, role, place) = it.destructured
                return Title(role.trim(), company.trim(), place.trim().trimEnd('.', '…'))
            }
            val parts = text.split(Regex("""\s+[-–]\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
            return Title(parts.first(), parts.getOrNull(1), parts.getOrNull(2))
        }

        /** Where an abroad listing stands; null when it is neither lane. */
        fun laneOf(text: String): String? {
            val folded = Filters.fold(text)
            val us = Filters.containsAny(folded, Jobright.US_ONLY)
            if (Filters.containsAny(folded, listOf("remote")) && Filters.containsAny(folded, Jobright.WORLDWIDE) && !us) return REMOTE_LANE
            if (Filters.containsAny(folded, VISA_WORDS)) return VISA_LANE
            return null
        }

        private val VISA_WORDS = AbroadFit.VISA_WORDS + listOf(
            "visa sponsorship", "sponsorship provided", "relocation", "relocation bonus", "skilled worker visa",
            "blue card", "work permit provided", "tier 2", "visa support",
        )

        /** One Google result as an item, and why it is dropped, if it is. */
        fun judge(result: Result, query: Query): Judged {
            val title = splitTitle(result.title)
            val text = listOfNotNull(result.title, result.snippet).joinToString("\n")
            val egypt = Filters.containsAny(Filters.fold(text), Filters.egyptPlaces)

            // A listing in Egypt found by an abroad search is still an Egypt one, and the reverse is dropped.
            // A Gulf search keeps only listings in its Gulf country, marked with it like Egypt's board jobs.
            val gulf = if (egypt || query.scope != Scope.GULF) null else Gulf.countryOf(text)
            val lane = if (egypt || query.scope == Scope.GULF) null else laneOf(text)
            val stack = CvStack.of(text)

            val item = Item(
                source = KEY,
                externalId = idOf(result.url) ?: result.url,
                title = title?.role ?: "Glassdoor listing",
                url = result.url,
                company = title?.company,
                location = title?.place,
                // An Egypt listing keeps no market, so the Egypt location rule reads its words.
                market = gulf ?: lane,
                postedAt = result.postedAt,
                keyword = stack.joinToString(", ").ifEmpty { null },
                excerpt = listOfNotNull(lane, result.snippet).joinToString("\n").ifEmpty { null },
                remote = lane == REMOTE_LANE,
                board = "Glassdoor, found on Google",
            )

            val rejected = when {
                title == null -> "no-title"
                query.scope == Scope.EGYPT && !egypt -> "not-egypt"
                query.scope == Scope.GULF && !egypt && gulf == null -> "not-gulf"
                gulf != null -> null
                !egypt && lane == null -> "no-lane"
                !egypt && stack.isEmpty() -> "stack"
                // Another language, or only for people already there.
                !egypt -> AbroadFit.blocked(text)
                else -> null
            }
            return Judged(item, rejected)
        }
    }
}
