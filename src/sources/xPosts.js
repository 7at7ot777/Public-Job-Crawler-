import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import LinkedInGuestClient from './linkedinClient.js';
import { resolveEngine } from './searchEngines.js';
import { planQueries, placeFor, YieldLedger } from './searchPlan.js';
import SerperBudget, { logSearchBudget } from './serperBudget.js';
import { parseStatusUrl, statusUrl, snowflakeDate, olderThanDays, truncate } from './xShared.js';

export const KEY = 'x_posts';

const TITLE_MAX = 140;

/**
 * Hiring posts on X, found through a search engine's index.
 *
 * The LinkedIn posts source's approach, minus its second half: X serves a
 * logged-out client a login wall and nothing else, so the permalink cannot be
 * opened and the search snippet is the whole of what the filters read. That
 * makes this the low-recall route — Google indexes a small share of X — and
 * x_session the high-recall one. Both find the same posts under the same id,
 * and the store's repost check keeps the second copy from being sent.
 */
export class XPostsSource {
  constructor(searchClient = null) {
    this.key = KEY;
    this.injectedSearchClient = searchClient;
    this.store = null;
    this.alerts = [];
  }

  /** As LinkedInPostsSource.useStore: the Serper count and each query's yield. */
  useStore(store) {
    this.store = store;
  }

  takeAlerts() {
    const alerts = this.alerts;
    this.alerts = [];
    return alerts;
  }

  /** Built on first use, for the same reason as LinkedInPostsSource.search. */
  get search() {
    this.searchClient ??=
      this.injectedSearchClient ??
      new LinkedInGuestClient({
        label: this.engine.label,
        headers: this.engine.headers?.(this.settings) ?? {},
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
    const keywords = options.keywords?.length ? options.keywords : this.settings.keywords;
    const locations = options.locations?.length ? options.locations : this.settings.locations;
    const maxPages = options.pages ?? this.settings.maxPages;
    const limit = options.limit ?? null;
    const timeWindow = options.timeWindow ?? this.settings.timeWindow;

    if (keywords.length === 0 || locations.length === 0) {
      logger.warn('X posts source has no keywords or locations configured');
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

    const explicit = Boolean(options.keywords?.length || options.locations?.length);
    const due = explicit ? planned : planned.filter((query) => ledger.isDue(query));

    if (due.length < planned.length) {
      logger.info('X posts: skipping cold queries this run', {
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
        ledger.record(query, await this.#search(query, maxPages, limit, timeWindow, seen, budget));
      } catch (error) {
        if (error instanceof BlockedError) error.partialItems = [...seen.values()];
        throw error;
      }

      if (limit !== null && seen.size >= limit) return [...seen.values()];
    }

    return [...seen.values()];
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

      const body = engine.body
        ? await this.search.postJson(engine.url(this.settings), engine.body(this.settings, context))
        : await this.search.getHtml(
            engine.url(this.settings),
            engine.params(this.settings, context),
          );

      if (body.trim() === '') return fresh;

      const items = engine
        .parse(body)
        .map((result) =>
          toItem(result, planned.keyword, placeFor(planned, result.title, result.snippet)),
        )
        .filter(Boolean);

      // Nothing usable ends this query; a narrow pair legitimately finds none.
      if (items.length === 0) return fresh;

      for (const item of items) {
        if (this.#tooOld(item)) continue;

        fresh += 1;
        if (!seen.has(item.externalId)) seen.set(item.externalId, item);

        if (limit !== null && seen.size >= limit) return fresh;
      }
    }

    return fresh;
  }

  /**
   * The engine dates a result by when it indexed the page, so its time window
   * lets old posts through. The status id says when the post was written.
   */
  #tooOld(item) {
    return olderThanDays(item.postedAt, this.settings.maxAgeDays);
  }
}

/**
 * A search result as an item, or null when it is not a post.
 *
 * `market` is left unset for the reason given in LinkedInPostsSource#toItem: a
 * search engine only biases towards a location, so the post's own words decide.
 */
export function toItem(result, keyword, location) {
  const status = parseStatusUrl(result.url);
  if (!status) return null;

  const { name, text } = splitTitle(result.title);
  const author = name ? `${name} (@${status.handle})` : `@${status.handle}`;

  return {
    source: KEY,
    externalId: status.id,
    itemType: 'post',
    title: truncate(text ?? result.snippet ?? 'Post on X', TITLE_MAX),
    url: statusUrl(status.handle, status.id),
    company: author,
    location,
    keyword,
    excerpt: result.snippet ?? text ?? null,
    postedAt: snowflakeDate(status.id) ?? result.postedAt ?? null,
  };
}

/**
 * Google titles an X post `<Name> on X: "<text>" / X`, sometimes with the
 * handle, sometimes cut short. Returns whichever halves are there.
 *
 * @returns {{name: string|null, text: string|null}}
 */
export function splitTitle(title) {
  const value = (title ?? '').replace(/\s*[/|]\s*X\s*$/i, '').trim();
  if (value === '') return { name: null, text: null };

  const match = value.match(/^(.+?)\s+on\s+(?:X|Twitter)(?:\s*:\s*(.*))?$/i);
  if (!match) return { name: null, text: value };

  // Google often keeps only "<Name> on X", with the post text in the snippet.
  const name = match[1].replace(/\s*\(@\w+\)\s*$/, '').trim();
  if (match[2] === undefined) return { name: name || null, text: null };

  const text = match[2].replace(/^["“]|["”](?:\s*…)?$/g, '').trim();

  return { name: name || null, text: text || null };
}

export default XPostsSource;
