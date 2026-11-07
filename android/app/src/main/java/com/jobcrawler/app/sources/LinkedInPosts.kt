package com.jobcrawler.app.sources

import com.jobcrawler.app.Alert
import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.BuildConfig
import com.jobcrawler.app.Criteria
import com.jobcrawler.app.Dates
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Gulf
import com.jobcrawler.app.Item
import com.jobcrawler.app.LinkedInRest
import com.jobcrawler.app.Log
import com.jobcrawler.app.StateStore
import com.jobcrawler.app.Store
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.math.BigInteger
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Hiring posts — "we're looking for a Laravel dev, DM me" — that never become
 * job listings. Ported from src/sources/linkedinPosts.js, Serper engine only.
 *
 * LinkedIn has no logged-out post search, but it lets Google index public
 * posts: Serper (Google behind a JSON API, paid per query) finds the
 * permalinks, and LinkedIn serves them.
 *
 * Every query is a paid search, so this runs once a day, on the first run
 * after 07:00 Cairo — as the server does — and not on every run. The day is
 * spent when the run starts, so a run cut short is not repeated and paid for
 * twice. [SerperBudget] caps the searches a day on top of that, and a query
 * that keeps finding nothing is asked every few days instead ([YieldLedger]).
 *
 * [gulf] is the same search over Saudi and UAE places, for the CV email only.
 * It is never capped and never skips a quiet search: it is counted in the
 * day's Serper total, but nothing stops it.
 */
class LinkedInPosts(
    private val apiKey: String = BuildConfig.SERPER_API_KEY,
    override val client: GuestClient = GuestClient(
        "Serper",
        headers = mapOf("X-API-KEY" to apiKey, "Accept" to "application/json"),
        delayMs = 1_000,
    ),
    // Post pages are linkedin.com too: the same pace and rest as the job search.
    private val postClient: GuestClient = LinkedInJobs.linkedInClient("LinkedIn posts"),
    private val dailyCap: Int = SerperBudget.DEFAULT_DAILY_CAP,
    private val grouped: Boolean = false,
    override val key: String = KEY,
    private val places: Criteria.Kind = Criteria.Kind.POST_PLACES,
    override val emailOnly: Boolean = false,
    /** Ask a search that keeps finding nothing every few days, not daily. */
    private val skipQuiet: Boolean = true,
) : Source {
    private val dayKey = "$key.day"

    override val clients: List<GuestClient> get() = listOf(client, postClient)

    private val alerts = mutableListOf<Alert>()

    override fun takeAlerts(): List<Alert> = alerts.toList().also { alerts.clear() }

    override fun isDue(store: Store): Boolean {
        if (apiKey.isBlank()) return false
        val now = ZonedDateTime.now(CAIRO)
        return now.toLocalTime() >= START && store.getState(dayKey) != now.toLocalDate().toString()
    }

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        store.setState(dayKey, ZonedDateTime.now(CAIRO).toLocalDate().toString())
        return search(store)
    }

    /** The search itself, against any [StateStore]: the unit tests use a map. */
    fun search(store: StateStore): List<Item> {
        val budget = SerperBudget(store, dailyCap)
        val ledger = YieldLedger(store, key, coldAfter = if (skipQuiet) SearchPlan.COLD_AFTER else 0)

        budget.dailyBalanceAlert { balance() }?.let { alerts += it }

        val planned = SearchPlan.plan(
            SearchPlan.TEMPLATE,
            Criteria[Criteria.Kind.POST_KEYWORDS],
            Criteria[places],
            grouped,
        )
        val due = planned.filter { ledger.isDue(it) }
        if (due.size < planned.size) {
            Log.info("LinkedIn posts: ${planned.size - due.size} quiet searches skipped today; asking ${due.size}")
        }

        // The same post surfaces under several queries.
        val seen = LinkedHashMap<String, Item>()

        try {
            for (query in due) {
                budget.spend()
                // A grouped query's places share a market: the lists are one country each.
                val body = client.postJson(SERPER_URL, request(query.query, countryOf(query.places.first())))

                var fresh = 0
                if (body.isNotBlank()) {
                    for (result in parseSerper(body)) {
                        val item = toItem(result, query.keyword, SearchPlan.placeFor(query, result.title, result.snippet), key)
                            ?: continue
                        if (Dates.isOlderThan(item.postedAt, MAX_AGE_DAYS)) continue
                        fresh += 1
                        seen.putIfAbsent(item.externalId, item)
                    }
                }
                ledger.record(query, fresh)
            }
        } catch (error: BlockedException) {
            error.partialItems = seen.values.toList()
            throw error
        }

        val resting = LinkedInRest.restingUntil(store)
        if (resting != null) {
            // The search was Serper's; only opening the posts is LinkedIn. Their snippets are judged instead.
            Log.info("LinkedIn posts: ${seen.size} posts found; LinkedIn rests ${LinkedInRest.describe(store, resting)}, judged on their snippets")
        } else {
            Log.info("LinkedIn posts: ${seen.size} posts found; opening up to $MAX_DETAILS")
            enrich(seen.values.take(MAX_DETAILS), store)
        }
        return seen.values.toList()
    }

    /** Credits left, from Serper's account endpoint (free), or null. */
    private fun balance(): Int? {
        val body = client.get(SerperBudget.ACCOUNT_URL)
        if (body.isBlank()) return null
        val json = JSONObject(body.trim())
        val value = if (json.has("balance")) json.opt("balance") else json.opt("credits")
        return (value as? Number)?.toInt() ?: value?.toString()?.toDoubleOrNull()?.toInt()
    }

    /**
     * The permalink's text replaces the search snippet, so the filters read
     * the real post. A walled page keeps the snippet; out of time keeps what
     * has been read so far.
     */
    private fun enrich(items: List<Item>, store: StateStore) {
        for (item in items) {
            if (!LinkedInRest.take(store)) {
                Log.info("LinkedIn posts: this hour's LinkedIn requests are used; the rest are judged on their snippets")
                return
            }
            val html = try {
                postClient.get(item.url)
            } catch (error: BlockedException) {
                if (error.transport) continue
                // A post page's 403/999 is a login wall, not the IP's rate limit: only a 429 rests the job search.
                if (postClient.wasThrottled) LinkedInRest.after(postClient, store, "LinkedIn posts", jobSearch = false)
                else Log.warn("LinkedIn posts: stopped opening posts: ${error.message}")
                return
            }

            val post = parsePost(html)
            if (post.text != null) {
                item.excerpt = post.text
                item.title = truncate(post.text.lineSequence().first().trim().ifEmpty { item.title }, TITLE_MAX)
            }
            item.company = post.author ?: item.company
            // The activity id's timestamp is exact; the page's date may be a bare day.
            item.postedAt = activityDate(item.url) ?: post.postedAt ?: item.postedAt
        }
    }

    data class Result(val url: String, val title: String?, val snippet: String?, val postedAt: String?)
    data class Post(val author: String?, val text: String?, val postedAt: String?)

    companion object {
        const val KEY = "linkedin_posts"
        const val GULF_KEY = "gulf_posts"
        private const val SERPER_URL = "https://google.serper.dev/search"
        private val CAIRO = ZoneId.of("Africa/Cairo")
        private val START = LocalTime.of(7, 0)

        /*
         * One search a day per keyword × place: 10 × 3 = 30, or 10 grouped.
         * Measured 2026-10-29 over a week: Egypt and Cairo found 21 of 22
         * fresh posts and Giza the last; district names (Maadi, Dokki, New
         * Cairo…) added nothing, so their queries went to keywords that do
         * find posts. Golang, NestJS, MERN and "Application Support" found none.
         */
        val KEYWORDS = listOf(
            "Laravel", "Full Stack", "Backend", "Node.js", "Software Engineer",
            "PHP", "Back End", "Web Developer", "Software Developer", "Fullstack",
        )
        val LOCATIONS = listOf("Egypt", "Cairo", "Giza")

        // Each is one more paid query per keyword a day: 40.
        val GULF_LOCATIONS = listOf("Saudi Arabia", "Riyadh", "UAE", "Dubai")

        /*
         * Saudi and UAE: no daily cap and no quiet-search skipping, and always
         * one search per place — a grouped query would mix two markets under
         * one `gl`.
         */
        fun gulf() = LinkedInPosts(
            key = GULF_KEY,
            places = Criteria.Kind.GULF_POST_PLACES,
            emailOnly = true,
            dailyCap = 0,
            grouped = false,
            skipQuiet = false,
        )

        /** The Google market a place belongs to: `gl` scopes results far more than the place in the query. */
        fun countryOf(place: String): String = when (Gulf.countryOf(place)) {
            Gulf.UAE -> "ae"
            Gulf.SAUDI -> "sa"
            else -> "eg"
        }

        /*
         * Google's own week, and a three-day cut on the post's own date: Google
         * indexes a post two or three days after it is written, and dates a
         * result by when it indexed it.
         */
        private const val TIME_WINDOW = "qdr:w"
        private const val MAX_AGE_DAYS = 3
        private const val MAX_DETAILS = 20
        private const val TITLE_MAX = 140

        fun request(query: String, country: String = "eg"): String = JSONObject()
            .put("q", query)
            // `gl` is what scopes results to a market; the place in the query only biases them.
            .put("gl", country)
            .put("hl", "en")
            .put("num", 10)
            .put("page", 1)
            .put("tbs", TIME_WINDOW)
            .toString()

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
                val url = Html.clean(result.optString("link")) ?: return@mapNotNull null
                if (!isPostUrl(url)) return@mapNotNull null
                Result(
                    url = url,
                    title = Html.clean(result.optString("title")),
                    snippet = Html.clean(result.optString("snippet")),
                    // "3 days ago" or "5 Mar 2026", depending on age.
                    postedAt = Dates.toIso(result.optString("date").ifEmpty { null }),
                )
            }
        }

        fun toItem(result: Result, keyword: String, location: String?, source: String = KEY): Item? {
            val externalId = externalIdOf(result.url) ?: return null
            return Item(
                source = source,
                externalId = externalId,
                itemType = "post",
                title = truncate(stripAuthorPrefix(result.title) ?: result.title ?: "LinkedIn post", TITLE_MAX),
                url = result.url.substringBefore('?').trim(),
                // The author is who to approach; the message prints company as that for a post.
                company = authorFromOgTitle(result.title) ?: authorFromUrl(result.url),
                // The place searched, for the message only: a post has no
                // location of its own, so market stays unset and the filters
                // read the post's words.
                location = location,
                keyword = keyword,
                excerpt = result.snippet,
                // The activity id first: the engine's date is often a bare day,
                // which parses as midnight UTC and showed every post at 03:00.
                postedAt = activityDate(result.url) ?: result.postedAt,
            )
        }

        /** Only post permalinks: profile and company pages match `site:` too. */
        fun isPostUrl(url: String) = Regex("linkedin\\.com/(posts/|feed/update/)", RegexOption.IGNORE_CASE).containsMatchIn(url)

        /** The activity id, which both URL shapes of one post share. */
        fun externalIdOf(url: String): String? =
            Regex("activity[:-](\\d{10,25})", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
                ?: Regex("linkedin\\.com/posts/([^/?#]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)

        /** An activity id is a snowflake: its top bits are the creation time in ms. */
        fun activityDate(url: String): String? {
            val id = Regex("activity[:-](\\d{10,25})", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1) ?: return null
            val ms = BigInteger(id).shiftRight(22).toLong()
            return if (ms <= 0) null else Instant.ofEpochMilli(ms).toString()
        }

        /** /posts/mohamed-elsayed-1a2b3c_… -> "Mohamed Elsayed". */
        fun authorFromUrl(url: String): String? {
            val slug = Regex("linkedin\\.com/posts/([^/?#_]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1) ?: return null
            return slug.split('-')
                .filter { it.isNotEmpty() && !Regex("^[0-9a-f]{6,}$", RegexOption.IGNORE_CASE).matches(it) }
                .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
                .trim()
                .ifEmpty { null }
        }

        /** "<Author> on LinkedIn: <post>". */
        fun authorFromOgTitle(title: String?): String? =
            title?.let { Regex("^(.*?)\\s+on\\s+LinkedIn\\b", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim() }
                ?.ifEmpty { null }

        fun stripAuthorPrefix(title: String?): String? {
            if (title == null) return null
            val match = Regex("^.*?\\s+on\\s+LinkedIn:\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(title)
            return (match?.groupValues?.get(1) ?: title).trim().ifEmpty { null }
        }

        /**
         * Author, body and time off a public permalink. A walled render still
         * carries the og: tags, which are often all there is.
         */
        fun parsePost(html: String): Post {
            if (html.isBlank()) return Post(null, null, null)
            val doc = Jsoup.parse(html)

            val ogTitle = meta(doc, "og:title")
            val body = text(doc, ".attributed-text-segment-list__content")
                ?: text(doc, ".feed-shared-update-v2__description")
                ?: text(doc, ".share-update-card__update-text")
                ?: meta(doc, "og:description")
            val author = text(doc, ".base-main-feed-card__entity-lockup .update-components-actor__title")
                ?: text(doc, ".feed-shared-actor__name")
                ?: text(doc, ".share-update-card__actor-text")
                ?: authorFromOgTitle(ogTitle)
            val published = meta(doc, "article:published_time") ?: doc.selectFirst("time[datetime]")?.attr("datetime")

            return Post(author, body, Dates.absoluteToIso(published))
        }

        private fun meta(doc: Document, property: String): String? =
            Html.clean(doc.selectFirst("meta[property=\"$property\"], meta[name=\"$property\"]")?.attr("content"))

        private fun text(doc: Document, selector: String): String? = Html.clean(doc.selectFirst(selector)?.text())

        private fun truncate(value: String, max: Int): String {
            val text = value.trim()
            return if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
        }
    }
}
