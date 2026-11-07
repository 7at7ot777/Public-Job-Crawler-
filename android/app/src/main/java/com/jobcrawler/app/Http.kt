package com.jobcrawler.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One paced lane to one remote, ported from src/sources/linkedinClient.js.
 *
 * Turns the ways a site says "stop" (429, 403, LinkedIn's 999) into one
 * exception, backs off exponentially, and slows every later request after a
 * throttle — a throttle is about this client, not the URL that earned it.
 *
 * [deadline] is the source's slice of the worker's ten minutes: past it, the
 * next request throws a block, and the source hands back what it has.
 */
class GuestClient(
    private val label: String,
    private val timeoutMs: Long = Limits.TIMEOUT_MS,
    private val headers: Map<String, String> = emptyMap(),
    private val delayMs: Long = Limits.DELAY_MS,
    /** Throttles tolerated in one run before the client gives up; LinkedIn's is 1, see [LinkedInRest]. */
    private val throttleBudget: Int = Limits.THROTTLE_BUDGET,
    /** Up to this much more, at random, on every wait: a fixed beat looks like a bot. */
    private val jitterMs: Long = 0,
    /** Wait before the first request of a run too, not only between requests. */
    private val paceFirst: Boolean = false,
) {
    var deadline: Long = Long.MAX_VALUE
    var isStopped: () -> Boolean = { false }

    /** The run this client works for: a lost network is that run's, not every worker's. */
    var run: RunContext = RunContext()

    /** Whether the site said "stop" (429, 403, 999) during this run. */
    val wasThrottled: Boolean get() = throttleEvents > 0

    /** The status of the last "stop" (429, 403 or 999), or null when there was none this run. */
    var throttleStatus: Int? = null
        private set

    private var intervalMs = delayMs
    private var cooldownUntil = 0L
    private var throttleEvents = 0
    private var cleanStreak = 0
    private var firstRequest = true

    /** Status and size of the last answer, for diagnosing a page that parsed to nothing. */
    var lastResponse: String? = null
        private set

    /** The last answer's body, capped: kept when a page parses to nothing, as a fixture to fix the parser with. */
    var lastBody: String? = null
        private set

    private val http = shared.newBuilder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /**
     * @return the body, or "" for a 4xx that is not throttling (a posting
     *         taken down: nothing here, not a block).
     * @throws BlockedException when retries are exhausted or time is up.
     */
    fun get(url: String, query: Map<String, Any?> = emptyMap()): String {
        val builder = url.toHttpUrl().newBuilder()
        for ((key, value) in query) {
            if (value != null && value.toString().isNotEmpty()) builder.setQueryParameter(key, value.toString())
        }
        return send(builder.build(), null)
    }

    /** A JSON POST, for a search API; answered and retried as [get] is. */
    fun postJson(url: String, json: String): String =
        send(url.toHttpUrl(), json.toRequestBody("application/json".toMediaType()))

    private fun send(target: HttpUrl, body: RequestBody?): String {
        var attempt = 0
        while (true) {
            pace()

            val response: Response = try {
                http.newCall(request(target.toString(), body)).execute()
            } catch (error: IOException) {
                /*
                 * No network at all is a handover (Wi-Fi ↔ mobile), not this
                 * site failing: wait for the new one and retry free. Not a
                 * transport error if it never returns, or Wuzzuf would mark
                 * the page checked and never open it again.
                 */
                if (!Net.isOnline()) {
                    if (Net.awaitOnline(until = deadline, isStopped = isStopped)) continue
                    if (isStopped()) throw BlockedException("$label: the system stopped the run")
                    run.lost = true
                    throw offline()
                }
                attempt += 1
                if (attempt >= Limits.MAX_RETRIES) {
                    throw BlockedException("$label request failed after $attempt attempts: ${error.message}", transport = true)
                }
                sleep(backoffMs(attempt, null))
                continue
            }

            response.use {
                val status = it.code

                /*
                 * A keyed API's 4xx is a refusal with a reason — a bad key,
                 * or Serper's "Not enough credits" as a 400. Read as "nothing
                 * here" like a page's 4xx, running out of credits looked like
                 * a quiet day, every day. 429 is still pacing, and retried.
                 */
                if (body != null && status in 400..499 && status != 429) {
                    val text = runCatching { it.body?.string() }.getOrNull().orEmpty()
                    val reason = runCatching { org.json.JSONObject(text).optString("message") }.getOrNull()
                        ?.ifEmpty { null } ?: text.replace(Regex("\\s+"), " ").take(200).ifEmpty { "no reason given" }
                    throw BlockedException("$label refused the request (HTTP $status): $reason", alert = "api-refused")
                }

                if (status == 429 || status == 403 || status == 999) {
                    attempt += 1
                    throttled(attempt, it)
                    if (attempt >= Limits.MAX_RETRIES) {
                        throw BlockedException("$label blocked the request (HTTP $status) after $attempt attempts: ${target.encodedPath}")
                    }
                    return@use
                }

                if (status >= 500) {
                    attempt += 1
                    if (attempt >= Limits.MAX_RETRIES) {
                        throw BlockedException("$label returned HTTP $status after $attempt attempts: ${target.encodedPath}")
                    }
                    sleep(backoffMs(attempt, it))
                    return@use
                }

                if (!it.isSuccessful) {
                    Log.warn("$label HTTP $status ${target.encodedPath}")
                    return ""
                }

                val body = it.body?.string() ?: ""
                relax()
                lastResponse = "HTTP $status, ${body.length} chars, ${it.header("content-type")}"
                lastBody = body.take(Snapshots.MAX_CHARS)
                return body
            }
        }
    }

    private fun request(url: String, body: RequestBody?): Request {
        val builder = Request.Builder().url(url)
        if (body != null) builder.post(body)
        builder
            .header("User-Agent", Limits.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
        for ((key, value) in headers) builder.header(key, value)
        return builder.build()
    }

    /** The wait every request goes through; the first of a run goes straight out. */
    private fun pace() {
        if (isStopped()) throw BlockedException("$label: the system stopped the run")
        // Already waited once this run: fail fast, the resume run picks it up.
        if (run.lost) throw offline()
        if (System.currentTimeMillis() >= deadline) {
            throw BlockedException("$label: out of time for this run; the rest waits for the next one")
        }

        val gap = if (firstRequest && !paceFirst) 0 else intervalMs + if (jitterMs > 0) (Math.random() * jitterMs).toLong() else 0
        val wait = maxOf(gap, cooldownUntil - System.currentTimeMillis())
        firstRequest = false
        if (wait > 0) sleep(wait)
    }

    private fun offline() = BlockedException("$label: no network; the rest waits until the phone is back online")

    private fun backoffMs(attempt: Int, response: Response?): Long {
        val retryAfter = response?.header("Retry-After")?.trim()?.toLongOrNull()
        if (retryAfter != null && retryAfter > 0) return retryAfter * 1000
        return Limits.BACKOFF_BASE_MS * (1L shl (attempt - 1))
    }

    private fun throttled(attempt: Int, response: Response) {
        val asked = backoffMs(attempt, response)
        throttleEvents += 1
        throttleStatus = response.code
        cleanStreak = 0
        // Every retry into a 429 lengthens the site's penalty: stop at once when that is the budget.
        if (throttleEvents >= throttleBudget && throttleBudget <= 1) {
            throw BlockedException("$label throttled (HTTP ${response.code}); stopping until it has rested")
        }
        cooldownUntil = System.currentTimeMillis() + asked
        intervalMs = minOf(Limits.MAX_DELAY_MS, maxOf(intervalMs * 2, asked))

        Log.warn("$label throttled (HTTP ${response.code}), next wait ${maxOf(intervalMs, asked)} ms")

        if (throttleEvents >= throttleBudget) {
            throw BlockedException("$label was throttled $throttleEvents times this run; stopping until the next one")
        }
    }

    /** After a clean stretch, give back the pace a throttle cost. */
    private fun relax() {
        if (intervalMs <= delayMs) return
        cleanStreak += 1
        if (cleanStreak < 20) return
        cleanStreak = 0
        throttleEvents = 0
        intervalMs = maxOf(delayMs, intervalMs / 2)
    }

    private fun sleep(ms: Long) {
        // Never sleep past the deadline: the worker has a hard limit.
        val until = minOf(System.currentTimeMillis() + ms, deadline)
        while (System.currentTimeMillis() < until) {
            if (isStopped()) throw BlockedException("$label: the system stopped the run")
            Thread.sleep(minOf(500L, until - System.currentTimeMillis()).coerceAtLeast(1))
        }
    }

    companion object {
        /** One connection pool for the whole app. */
        val shared: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
