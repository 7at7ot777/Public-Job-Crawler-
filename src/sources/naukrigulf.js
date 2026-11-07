import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import LinkedInGuestClient from './linkedinClient.js';
import { parseSearchResponse } from './naukrigulfParser.js';

export const KEY = 'naukrigulf';

/**
 * Naukrigulf, read through the JSON endpoint its own pages call.
 *
 * Its listings are already reachable second-hand — Tanqeeb re-lists them —
 * but a Tanqeeb card names no employer, which is half of the fingerprint the
 * store dedups on, and it costs a request per posting to recover. Here the
 * search response carries the employer, the description and the posted date
 * outright, so a keyword costs exactly one request and nothing is opened
 * afterwards. It is the cheapest source in the project.
 *
 * Searches name no country on purpose. The endpoint takes a Location, but
 * running every keyword against every market multiplies requests for results
 * the location filter can pick out of one unfiltered page just as well.
 */
export class NaukrigulfSource {
  constructor(client = null) {
    this.key = KEY;
    this.client =
      client ??
      new LinkedInGuestClient({
        label: 'Naukrigulf',
        timeoutMs: config.sources[KEY].timeoutMs,
        // The ids the site's own front-end sends. Without them the endpoint
        // answers 404 rather than 403 — it is an identifier for the calling
        // app, not a credential or a session.
        headers: {
          appid: String(config.sources[KEY].appId),
          systemid: String(config.sources[KEY].systemId),
          Accept: 'application/json',
        },
      });
  }

  get settings() {
    return config.sources[KEY];
  }

  isEnabled() {
    return Boolean(this.settings.enabled);
  }

  /**
   * @param {{keywords?: string[], limit?: number, pages?: number, maxAgeDays?: number}} options
   * @returns {Promise<object[]>}
   * @throws {BlockedError} carrying whatever was parsed before the block.
   */
  async fetch(options = {}) {
    const keywords = options.keywords?.length ? options.keywords : config.keywords;
    const maxPages = options.pages ?? this.settings.maxPages;
    const limit = options.limit ?? null;
    const maxAgeDays = options.maxAgeDays ?? this.settings.maxAgeDays;

    if (keywords.length === 0) {
      logger.warn('Naukrigulf source has no keywords configured');
      return [];
    }

    // The same posting answers to several keywords.
    const seen = new Map();

    for (const keyword of keywords) {
      try {
        await this.#search(keyword, maxPages, limit, maxAgeDays, seen);
      } catch (error) {
        if (error instanceof BlockedError) {
          error.partialItems = [...seen.values()];
        }
        throw error;
      }

      if (limit !== null && seen.size >= limit) break;
    }

    return [...seen.values()];
  }

  async #search(keyword, maxPages, limit, maxAgeDays, seen) {
    const { searchUrl, pageSize } = this.settings;

    for (let page = 0; page < maxPages; page += 1) {
      const offset = page * pageSize;

      const body = await this.client.getHtml(searchUrl, {
        Keywords: keyword,
        Limit: pageSize,
        Offset: offset,
        // Newest first, which is what makes one page per keyword enough.
        SortBy: 1,
      });

      const { items, total } = parseSearchResponse(body, keyword);

      // Nothing parsed out of a response that was not empty means the shape
      // changed or something answered in place of the endpoint. Worth saying,
      // because otherwise it reads identically to "no jobs match".
      if (items.length === 0) {
        if ((body ?? '').length > 500) {
          logger.warn('Naukrigulf returned a response with no jobs', {
            keyword,
            offset,
            ...(this.client.lastResponse ?? {}),
          });
        }

        return;
      }

      for (const item of items) {
        if (isOlderThan(item.postedAt, maxAgeDays)) continue;

        if (!seen.has(item.externalId)) {
          seen.set(item.externalId, {
            source: KEY,
            itemType: 'job',
            /*
             * Left unset deliberately. A market tells the location filter
             * "the search already decided where this is" and skips the text
             * check — true of a per-country site like Tanqeeb, false here,
             * where an unfiltered search returns Casablanca and Bangalore
             * alongside Dubai. Null makes the filter read the location
             * itself, which names the country in full.
             */
            market: null,
            ...item,
          });
        }

        if (limit !== null && seen.size >= limit) return;
      }

      // The endpoint keeps answering past the end of the results with an empty
      // list; the count says when to stop asking.
      if (total !== null && offset + items.length >= total) return;
    }
  }
}

/** A posting without a date is kept: an unknown age is not an old one. */
export function isOlderThan(postedAt, maxAgeDays) {
  if (!postedAt || !Number.isFinite(maxAgeDays) || maxAgeDays <= 0) return false;

  const posted = new Date(postedAt).getTime();
  if (Number.isNaN(posted)) return false;

  return Date.now() - posted > maxAgeDays * 86_400_000;
}

export default NaukrigulfSource;
