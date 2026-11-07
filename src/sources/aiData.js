import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import LinkedInGuestClient from './linkedinClient.js';
import { parseSearchResponse } from './workableParser.js';
import { parseBoard, parseBoardResponse, PLATFORMS } from './atsParser.js';
import { placedIn, isOlderThan } from './workable.js';
import { filtersFor } from '../filters.js';
import { dedupKey, modalitiesOf, placeWanted, variantRank } from './aiDataParser.js';

export const KEY = 'ai_data';

/** A location entry that means "search with no location at all". */
const ANYWHERE = new Set(['anywhere', 'worldwide', 'global', 'remote']);

/**
 * AI data work: labelling, annotation, transcription and the rating and
 * evaluation of model output, on audio, images, video and text, Arabic
 * especially. Freelance and contract gigs as much as staff roles.
 *
 * Two lanes, both through feeds the other sources already read:
 *
 *  - jobs.workable.com, searched with labelling keywords. The vendors and the
 *    agencies they hire through advertise there, and none has to be named.
 *    A location of "Anywhere" searches with no location, which is where most
 *    of this work is listed.
 *  - Named vendors' own career boards (AI_DATA_BOARDS), in the ATS source's
 *    platform:slug form, for the vendors that hire on Lever, Greenhouse,
 *    Ashby and the rest rather than on Workable.
 *
 * A vendor's board is everything it has open, engineers and sales included,
 * so this source's own title rule (filters.titleTerms in config) is what
 * narrows both lanes to the labelling roles. The stack filter does not apply:
 * these jobs name no stack.
 */
export class AiDataSource {
  constructor(client = null) {
    this.key = KEY;
    this.client =
      client ??
      new LinkedInGuestClient({
        label: 'AI data',
        timeoutMs: config.sources[KEY].timeoutMs,
        delayMs: config.sources[KEY].delayMs,
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
    const keywords = options.keywords?.length ? options.keywords : this.settings.keywords;
    const locations = options.locations?.length ? options.locations : this.settings.locations;
    const maxPages = options.pages ?? this.settings.maxPages;
    const maxAgeDays = options.maxAgeDays ?? this.settings.maxAgeDays;
    const limit = options.limit ?? null;

    // Keyed by company + title (dedupKey): the same job answers to several
    // keywords, a vendor that lists on Workable and on its own board is the
    // same posting, and a vendor posts one requisition per country.
    const seen = new Map();
    const full = () => limit !== null && seen.size >= limit;

    try {
      if (this.settings.searchUrl && keywords.length > 0 && locations.length > 0) {
        for (const location of locations) {
          for (const keyword of keywords) {
            await this.#search(keyword, location, maxPages, maxAgeDays, seen, full);
            if (full()) return [...seen.values()];
          }
        }
      }

      await this.#boards(maxAgeDays, seen, full);
    } catch (error) {
      if (error instanceof BlockedError) error.partialItems = [...seen.values()];
      throw error;
    }

    return [...seen.values()];
  }

  async #search(keyword, location, maxPages, maxAgeDays, seen, full) {
    const anywhere = ANYWHERE.has(String(location).trim().toLowerCase());
    let pageToken = null;

    for (let page = 0; page < maxPages; page += 1) {
      const body = await this.client.getHtml(this.settings.searchUrl, {
        query: keyword,
        // The client drops a null parameter, which leaves the search borderless.
        location: anywhere ? null : location,
        day_range: maxAgeDays > 0 ? maxAgeDays : null,
        pageToken,
      });

      const { items, nextPageToken } = parseSearchResponse(body, keyword);
      if (items.length === 0) return;

      for (const { country, countries, ...job } of items) {
        if (isOlderThan(job.postedAt, maxAgeDays)) continue;

        this.#keep(
          {
            ...job,
            market: !anywhere && placedIn(location, country, countries) ? location : null,
          },
          seen,
        );

        if (full()) return;
      }

      if (!nextPageToken) return;
      pageToken = nextPageToken;
    }
  }

  async #boards(maxAgeDays, seen, full) {
    for (const entry of this.settings.boards) {
      const board = parseBoard(entry);

      if (!board) {
        logger.warn('Ignoring AI data board with an unknown platform', {
          entry,
          platforms: Object.keys(PLATFORMS),
        });
        continue;
      }

      let jobs;

      try {
        const body = await this.client.getHtml(PLATFORMS[board.platform].url(board.slug));
        jobs = parseBoardResponse(body, board);
      } catch (error) {
        // One vendor's board being down is not a reason to skip the rest.
        if (error instanceof BlockedError && !error.transport) throw error;

        logger.warn('Could not read AI data board', { board: entry, error: error.message });
        continue;
      }

      // A renamed or closed board answers 404, which arrives here as nothing.
      if (jobs === null) {
        logger.warn('AI data board returned no feed', {
          board: entry,
          ...(this.client.lastResponse ?? {}),
        });
        continue;
      }

      for (const job of jobs) {
        if (isOlderThan(job.postedAt, maxAgeDays)) continue;

        this.#keep({ ...job, market: null }, seen);
        if (full()) return;
      }
    }
  }

  #keep(job, seen) {
    const item = { source: KEY, itemType: 'job', ...job };
    if (!placeWanted(item, this.settings.places)) return;

    // A second variant of a held role replaces it only when the filter is
    // likelier to want it; Map.set on an existing key keeps first-seen order.
    const key = dedupKey(item);
    const held = seen.get(key);
    if (held) {
      const blocked = filtersFor(KEY).blockedLocations ?? [];
      if (variantRank(item, blocked) >= variantRank(held, blocked)) return;
    }

    // The "Matched" line says what the work is on, not which search found it.
    item.keyword = modalitiesOf(item) ?? 'AI data';
    seen.set(key, item);
  }
}

export default AiDataSource;
