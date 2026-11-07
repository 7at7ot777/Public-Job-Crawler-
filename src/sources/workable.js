import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import LinkedInGuestClient from './linkedinClient.js';
import { parseSearchResponse } from './workableParser.js';

export const KEY = 'workable';

/**
 * jobs.workable.com, the board that lists every Workable customer's openings
 * in one search — Robusta, Lucidya, Nawy, Salla and hundreds of others in
 * Egypt and the Gulf, none of which has to be named here.
 *
 * Read through the JSON endpoint its own search page calls. robots.txt only
 * fences off the HTML search pages, not /api. One request per keyword x
 * location page, and the response carries everything the message needs.
 *
 * day_range is applied on Workable's side, so a search only returns what is
 * recent enough to send; the age check below is the same rule kept honest.
 */
export class WorkableSource {
  constructor(client = null) {
    this.key = KEY;
    this.client =
      client ??
      new LinkedInGuestClient({
        label: 'Workable',
        timeoutMs: config.sources[KEY].timeoutMs,
        headers: { Accept: 'application/json' },
      });
  }

  get settings() {
    return config.sources[KEY];
  }

  isEnabled() {
    return Boolean(this.settings.enabled);
  }

  /**
   * @param {{keywords?: string[], locations?: string[], limit?: number, pages?: number, maxAgeDays?: number}} options
   * @returns {Promise<object[]>}
   * @throws {BlockedError} carrying whatever was parsed before the block.
   */
  async fetch(options = {}) {
    const keywords = options.keywords?.length
      ? options.keywords
      : (this.settings.keywords ?? config.keywords);
    const locations = options.locations?.length
      ? options.locations
      : (this.settings.locations ?? config.locations);
    const maxPages = options.pages ?? this.settings.maxPages;
    const limit = options.limit ?? null;
    const maxAgeDays = options.maxAgeDays ?? this.settings.maxAgeDays;

    if (keywords.length === 0 || locations.length === 0) {
      logger.warn('Workable source has no keywords or locations configured');
      return [];
    }

    // The same posting answers to several keywords.
    const seen = new Map();

    /*
     * BOARDS_GULF (the Android app's switch): Saudi Arabia and the UAE after
     * the home markets, one page each. Not for a run given its own locations.
     */
    const searches = locations.map((location) => [location, maxPages]);
    if (config.boardsGulf && !options.locations?.length) {
      for (const country of this.settings.gulfLocations ?? []) {
        if (!locations.includes(country)) searches.push([country, Math.min(maxPages, 1)]);
      }
    }

    for (const [location, pages] of searches) {
      for (const keyword of keywords) {
        try {
          await this.#search(keyword, location, pages, limit, maxAgeDays, seen);
        } catch (error) {
          if (error instanceof BlockedError) {
            error.partialItems = [...seen.values()];
          }
          throw error;
        }

        if (limit !== null && seen.size >= limit) return [...seen.values()];
      }
    }

    return [...seen.values()];
  }

  async #search(keyword, location, maxPages, limit, maxAgeDays, seen) {
    let pageToken = null;

    for (let page = 0; page < maxPages; page += 1) {
      const body = await this.client.getHtml(this.settings.searchUrl, {
        query: keyword,
        location,
        day_range: maxAgeDays > 0 ? maxAgeDays : null,
        pageToken,
      });

      const { items, nextPageToken } = parseSearchResponse(body, keyword);

      if (items.length === 0) {
        if ((body ?? '').length > 500) {
          logger.warn('Workable returned a response with no jobs', {
            keyword,
            location,
            page,
            ...(this.client.lastResponse ?? {}),
          });
        }

        return;
      }

      for (const { country, countries, ...item } of items) {
        if (isOlderThan(item.postedAt, maxAgeDays)) continue;

        if (!seen.has(item.externalId)) {
          seen.set(item.externalId, {
            source: KEY,
            itemType: 'job',
            /*
             * The searched country, when the job really is there. Workable's
             * location search also returns jobs that merely accept the
             * country among many; those are left to the location filter,
             * which reads what the posting says.
             */
            market: placedIn(location, country, countries) ? location : null,
            ...item,
          });
        }

        if (limit !== null && seen.size >= limit) return;
      }

      if (!nextPageToken) return;
      pageToken = nextPageToken;
    }
  }
}

/** Whether a job's own country, or one of its places, is the searched one. */
export function placedIn(location, country, countries = []) {
  const wanted = String(location ?? '').trim().toLowerCase();
  if (wanted === '') return false;

  if (String(country ?? '').trim().toLowerCase() === wanted) return true;

  return countries.some((place) => {
    const parts = String(place).split(',');
    return parts[parts.length - 1].trim().toLowerCase() === wanted;
  });
}

/** A posting without a date is kept: an unknown age is not an old one. */
export function isOlderThan(postedAt, maxAgeDays) {
  if (!postedAt || !Number.isFinite(maxAgeDays) || maxAgeDays <= 0) return false;

  const posted = new Date(postedAt).getTime();
  if (Number.isNaN(posted)) return false;

  return Date.now() - posted > maxAgeDays * 86_400_000;
}

export default WorkableSource;
