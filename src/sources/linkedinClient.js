import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';

/** LinkedIn's non-standard "you are being throttled" status. */
const STATUS_BLOCKED = 999;
const THROTTLE_STATUSES = new Set([429, 403, STATUS_BLOCKED]);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Thin HTTP client for LinkedIn's public, unauthenticated guest endpoints.
 *
 * Responsibilities: pacing, realistic headers, and turning the several ways
 * LinkedIn says "stop" (429, 403, its custom 999) into one error type. It knows
 * nothing about job markup — that is the parser's job.
 *
 * `label` only names the remote in errors and logs; pacing and back-off behave
 * the same wherever it points, so the posts source reuses this client for its
 * search engine rather than duplicating the retry logic.
 */
export class LinkedInGuestClient {
  constructor(options = {}) {
    this.options = { ...config.http, ...options };
    this.label = options.label ?? 'LinkedIn';
    // Per-host additions to the request headers. Kept opt-in rather than sent
    // everywhere, so tuning one remote cannot quietly change how another
    // behaves.
    this.headers = options.headers ?? {};
    this.requestCount = 0;

    /*
     * Throttle adaptation, one client = one paced lane.
     *
     * A 429/403/999 is a statement about this client, not the URL that earned
     * it — so these outlive that request: intervalMs is the pace every later
     * request uses, cooldownUntil holds a floor under it while the remote's
     * Retry-After lasts, and throttleEvents counts a bad patch until clean
     * answers clear it (see #relax).
     */
    this.intervalMs = this.options.delayMs;
    this.cooldownUntil = 0;
    this.throttleEvents = 0;
    this.cleanStreak = 0;
  }

  /**
   * Fetch an HTML fragment, retrying with exponential backoff on throttling.
   *
   * @throws {BlockedError} when retries are exhausted.
   */
  async getHtml(url, query = {}) {
    return this.#fetchBody(url, { query });
  }

  /**
   * The same request, sent as a JSON POST — what a search API takes where a
   * search page takes a query string.
   *
   * Separate entry point rather than an option on getHtml, so the pacing and
   * back-off below stay the one implementation both shapes share.
   *
   * @throws {BlockedError} when retries are exhausted.
   */
  async postJson(url, body) {
    return this.#fetchBody(url, { json: body });
  }

  async #fetchBody(url, { query = {}, json = null } = {}) {
    const maxRetries = Math.max(1, this.options.maxRetries);
    const target = new URL(url);

    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
        target.searchParams.set(key, String(value));
      }
    }

    let attempt = 0;

    for (;;) {
      await this.#pace();

      let response;

      try {
        response = await this.#send(target, json);
      } catch (error) {
        attempt += 1;

        if (attempt >= maxRetries) {
          // Never answered, as opposed to refused: flagged so a caller walking
          // many URLs can skip this one instead of abandoning the run.
          throw new BlockedError(
            `${this.label} request failed after ${attempt} attempts: ${error.message}`,
            [],
            true,
          );
        }

        await this.#backoff(attempt);
        continue;
      }

      /*
       * A keyed API answering 401/403 is rejecting the credential, not pacing
       * us, and no amount of backing off changes that. Said outright rather
       * than retried into a generic block — and never allowed to fall through
       * to the 4xx branch below, whose empty body reads downstream as a
       * successful crawl that found nothing.
       */
      if (json !== null && (response.status === 401 || response.status === 403)) {
        const error = new BlockedError(
          `${this.label} rejected the credential (HTTP ${response.status}). ` +
            'Check the API key — an expired or mistyped one looks exactly like an empty crawl.',
        );
        error.alert = 'api-refused';
        throw error;
      }

      if (THROTTLE_STATUSES.has(response.status)) {
        attempt += 1;

        if (attempt >= maxRetries) {
          // The refusal that ends the request is a throttle too — counted and
          // logged like the others, so the run's totals and the log add up.
          this.throttleEvents += 1;
          this.cleanStreak = 0;

          logger.warn('Throttled, giving up', {
            remote: this.label,
            attempt,
            status: response.status,
            throttled: this.throttleEvents,
            url: target.pathname,
          });

          throw new BlockedError(
            `${this.label} blocked the request (HTTP ${response.status}) after ${attempt} attempts: ${target.pathname}`,
          );
        }

        this.#throttled(attempt, response);
        continue;
      }

      if (response.status >= 500) {
        attempt += 1;

        if (attempt >= maxRetries) {
          throw new BlockedError(
            `${this.label} returned HTTP ${response.status} after ${attempt} attempts: ${target.pathname}`,
          );
        }

        await this.#backoff(attempt, response);
        continue;
      }

      /*
       * A keyed API's other 4xx is a refusal with a reason in the body —
       * Serper says "Not enough credits" with a 400. Read as "nothing here"
       * like a page's 4xx, it made running out of credits look like a quiet
       * day, every day, until someone noticed. Raised as an alert instead.
       */
      if (json !== null && !response.ok) {
        const text = (await response.text().catch(() => '')).replace(/\s+/g, ' ').trim();
        let reason = text.slice(0, 200);

        try {
          reason = JSON.parse(text).message ?? reason;
        } catch {
          // Not JSON: the raw text is the reason.
        }

        const error = new BlockedError(
          `${this.label} refused the request (HTTP ${response.status}): ${reason || 'no reason given'}`,
        );
        error.alert = 'api-refused';
        throw error;
      }

      if (!response.ok) {
        // A 4xx that is not throttling (e.g. a posting that was taken down).
        // Not retryable and not a block — treat it as "nothing here".
        logger.warn('Guest request failed', {
          remote: this.label,
          status: response.status,
          url: target.pathname,
        });

        return '';
      }

      const body = await response.text();

      this.#relax();

      // Kept for diagnosis: when a parser finds nothing in a 200, the source
      // can say what the remote really sent — a challenge page, a redirect to
      // somewhere else, an empty shell — instead of reporting "0 results".
      this.lastResponse = {
        url: response.url,
        status: response.status,
        length: body.length,
        contentType: response.headers.get('content-type'),
      };

      return body;
    }
  }

  async #send(url, json = null) {
    this.requestCount += 1;

    const sendingJson = json !== null;
    const startedAt = Date.now();
    const target = new URL(url);

    const response = await fetch(url, {
      method: sendingJson ? 'POST' : 'GET',
      redirect: 'follow',
      signal: AbortSignal.timeout(this.options.timeoutMs),
      headers: {
        'User-Agent': this.options.userAgent,
        Accept: sendingJson ? 'application/json' : 'text/html,application/xhtml+xml,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
        ...(sendingJson ? { 'Content-Type': 'application/json' } : {}),
        ...this.headers,
      },
      ...(sendingJson ? { body: JSON.stringify(json) } : {}),
    }).catch((error) => {
      logger.debug('HTTP request failed', {
        remote: this.label,
        url: `${target.host}${target.pathname}`,
        durationMs: Date.now() - startedAt,
        error: error.message,
      });
      throw error;
    });

    // One line per request, at debug: which page, how it answered, how long it took.
    logger.debug('HTTP request', {
      remote: this.label,
      method: sendingJson ? 'POST' : 'GET',
      url: `${target.host}${target.pathname}${target.search}`,
      status: response.status,
      durationMs: Date.now() - startedAt,
      bytes: Number(response.headers.get('content-length')) || undefined,
      request: this.requestCount,
    });

    return response;
  }

  /**
   * The wait every request goes through: the client's current interval, held
   * back further while a throttle's cool-down lasts.
   */
  async #pace() {
    const cooldownMs = Math.max(0, this.cooldownUntil - Date.now());
    const waitMs = Math.max(this.intervalMs, cooldownMs);

    if (waitMs > 0) {
      await sleep(waitMs);
    }
  }

  /** Retry-After when the remote sent one, the exponential schedule otherwise. */
  #waitFor(attempt, response = null) {
    // Number(null) is 0 and Number('') is 0, both of which are finite — so a
    // missing header must be rejected explicitly or we "wait" zero and hammer
    // a server that just asked us to slow down.
    const header = response?.headers?.get('retry-after');
    const retryAfterSeconds =
      header === null || header === undefined || header.trim() === '' ? null : Number(header);

    if (retryAfterSeconds !== null && Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0) {
      return retryAfterSeconds * 1000;
    }

    return this.options.backoffBaseMs * 2 ** (attempt - 1);
  }

  /** Transport errors and 5xx: wait out the schedule, then let the loop retry. */
  async #backoff(attempt, response = null) {
    const waitMs = this.#waitFor(attempt, response);

    logger.warn('Throttled, backing off', {
      remote: this.label,
      attempt,
      waitMs,
      status: response?.status ?? null,
    });

    await sleep(waitMs);
  }

  /**
   * A throttle says "you are too fast", about the client and not the URL, so
   * every later request feels it too — not just this retry. The pace doubles
   * (up to maxDelayMs), the remote's own Retry-After becomes a cool-down the
   * next request cannot start before, and a run that keeps being throttled
   * stops instead of spending its whole budget knocking.
   */
  #throttled(attempt, response) {
    const askedMs = this.#waitFor(attempt, response);

    this.throttleEvents += 1;
    this.cleanStreak = 0;
    this.cooldownUntil = Date.now() + askedMs;

    const cap = this.options.maxDelayMs ?? 60_000;
    this.intervalMs = Math.min(cap, Math.max(this.intervalMs * 2, askedMs));

    logger.warn('Throttled, backing off', {
      remote: this.label,
      attempt,
      // What the next request will really wait, not just this retry.
      waitMs: Math.max(this.intervalMs, askedMs),
      status: response?.status ?? null,
      throttled: this.throttleEvents,
    });

    const budget = this.options.throttleBudget ?? 5;

    if (budget > 0 && this.throttleEvents >= budget) {
      throw new BlockedError(
        `${this.label} was throttled ${this.throttleEvents} times this run; stopping so the ` +
          'remaining searches wait for the next pass.',
      );
    }
  }

  /**
   * A clean answer. After a stretch of them, give back the pace a throttle
   * cost — and clear the throttle count, so the budget measures a bad patch
   * rather than a whole day of intermittent throttling in watch mode.
   */
  #relax() {
    if (this.intervalMs <= this.options.delayMs) return;

    this.cleanStreak += 1;

    if (this.cleanStreak < (this.options.recoverySuccesses ?? 20)) return;

    this.cleanStreak = 0;
    this.throttleEvents = 0;
    this.intervalMs = Math.max(this.options.delayMs, this.intervalMs / 2);
  }
}

export default LinkedInGuestClient;
