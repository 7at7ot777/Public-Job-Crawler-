package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.BuildConfig
import com.jobcrawler.app.Dates
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store
import okhttp3.Credentials
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Three job search APIs with a free key, for what the remote feeds cannot
 * give: on-site jobs with a visa in the UK, Germany, the Netherlands,
 * Ireland, Australia and Canada. Each runs only once its key is in .env
 * (ADZUNA_APP_ID + ADZUNA_APP_KEY, JOOBLE_API_KEY, CAREERJET_API_KEY), twice a
 * day. They return a title and a snippet, not the whole ad, so a job is
 * judged on those by [RemoteFeeds.judge].
 */
class JobApis(
    override val client: GuestClient = GuestClient("Job APIs", headers = mapOf("Accept" to "application/json")),
    private val keys: Keys = Keys.fromBuild(),
    private val cadence: Cadence = Cadence(KEY, EVERY_MS),
) : Source {
    override val key = KEY

    override val autoEmail: Boolean = true

    override fun isDue(store: Store) = keys.any && cadence.isDue(store)

    /* Careerjet's v4 API takes the key as basic auth (user = key, empty password); one refusal ends it for the run. */
    private val careerjetClient = GuestClient(
        "Careerjet",
        headers = mapOf("Accept" to "application/json", "Authorization" to Credentials.basic(keys.careerjet, "")),
        throttleBudget = 1,
    )

    override val clients: List<GuestClient> get() = listOf(client, careerjetClient)

    data class Keys(val adzunaId: String, val adzunaKey: String, val jooble: String, val careerjet: String) {
        val adzuna get() = adzunaId.isNotBlank() && adzunaKey.isNotBlank()
        val any get() = adzuna || jooble.isNotBlank() || careerjet.isNotBlank()

        companion object {
            fun fromBuild() = Keys(BuildConfig.ADZUNA_APP_ID, BuildConfig.ADZUNA_APP_KEY, BuildConfig.JOOBLE_API_KEY, BuildConfig.CAREERJET_API_KEY)
        }
    }

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        cadence.started(store)
        val checked = store.checked.ids(KEY)
        val kept = mutableListOf<Item>()
        val rejected = sortedMapOf<String, Int>()
        var listed = 0
        var seen = 0
        var old = 0

        fun take(jobs: List<RemoteFeeds.Job>) {
            listed += jobs.size
            for (job in jobs) {
                val id = "${job.feed}:${job.id}"
                if (id in checked) { seen += 1; continue }
                if (Dates.isOlderThan(job.postedAt, MAX_AGE_DAYS)) { old += 1; continue }
                store.checked.mark(KEY, id)
                RemoteFeeds.tally(RemoteFeeds.judge(job, KEY), kept, rejected)
            }
        }

        // An API that refuses (a bad key, an IP it does not allow) is left alone for the rest of the run.
        val refused = mutableSetOf<String>()

        fun guarded(api: String, name: String, call: () -> List<RemoteFeeds.Job>) {
            if (api in refused) return
            try {
                take(call())
            } catch (error: BlockedException) {
                if (client.isStopped() || careerjetClient.isStopped()) throw error
                if (error.transport) {
                    Log.warn("Job APIs: $name unreachable: ${error.message}")
                } else {
                    refused += api
                    Log.warn("Job APIs: $api refused the request, skipped this run: ${error.message}" +
                        if (api == "Careerjet") " (Careerjet only answers IPs allowed in the publisher account)" else "")
                }
            } catch (error: Exception) {
                Log.warn("Job APIs: $name did not parse: ${error.javaClass.simpleName}: ${error.message}")
            }
        }

        try {
            for (query in QUERIES) {
                if (client.isStopped()) break
                if (keys.adzuna) for ((code, country) in ADZUNA_COUNTRIES) guarded("Adzuna", "Adzuna $code") {
                    adzuna(client.get(adzunaUrl(code), adzunaParams(keys, query)), country)
                }
                if (keys.jooble.isNotBlank()) for (country in COUNTRIES) guarded("Jooble", "Jooble $country") {
                    jooble(client.postJson("https://jooble.org/api/${keys.jooble}", joobleBody(query, country)))
                }
                if (keys.careerjet.isNotBlank()) for (locale in CAREERJET_LOCALES.keys) guarded("Careerjet", "Careerjet $locale") {
                    careerjet(careerjetClient.get(CAREERJET_URL, careerjetParams(query, locale)))
                }
            }
        } catch (error: BlockedException) {
            error.partialItems = kept.toList()
            throw error
        } finally {
            Log.info(RemoteFeeds.summary("Job APIs", RemoteFeeds.Companion.Counts(listed, seen, old, listed - seen - old), kept.size, rejected))
        }
        return kept
    }

    companion object {
        const val KEY = "job_apis"
        private const val EVERY_MS = 12 * 60 * 60_000L
        private const val MAX_AGE_DAYS = 7

        /* The CV's stack, each with the two ways a job abroad can be taken from Cairo. */
        val QUERIES = listOf("Laravel", "PHP", "Node.js", "Backend Developer").flatMap { listOf("$it visa sponsorship", "$it remote") }

        val ADZUNA_COUNTRIES = linkedMapOf(
            "gb" to "United Kingdom", "de" to "Germany", "nl" to "Netherlands", "au" to "Australia", "ca" to "Canada",
        )
        val COUNTRIES = listOf("United Kingdom", "Germany", "Netherlands", "Ireland", "Australia", "Canada")
        val CAREERJET_LOCALES = mapOf("en_GB" to "United Kingdom", "en_IE" to "Ireland", "en_AU" to "Australia", "en_CA" to "Canada")
        private const val CAREERJET_URL = "https://search.api.careerjet.net/v4/query"

        fun adzunaUrl(country: String) = "https://api.adzuna.com/v1/api/jobs/$country/search/1"

        fun adzunaParams(keys: Keys, query: String): Map<String, Any?> = mapOf(
            "app_id" to keys.adzunaId,
            "app_key" to keys.adzunaKey,
            "what" to query,
            "max_days_old" to MAX_AGE_DAYS,
            "results_per_page" to 20,
            "content-type" to "application/json",
        )

        fun joobleBody(query: String, country: String): String = JSONObject().put("keywords", query).put("location", country).toString()

        /** Careerjet v4: the key goes in the Authorization header, not here. */
        fun careerjetParams(query: String, locale: String): Map<String, Any?> = mapOf(
            "keywords" to query,
            "locale_code" to locale,
            "page_size" to 20,
            "sort" to "date",
            "fragment_size" to 500,
            // Careerjet asks who is searching; a phone app has no visitor, so the app says so.
            "user_ip" to "127.0.0.1",
            "user_agent" to "JobCrawler Android",
        )

        private fun clean(value: String?): String? = Html.clean(value)?.takeIf { it != "null" }

        /** A remote job's place says so, so the lane check sees it. */
        private fun remoteIn(vararg text: String?) = text.any { it?.contains("remote", ignoreCase = true) == true }

        fun adzuna(body: String, country: String): List<RemoteFeeds.Job> =
            RemoteFeeds.objects(JSONObject(body).optJSONArray("results")).mapNotNull { job ->
                val title = clean(Html.detag(job.optString("title"))) ?: return@mapNotNull null
                val description = Html.strip(job.optString("description"))
                val place = clean(job.optJSONObject("location")?.optString("display_name"))
                RemoteFeeds.Job(
                    feed = "adzuna",
                    id = clean(job.optString("id")) ?: return@mapNotNull null,
                    title = title,
                    company = clean(job.optJSONObject("company")?.optString("display_name")),
                    url = clean(job.optString("redirect_url")) ?: return@mapNotNull null,
                    location = listOfNotNull(place, country.takeIf { place?.contains(it, ignoreCase = true) != true }).joinToString(", "),
                    restriction = null,
                    description = description,
                    postedAt = Dates.absoluteToIso(job.optString("created")),
                    remote = remoteIn(title, description),
                )
            }

        /** Jooble's "2026-09-22T00:00:00.0000000": seven fraction digits and no zone, read as UTC. */
        fun joobleDate(value: String?): String? {
            val text = value?.trim()?.takeIf { it.length >= 19 } ?: return null
            return Dates.absoluteToIso(text.substring(0, 19) + "Z")
        }

        private val careerjetFormat = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)

        /** Careerjet's "Wed,15 Nov 2025 19:13:43 GMT": the weekday dropped (it may not match the date), the rest read as is. */
        fun careerjetDate(value: String?): String? {
            val text = value?.trim()?.replace(Regex("""^[A-Za-z]{3},\s*"""), "")?.ifEmpty { null } ?: return null
            return runCatching { ZonedDateTime.parse(text, careerjetFormat).toInstant().toString() }.getOrNull()
        }

        fun jooble(body: String): List<RemoteFeeds.Job> =
            RemoteFeeds.objects(JSONObject(body).optJSONArray("jobs")).mapNotNull { job ->
                val title = clean(Html.detag(job.optString("title"))) ?: return@mapNotNull null
                val description = Html.strip(job.optString("snippet"))
                RemoteFeeds.Job(
                    feed = "jooble",
                    id = clean(job.optString("id")) ?: return@mapNotNull null,
                    title = title,
                    company = clean(job.optString("company")),
                    url = clean(job.optString("link")) ?: return@mapNotNull null,
                    location = clean(job.optString("location")),
                    restriction = null,
                    description = description,
                    postedAt = joobleDate(job.optString("updated")),
                    remote = remoteIn(title, description, job.optString("location")),
                )
            }

        fun careerjet(body: String): List<RemoteFeeds.Job> =
            RemoteFeeds.objects(JSONObject(body).optJSONArray("jobs")).mapNotNull { job ->
                val url = clean(job.optString("url")) ?: return@mapNotNull null
                val title = clean(Html.detag(job.optString("title"))) ?: return@mapNotNull null
                val description = Html.strip(job.optString("description"))
                RemoteFeeds.Job(
                    feed = "careerjet",
                    id = url.hashCode().toUInt().toString(),
                    title = title,
                    company = clean(job.optString("company")),
                    url = url,
                    location = clean(job.optString("locations")),
                    restriction = null,
                    description = description,
                    postedAt = careerjetDate(job.optString("date")),
                    remote = remoteIn(title, description, job.optString("locations")),
                )
            }
    }
}
