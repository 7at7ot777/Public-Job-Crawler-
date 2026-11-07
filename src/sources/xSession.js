import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import XSessionClient from './xSessionClient.js';
import { parseSearchTimeline } from './xTimelineParser.js';
import { statusUrl, snowflakeDate, olderThanDays, firstLine, truncate } from './xShared.js';

export const KEY = 'x_session';

const TITLE_MAX = 140;

/**
 * Hiring posts on X, read through X's own search while signed in by cookie.
 *
 * The high-recall counterpart to x_posts: every post X has, in full, minutes
 * after it is written — at the price of X's terms and a real account. See the
 * config block for the account advice. A refusal of any kind ends the run with
 * a BlockedError naming what to fix; nothing X does is read as "no results".
 */
export class XSessionSource {
  constructor(client = null) {
    this.key = KEY;
    this.injectedClient = client;
  }

  get settings() {
    return config.sources[KEY];
  }

  /** Off without both cookies, so enabling it early cannot fail every run. */
  isEnabled() {
    const settings = this.settings;
    return Boolean(settings.enabled && settings.authToken && settings.ct0);
  }

  /**
   * @param {{keywords?: string[], locations?: string[], pages?: number,
   *          limit?: number, timeWindow?: number}} options  timeWindow in days
   * @throws {BlockedError} carrying whatever was parsed before the block.
   */
  async fetch(options = {}) {
    const keywords = options.keywords?.length ? options.keywords : this.settings.keywords;
    const locations = options.locations?.length ? options.locations : this.settings.locations;
    const maxPages = options.pages ?? this.settings.maxPages;
    const limit = options.limit ?? null;
    const days = Number(options.timeWindow ?? this.settings.timeWindow) || 0;

    if (keywords.length === 0 || locations.length === 0) {
      logger.warn('X session source has no keywords or locations configured');
      return [];
    }

    // A fresh client per run, so the request budget is per run.
    const client = this.injectedClient ?? new XSessionClient(this.settings);
    const seen = new Map();

    for (const keyword of keywords) {
      for (const location of locations) {
        try {
          await this.#search(client, keyword, location, maxPages, limit, days, seen);
        } catch (error) {
          if (error instanceof BlockedError) error.partialItems = [...seen.values()];
          throw error;
        }

        if (limit !== null && seen.size >= limit) return [...seen.values()];
      }
    }

    return [...seen.values()];
  }

  async #search(client, keyword, location, maxPages, limit, days, seen) {
    const query = buildQuery(this.settings.queryTemplate, keyword, location, days);
    let cursor = null;

    for (let page = 0; page < maxPages; page += 1) {
      const { tweets, cursor: next } = parseSearchTimeline(await client.search(query, cursor));

      for (const tweet of tweets) {
        const item = toItem(tweet, keyword, location);
        if (!item || seen.has(item.externalId)) continue;

        // `since:` filters by date; the exact age is read from the post's own
        // timestamp so nothing older than the window slips through.
        if (olderThanDays(item.postedAt, this.settings.maxAgeDays)) continue;

        seen.set(item.externalId, item);
        if (limit !== null && seen.size >= limit) return;
      }

      // No posts or no further page: this query is done.
      if (tweets.length === 0 || !next || next === cursor) return;
      cursor = next;
    }
  }
}

/**
 * The template with its placeholders filled, plus `since:` for the window —
 * X applies that server-side, so old posts are never fetched at all.
 */
export function buildQuery(template, keyword, location, days, now = new Date()) {
  const query = template.replaceAll('{keyword}', keyword).replaceAll('{location}', location);
  if (!days || days <= 0) return query;

  const since = new Date(now.getTime() - days * 86_400_000).toISOString().slice(0, 10);
  return `${query} since:${since}`;
}

/**
 * A parsed post as an item. `market` is unset, as for every post source: the
 * location filter reads the post's own words.
 */
export function toItem(tweet, keyword, location) {
  if (!tweet?.id || !tweet.handle) return null;

  return {
    source: KEY,
    externalId: tweet.id,
    itemType: 'post',
    title: truncate(firstLine(tweet.text) ?? 'Post on X', TITLE_MAX),
    url: statusUrl(tweet.handle, tweet.id),
    // The author is who to approach; formatPost renders `company` as that.
    company: tweet.name ? `${tweet.name} (@${tweet.handle})` : `@${tweet.handle}`,
    location,
    keyword,
    excerpt: tweet.text || null,
    postedAt: tweet.createdAt ?? snowflakeDate(tweet.id),
  };
}

export default XSessionSource;
