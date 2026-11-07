import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import LinkedInGuestClient from './linkedinClient.js';
import { resolveEngine } from './searchEngines.js';
import { planQueries, placeFor, YieldLedger } from './searchPlan.js';
import SerperBudget, { logSearchBudget } from './serperBudget.js';
import { parsePost, authorFromOgTitle, stripAuthorPrefix } from './linkedinPostParser.js';

export const KEY = 'linkedin_posts';

const TITLE_MAX = 140;

/**
 * Finds hiring posts — the "we're looking for a Laravel dev, DM me" kind — that
 * never become job listings.
 *
 * LinkedIn has no public post search for logged-out clients: /search/results/
 * content/ sits behind the auth wall, and the free ways past it all need an
 * account cookie, which risks the account and breaks LinkedIn's terms. It does
 * let search engines index public posts, so this source searches the index
 * instead: a search engine finds the permalinks, LinkedIn serves them.
 *
 * The trade is freshness for safety. A post reaches the engine's index hours to
 * days after it is written, so this source complements the jobs source rather
 * than matching its hourly rhythm. Dedup makes a wide time window harmless.
 */
export class LinkedInPostsSource {
  /**
   * Two clients, because the pacing counter lives on the instance: the search
   * engine and LinkedIn each get their own delay budget instead of sharing one
   * that would let a burst against either look like a burst against both.
   */
  constructor(searchClient = null, postClient = null) {
    this.key = KEY;
    this.injectedSearchClient = searchClient;
    this.postClient = postClient ?? new LinkedInGuestClient({ label: 'LinkedIn' });
    this.store = null;
    this.alerts = [];
  }

  /** The store keeps the Serper count and each query's yield across runs. */
  useStore(store) {
    this.store = store;
  }

  /** Warnings that did not stop the run (a low balance); the pipeline sends them. */
  takeAlerts() {
    const alerts = this.alerts;
    this.alerts = [];
    return alerts;
  }

  /**
   * Built on first use, not in the constructor: the engine decides the client's
   * headers (an API key, for a keyed engine), and every source is constructed
   * at startup whether or not it is enabled. Resolving an engine name that
   * early would turn a typo in a disabled source into a crash at boot.
   */
  get search() {
    this.searchClient ??=
      this.injectedSearchClient ??
      new LinkedInGuestClient({
        label: this.engine.label,
        headers: this.engine.headers?.(this.settings) ?? {},
        // A billed API has its own pace; the crawl-wide delay is LinkedIn's.
        ...(this.engine.billed ? { delayMs: config.serper.delayMs } : {}),
      });

    return this.searchClient;
  }

  get engine() {
    this.resolvedEngine ??= resolveEngine(this.settings.engine);
    return this.resolvedEngine;
  }

  get settings() {
    return config.sources[KEY];
  }

  isEnabled() {
    return Boolean(this.settings.enabled);
  }

  /**
   * @param {{keywords?: string[], locations?: string[], pages?: number,
   *          limit?: number, timeWindow?: string}} options
   * @returns {Promise<object[]>}
   * @throws {BlockedError} carrying whatever was parsed before the block.
   */
  async fetch(options = {}) {
    this.queriesThisRun = 0;

    try {
      return await this.#crawl(options);
    } finally {
      if (this.queriesThisRun > 0) {
        const budget = this.engine.billed ? new SerperBudget(this.store) : null;
        logSearchBudget(KEY, this.engine, this.queriesThisRun, budget);
      }
    }
  }

  async #crawl(options) {
    /*
     * This source pays per query and targets a different market than the job
     * boards do, so it carries its own keyword and location lists rather than
     * inheriting the crawl-wide ones. Falling back to those would mean every
     * keyword against every remote market — dozens of billed queries per run,
     * none of them aimed at what this source is for.
     */
    const keywords = options.keywords?.length
      ? options.keywords
      : this.settings.keywords.length > 0
        ? this.settings.keywords
        : config.keywords;

    const fallbackLocations =
      config.locations.length > 0 ? config.locations : config.remoteLocations;

    const locations = options.locations?.length
      ? options.locations
      : this.settings.locations.length > 0
        ? this.settings.locations
        : fallbackLocations;
    const maxPages = options.pages ?? this.settings.maxPages;
    const limit = options.limit ?? null;
    const timeWindow = options.timeWindow ?? this.settings.timeWindow;

    if (keywords.length === 0 || locations.length === 0) {
      logger.warn('LinkedIn posts source has no keywords or locations configured');
      return [];
    }

    // The same post surfaces under several keyword/location queries.
    const seen = new Map();

    const planned = planQueries({
      template: this.settings.queryTemplate,
      keywords,
      locations,
      queryMode: this.settings.queryMode,
      groupSize: this.settings.groupSize,
    });
    const ledger = new YieldLedger(this.store, KEY, this.settings);
    const budget = this.engine.billed ? new SerperBudget(this.store) : null;

    // Explicit lists (a manual run, a test) ask everything; the schedule may skip cold queries.
    const explicit = Boolean(options.keywords?.length || options.locations?.length);
    const due = explicit ? planned : planned.filter((query) => ledger.isDue(query));

    if (due.length < planned.length) {
      logger.info('LinkedIn posts: skipping cold queries this run', {
        asked: due.length,
        skipped: planned.length - due.length,
      });
    }

    if (budget && this.store) {
      const alert = await budget.dailyBalanceAlert();
      if (alert) this.alerts.push(alert);
    }

    for (const query of due) {
      try {
        const fresh = await this.#search(query, maxPages, limit, timeWindow, seen, budget);
        ledger.record(query, fresh);
      } catch (error) {
        if (error instanceof BlockedError) {
          // Partial results are independently valid; hand them back.
          error.partialItems = await this.#enrich([...seen.values()]);
        }
        throw error;
      }

      if (limit !== null && seen.size >= limit) {
        return this.#enrich([...seen.values()]);
      }
    }

    return this.#enrich([...seen.values()]);
  }

  /** @returns {Promise<number>} results within the age cut: the query's yield. */
  async #search(planned, maxPages, limit, timeWindow, seen, budget) {
    const engine = this.engine;
    let fresh = 0;

    for (let page = 0; page < maxPages; page += 1) {
      const context = { query: planned.query, page, timeWindow };

      // Counted before it is sent: a request that fails was still billed.
      budget?.spend(engine.label);
      this.queriesThisRun += 1;

      /*
       * A search API takes a JSON body; a search page takes a query string.
       * The engine says which by defining `body` or `params`, so adding an
       * engine of either shape needs no change here.
       */
      const body = engine.body
        ? await this.search.postJson(engine.url(this.settings), engine.body(this.settings, context))
        : await this.search.getHtml(
            engine.url(this.settings),
            engine.params(this.settings, context),
          );

      // An empty body is the client's "4xx, nothing here" and never parses.
      if (body.trim() === '') return fresh;

      const results = engine.parse(body).filter((result) => isPostUrl(result.url));

      // No usable result means the end of this query, not an error: a narrow
      // keyword x location pair legitimately matches nothing.
      if (results.length === 0) return fresh;

      for (const result of results) {
        const location = placeFor(planned, result.title, result.snippet);
        const item = this.#toItem(result, planned.keyword, location);
        if (!item) continue;
        if (this.#tooOld(item)) continue;

        fresh += 1;
        if (!seen.has(item.externalId)) seen.set(item.externalId, item);

        if (limit !== null && seen.size >= limit) return fresh;
      }
    }

    return fresh;
  }

  /**
   * A backstop on the engine's own time filter, which is looser than it looks:
   * an engine dates a result by when it indexed the page, so a one-week window
   * returns posts from last year. Only a result that states its own age is
   * judged — most state none, and a missing date is not evidence of anything.
   */
  #tooOld(item) {
    const maxAgeDays = this.settings.maxAgeDays;
    if (!maxAgeDays || !item.postedAt) return false;

    const age = Date.now() - new Date(item.postedAt).getTime();
    if (!Number.isFinite(age)) return false;

    return age > maxAgeDays * 86_400_000;
  }

  /**
   * Fetch each post permalink so the filters read the real body instead of a
   * truncated search snippet.
   *
   * Capped by maxDetails: one paced request per post means an unbounded result
   * set spends the whole run — and the rate-limit budget — on enrichment. A
   * post that cannot be fetched keeps its snippet, so it is still judged.
   */
  async #enrich(items) {
    if (!this.settings.fetchDetails || items.length === 0) return items;

    const candidates = items.slice(0, this.settings.maxDetails);

    logger.debug('Fetching post details', {
      candidates: candidates.length,
      skipped: items.length - candidates.length,
    });

    for (const item of candidates) {
      try {
        const html = await this.postClient.getHtml(item.url);
        const post = parsePost(html);

        if (post.walled) {
          // A walled render is normal, not a failure: keep the snippet, which
          // is usually enough for the filters to judge the post.
          logger.debug('Post is behind the auth wall; keeping search snippet', { url: item.url });
        } else {
          item.excerpt = post.text ?? item.excerpt;
          item.title = truncate(firstLine(post.text) ?? item.title, TITLE_MAX);
        }

        item.company = post.author ?? item.company;
        // The activity id's timestamp is exact; the page's own date may be a
        // bare day, which reads as midnight UTC (03:00 in Cairo).
        item.postedAt = activityDate(item.url) ?? post.postedAt ?? item.postedAt;
      } catch (error) {
        if (error instanceof BlockedError) throw error;

        // One unreadable permalink must not cost us the post.
        logger.warn('Could not fetch post detail', { url: item.url, error: error.message });
      }
    }

    return items;
  }

  #toItem(result, keyword, location) {
    const externalId = extractExternalId(result.url);
    if (!externalId) return null;

    const headline = stripAuthorPrefix(result.title);

    return {
      source: KEY,
      externalId,
      itemType: 'post',
      title: truncate(headline ?? result.title ?? 'LinkedIn post', TITLE_MAX),
      url: stripQuery(result.url),
      /*
       * The author is the person to approach, and formatPost renders `company`
       * as exactly that for a post.
       *
       * Two ways to find one, because the first often fails here: LinkedIn
       * titles its own pages "<Author> on LinkedIn: <post>", but a search
       * engine frequently replaces that with the post's opening line, which
       * names nobody. The permalink always carries the author's profile slug,
       * so it answers when the title does not.
       */
      company: authorFromOgTitle(result.title) ?? authorFromUrl(result.url),
      /*
       * The place the search asked for (or, for a grouped query, the one of its
       * places the result names), kept for the message — not a fact
       * about the post, which carries no location field of its own.
       *
       * `market` is deliberately left unset, and that is the difference
       * between this source and every other one. A job board search filters
       * by market and its results are in that market; a search engine only
       * biases towards one, and a search naming Egypt returns "Senior Laravel
       * Developer. Location: Jordan" in first place. So there is nothing here
       * for the location filter to take on trust, and filters.js reads the
       * post's own words instead — ignoring this field, which would otherwise
       * answer the question with the question.
       */
      location,
      keyword,
      excerpt: result.snippet,
      // The activity id first: it is the exact moment the post was written.
      // The engine's date is only a fallback, because it is often a bare day
      // ("Oct 27, 2026") that parses as midnight UTC, so every post read 03:00.
      postedAt: activityDate(result.url) ?? result.postedAt,
    };
  }
}

/** Only real post permalinks: profile and company pages match `site:` too. */
export function isPostUrl(url) {
  return /linkedin\.com\/(posts\/|feed\/update\/)/i.test(url ?? '');
}

/**
 * The activity id is the post's stable identity, and so the dedup key: one post
 * is reachable both as /posts/<slug>-activity-<id>-<hash> and as
 * /feed/update/urn:li:activity:<id>, and both must resolve to one row.
 */
export function extractExternalId(url) {
  if (!url) return null;

  const activity = url.match(/activity[:-](\d{10,25})/i);
  if (activity) return activity[1];

  const slug = url.match(/linkedin\.com\/posts\/([^/?#]+)/i);
  return slug ? slug[1] : null;
}

/**
 * When a post was written, read off its activity id.
 *
 * A LinkedIn activity id is a snowflake like Twitter's: the top bits are the
 * creation time in milliseconds since the Unix epoch (offset 0, unlike X). So
 * even a post that stays behind the auth wall — where the snippet carries no
 * date — arrives with an exact timestamp. That is what lets the age backstop
 * hold a tight window reliably rather than as a guess.
 *
 * @returns {string|null} ISO 8601
 */
export function activityDate(url) {
  const match = (url ?? '').match(/activity[:-](\d{10,25})/i);
  if (!match) return null;

  const ms = Number(BigInt(match[1]) >> 22n);
  const date = new Date(ms);

  return ms <= 0 || Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/**
 * The author's name, read off the permalink.
 *
 * A post URL is /posts/<profile-slug>_<post-slug>-activity-<id>-<hash>, and
 * the profile slug is the author's name hyphenated. Recovering a name from it
 * means undoing that — imperfect, since LinkedIn appends digits to a taken
 * slug, but "Mohamed Elsayed" is worth more than nothing when the message's
 * whole point is who to contact.
 */
export function authorFromUrl(url) {
  const match = (url ?? '').match(/linkedin\.com\/posts\/([^/?#_]+)/i);
  if (!match) return null;

  const name = match[1]
    .split('-')
    // The trailing token is LinkedIn's disambiguator, not part of the name.
    .filter((part) => part !== '' && !/^[0-9a-f]{6,}$/i.test(part))
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
    .trim();

  return name === '' ? null : name;
}

function stripQuery(url) {
  const index = url.indexOf('?');
  return (index === -1 ? url : url.slice(0, index)).trim();
}

function firstLine(text) {
  if (!text) return null;

  const line = text.split(/\r?\n/)[0].trim();
  return line === '' ? null : line;
}

function truncate(value, max) {
  const text = (value ?? '').trim();
  if (text.length <= max) return text;

  return `${text.slice(0, max - 1).trimEnd()}…`;
}

export default LinkedInPostsSource;
