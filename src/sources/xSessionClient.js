import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** The longest a rate-limit reset is waited out in-run; past it, the run stops. */
const MAX_RESET_WAIT_MS = 90_000;

/*
 * The feature flags X's web app sends with SearchTimeline. The endpoint answers
 * 400 naming any flag it now requires and did not get, so a change on X's side
 * is fixed by setting X_SESSION_FEATURES to the list in that error.
 */
export const DEFAULT_FEATURES = {
  rweb_video_screen_enabled: false,
  profile_label_improvements_pcf_label_in_post_enabled: true,
  rweb_tipjar_consumption_enabled: true,
  verified_phone_label_enabled: false,
  creator_subscriptions_tweet_preview_api_enabled: true,
  responsive_web_graphql_timeline_navigation_enabled: true,
  responsive_web_graphql_skip_user_profile_image_extensions_enabled: false,
  premium_content_api_read_enabled: false,
  communities_web_enable_tweet_community_results_fetch: true,
  c9s_tweet_anatomy_moderator_badge_enabled: true,
  responsive_web_grok_analyze_button_fetch_trends_enabled: false,
  responsive_web_grok_analyze_post_followups_enabled: true,
  responsive_web_jetfuel_frame: false,
  responsive_web_grok_share_attachment_enabled: true,
  articles_preview_enabled: true,
  responsive_web_edit_tweet_api_enabled: true,
  graphql_is_translatable_rweb_tweet_is_translatable_enabled: true,
  view_counts_everywhere_api_enabled: true,
  longform_notetweets_consumption_enabled: true,
  responsive_web_twitter_article_tweet_consumption_enabled: true,
  tweet_awards_web_tipping_enabled: false,
  responsive_web_grok_show_grok_translated_post: false,
  responsive_web_grok_analysis_button_from_backend: true,
  creator_subscriptions_quote_tweet_preview_enabled: false,
  freedom_of_speech_not_reach_fetch_enabled: true,
  standardized_nudges_misinfo: true,
  tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled: true,
  longform_notetweets_rich_text_read_enabled: true,
  longform_notetweets_inline_media_enabled: true,
  responsive_web_grok_image_annotation_enabled: true,
  responsive_web_enhance_cards_enabled: false,
};

/**
 * Builds the x-client-transaction-id X now demands on every API call: a value
 * derived from the method, the path and keys in x.com's own page and scripts.
 * Loaded lazily so the dependency is never touched unless this source runs.
 */
async function defaultTransactionFactory() {
  const { ClientTransaction, fetchXDocument } = await import('x-client-transaction-id');
  const transaction = await ClientTransaction.create(await fetchXDocument());

  return (method, path) => transaction.generateTransactionId(method, path);
}

/**
 * X's internal search, called as its web app calls it, signed in by cookie.
 *
 * Every way X can refuse is turned into a BlockedError that says what to fix,
 * because each of them — an expired cookie, a rotated query id, a new feature
 * flag — otherwise looks exactly like a search that found nothing.
 */
export class XSessionClient {
  /**
   * @param {object} settings  config.sources.x_session
   * @param {{fetch?: Function, transactionFactory?: Function}} deps  for tests
   */
  constructor(settings = config.sources.x_session, deps = {}) {
    this.settings = settings;
    this.fetch = deps.fetch ?? ((...args) => fetch(...args));
    this.transactionFactory = deps.transactionFactory ?? defaultTransactionFactory;
    this.maxRetries = Math.max(1, config.http.maxRetries);
    this.requestCount = 0;
  }

  /**
   * One page of Latest results for a raw X search query.
   *
   * @returns {Promise<object>} the parsed JSON response
   * @throws {BlockedError}
   */
  async search(rawQuery, cursor = null) {
    if (this.requestCount >= this.settings.maxRequests) {
      throw new BlockedError(
        `X session request budget spent (${this.settings.maxRequests} per run, X_SESSION_MAX_REQUESTS)`,
      );
    }

    const path = `${new URL(this.settings.apiBase).pathname}/${this.settings.searchQueryId}/SearchTimeline`;
    const url = new URL(`${this.settings.apiBase}/${this.settings.searchQueryId}/SearchTimeline`);

    url.searchParams.set(
      'variables',
      JSON.stringify({
        rawQuery,
        count: this.settings.pageSize,
        querySource: 'typed_query',
        product: 'Latest',
        ...(cursor ? { cursor } : {}),
      }),
    );
    url.searchParams.set('features', JSON.stringify(this.#features()));

    for (let attempt = 1; ; attempt += 1) {
      if (this.settings.delayMs > 0) await sleep(this.settings.delayMs);

      const response = await this.#send(url, path);
      const body = await response.text();

      if (response.status === 401 || response.status === 403) {
        throw new BlockedError(
          `X rejected the session (HTTP ${response.status}): the cookie has expired or the ` +
            'account is locked. Log in to the throwaway account in a browser and copy fresh ' +
            'auth_token and ct0 cookies into X_AUTH_TOKEN and X_CT0.',
        );
      }

      if (response.status === 404) {
        throw new BlockedError(
          `X answered 404 for SearchTimeline query id "${this.settings.searchQueryId}": X has ` +
            'rotated it, or rejected the x-client-transaction-id. Copy the current id from ' +
            'DevTools → Network on x.com/search into X_SEARCH_QUERY_ID.',
        );
      }

      if (response.status === 400 || response.status === 422) {
        throw new BlockedError(
          `X refused the search request (HTTP ${response.status}): ${excerpt(body)}. ` +
            'A "features cannot be null" message means X_SESSION_FEATURES needs the listed flags.',
        );
      }

      if (response.status === 429) {
        const waitMs = resetWait(response);

        if (attempt >= this.maxRetries || waitMs > MAX_RESET_WAIT_MS) {
          throw new BlockedError(
            `X rate-limited the account (HTTP 429)${resetNote(response)}. ` +
              'Lower X_SESSION_MAX_REQUESTS or run less often.',
          );
        }

        logger.warn('X rate limit hit, waiting', { waitMs, attempt });
        await sleep(waitMs);
        continue;
      }

      if (response.status >= 500) {
        if (attempt >= this.maxRetries) {
          throw new BlockedError(`X returned HTTP ${response.status} after ${attempt} attempts`);
        }

        await sleep(config.http.backoffBaseMs * 2 ** (attempt - 1));
        continue;
      }

      if (!response.ok) {
        throw new BlockedError(`X returned HTTP ${response.status}: ${excerpt(body)}`);
      }

      let payload;

      try {
        payload = JSON.parse(body);
      } catch {
        // A login page or a challenge served with 200: never "no results".
        throw new BlockedError(`X did not return JSON: ${excerpt(body)}`);
      }

      // GraphQL reports some failures inside a 200. With no data alongside,
      // the search did not happen.
      if (Array.isArray(payload.errors) && payload.errors.length > 0 && !payload.data) {
        throw new BlockedError(
          `X search failed: ${payload.errors.map((error) => error.message).join('; ')}`,
        );
      }

      return payload;
    }
  }

  async #send(url, path) {
    this.requestCount += 1;

    let transactionId;

    try {
      this.transactionId ??= await this.transactionFactory();
      transactionId = await this.transactionId('GET', path);
    } catch (error) {
      throw new BlockedError(
        `Could not build x-client-transaction-id: ${error.message}. X has likely changed ` +
          'its page; update the x-client-transaction-id package.',
      );
    }

    const startedAt = Date.now();

    try {
      const response = await this.fetch(url, {
        method: 'GET',
        signal: AbortSignal.timeout(config.http.timeoutMs),
        headers: {
          'User-Agent': config.http.userAgent,
          Accept: '*/*',
          'Accept-Language': 'en-US,en;q=0.9',
          'Content-Type': 'application/json',
          Referer: 'https://x.com/search',
          authorization: `Bearer ${this.settings.bearer}`,
          'x-csrf-token': this.settings.ct0,
          cookie: `auth_token=${this.settings.authToken}; ct0=${this.settings.ct0}`,
          'x-twitter-auth-type': 'OAuth2Session',
          'x-twitter-active-user': 'yes',
          'x-twitter-client-language': 'en',
          'x-client-transaction-id': transactionId,
        },
      });

      // The path only: the query string carries the search, never the session.
      logger.debug('HTTP request', {
        remote: 'X',
        url: path,
        status: response.status,
        durationMs: Date.now() - startedAt,
      });

      return response;
    } catch (error) {
      logger.debug('HTTP request failed', {
        remote: 'X',
        url: path,
        durationMs: Date.now() - startedAt,
        error: error.message,
      });
      throw new BlockedError(`X request failed: ${error.message}`, [], true);
    }
  }

  #features() {
    if (!this.settings.features) return DEFAULT_FEATURES;

    try {
      return JSON.parse(this.settings.features);
    } catch {
      throw new BlockedError('X_SESSION_FEATURES is not valid JSON');
    }
  }
}

/** Milliseconds until X's rate-limit window resets, or a short default. */
function resetWait(response) {
  const reset = Number(response.headers.get('x-rate-limit-reset'));
  if (!Number.isFinite(reset) || reset <= 0) return config.http.backoffBaseMs;

  return Math.max(1000, reset * 1000 - Date.now());
}

function resetNote(response) {
  const reset = Number(response.headers.get('x-rate-limit-reset'));
  if (!Number.isFinite(reset) || reset <= 0) return '';

  return `; the window resets at ${new Date(reset * 1000).toISOString()}`;
}

function excerpt(body) {
  return String(body ?? '').slice(0, 200).replace(/\s+/g, ' ').trim();
}

export default XSessionClient;
