import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import LinkedInGuestClient from './linkedinClient.js';
import { locationAllowed } from '../filters.js';
import { parseSearchResults, parseJobPage } from './tanqeebParser.js';

export const KEY = 'tanqeeb';

/**
 * Tanqeeb, an aggregator that re-lists other boards.
 *
 * Its value is reach: a card names the board it came from, and Bayt is among
 * them. Bayt's own site answers 403 to anything that is not a browser, so
 * this is the one route to its listings that does not involve defeating
 * somebody's bot protection.
 *
 * The cost is duplication. Most of what Tanqeeb returns is already on
 * LinkedIn, and the store's fingerprint check — company and title, across
 * sources — is what keeps a job found twice from being messaged twice.
 */
export class TanqeebSource {
  constructor(client = null) {
    this.key = KEY;
    this.client = client ?? new LinkedInGuestClient({ label: 'Tanqeeb' });
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
      logger.warn('Tanqeeb source has no keywords configured');
      return [];
    }

    // The same posting answers to several keywords, and can be listed on
    // more than one country's site; the first site to list it places it.
    const seen = new Map();

    search: for (const [searchUrl, market] of Object.entries(this.settings.sites)) {
      for (const keyword of keywords) {
        try {
          await this.#search({ searchUrl, market }, keyword, maxPages, limit, maxAgeDays, seen);
        } catch (error) {
          if (error instanceof BlockedError) {
            error.partialItems = [...seen.values()];
          }
          throw error;
        }

        if (limit !== null && seen.size >= limit) break search;
      }
    }

    return this.#enrich([...seen.values()]);
  }

  /**
   * Open each posting for the employer name and the description.
   *
   * Not optional in practice: a card carries no company, and the company is
   * half of the fingerprint the store dedups on — without it, every job
   * Tanqeeb shares with LinkedIn would be delivered twice. The description
   * also gives the tech filter something to read besides a job title.
   *
   * One paced request per posting, so it is capped, and the cap is spent on
   * postings that could still pass.
   */
  async #enrich(items) {
    if (!this.settings.fetchDetails || items.length === 0) return items;

    const candidates = config.filters.enabled
      ? items.filter((item) => this.#couldPass(item))
      : items;

    const wanted = candidates.slice(0, this.settings.maxDetails);

    logger.debug('Fetching Tanqeeb details', {
      candidates: candidates.length,
      skipped: items.length - candidates.length,
      fetching: wanted.length,
    });

    for (const item of wanted) {
      try {
        const page = await this.client.getHtml(item.url);
        const { company, excerpt } = parseJobPage(page);

        item.company = company ?? item.company;
        item.excerpt = excerpt ?? item.excerpt;
      } catch (error) {
        // Throttling ends the run; one unreachable page does not.
        if (error instanceof BlockedError && !error.transport) throw error;

        // The card already carries enough to judge and deliver the posting —
        // only the employer name and description are lost.
        logger.warn('Could not fetch Tanqeeb job', { url: item.url, error: error.message });
      }
    }

    return items;
  }

  /**
   * Cheap pre-filter on the card alone, before spending a request on it.
   *
   * Rejects only on what a description cannot overturn: where the job is, and
   * a title that openly says senior. The technology check is left to the real
   * filter, because that is exactly what the description is fetched for.
   */
  #couldPass(item) {
    const { locations, seniorityExclude } = config.filters;

    if (locations.length > 0 && !locationAllowed(item, (item.location ?? '').toLowerCase())) {
      return false;
    }

    const title = (item.title ?? '').toLowerCase();

    return !seniorityExclude.some((term) => matchesWord(title, term));
  }

  async #search({ searchUrl, market }, keyword, maxPages, limit, maxAgeDays, seen) {
    for (let page = 1; page <= maxPages; page += 1) {
      const html = await this.client.getHtml(searchUrl, {
        keywords: searchTerm(keyword),
        page,
        // Relevance order, the default, puts months-old postings on page 1,
        // where the age cut then drops all of them.
        order_by: 'most_recent',
      });

      // An empty body is the client's "4xx, nothing here".
      if (html.trim() === '') return;

      const results = parseSearchResults(html, keyword);

      // No cards is normally just the end of the results — but a page that
      // came back large and still yielded nothing is a different thing, and
      // without this the two are indistinguishable in a remote log.
      if (results.length === 0) {
        if (html.length > 2000) {
          logger.warn('Tanqeeb returned a page with no job cards', {
            market,
            keyword,
            page,
            ...(this.client.lastResponse ?? {}),
            title: (html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1] ?? '')
              .replace(/\s+/g, ' ')
              .trim()
              .slice(0, 120),
            hasCardClass: html.includes('search-job-card'),
            looksBlocked: /just a moment|captcha|access denied|unusual traffic/i.test(html),
          });
        }

        return;
      }

      for (const result of results) {
        if (isOlderThan(result.postedAt, maxAgeDays)) continue;

        if (!seen.has(result.externalId)) {
          seen.set(result.externalId, {
            source: KEY,
            itemType: 'job',
            company: null,
            // The site searched is one country's, so the country is known
            // without reading it back out of the card.
            market,
            ...result,
          });
        }

        if (limit !== null && seen.size >= limit) return;
      }
    }
  }
}

/**
 * The keyword as Tanqeeb's search will accept it.
 *
 * A dot sends it to the homepage instead of a result page — "Node.js
 * Developer" returns nothing while "Node js Developer" returns a full page of
 * the same jobs. It answers 200 either way, so the failure is silent, and the
 * keyword that triggers it is one of the defaults.
 */
export function searchTerm(keyword) {
  return String(keyword ?? '')
    .replace(/[.]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Term as a whole word, so "sr" does not match "srl" or "assr". */
function matchesWord(text, term) {
  const needle = term.toLowerCase().trim();
  if (needle === '') return false;

  const escaped = needle.replace(/[.*+?^${}()|[\]\\]/g, (char) => `\\${char}`);
  return new RegExp(`(?<![a-z0-9])${escaped}(?![a-z0-9])`, 'i').test(text);
}

/** A card without a date is kept: an unknown age is not an old one. */
export function isOlderThan(postedAt, maxAgeDays) {
  if (!postedAt || !Number.isFinite(maxAgeDays) || maxAgeDays <= 0) return false;

  const posted = new Date(postedAt).getTime();
  if (Number.isNaN(posted)) return false;

  return Date.now() - posted > maxAgeDays * 86_400_000;
}

export default TanqeebSource;
