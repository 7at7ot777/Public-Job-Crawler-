import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import LinkedInGuestClient from './linkedinClient.js';
import { parseJobPage, extractExternalId } from './wuzzufParser.js';

export const KEY = 'wuzzuf';

/**
 * Wuzzuf, read through its sitemap.
 *
 * Its search pages are behind Cloudflare and answer 403 to anything that is
 * not a browser, so they are never requested. The sitemap and the job pages
 * themselves are open, and robots.txt allows both — which makes the sitemap
 * the discovery route rather than a workaround.
 *
 * The catch is that every URL in it carries the same <lastmod>: the time the
 * sitemap was generated, not the time the job was posted. So freshness cannot
 * come from the listing, and is read off each job page instead ("3 days ago").
 * That makes two filters necessary before any page is fetched, or a run would
 * pull thousands of pages to find a handful:
 *
 *   1. the slug, which names the role — 13 of 5,306 URLs matched a backend
 *      stack the day this was written;
 *   2. what is already stored, since a sitemap lists every live job every
 *      time, not just the new ones.
 */
export class WuzzufSource {
  /**
   * @param {object|null} client
   * @param {{findByExternalId: Function}|null} store  used to skip jobs already
   *        crawled; without it every run re-fetches the whole matching set.
   */
  constructor(client = null, store = null) {
    this.key = KEY;
    this.client =
      client ??
      new LinkedInGuestClient({
        label: 'Wuzzuf',
        timeoutMs: config.sources[KEY].timeoutMs,
        /*
         * The headers a browser sends when you follow a link, which is what
         * this request is. Wuzzuf's job pages answer 403 from some networks
         * while serving the same URL freely to others, so the difference is
         * the client, the address, or both — and only one of those is ours to
         * change.
         *
         * Best effort, and honestly so: it may make no difference from a
         * blocked address.
         */
        headers: {
          'Upgrade-Insecure-Requests': '1',
          'Sec-Fetch-Dest': 'document',
          'Sec-Fetch-Mode': 'navigate',
          'Sec-Fetch-Site': 'none',
          'Sec-Fetch-User': '?1',
        },
      });
    this.store = store;
  }

  get settings() {
    return config.sources[KEY];
  }

  isEnabled() {
    return Boolean(this.settings.enabled);
  }

  /** The pipeline hands the store over so the source can skip known jobs. */
  useStore(store) {
    this.store = store;
  }

  /**
   * @param {{limit?: number, maxAgeDays?: number}} options
   * @returns {Promise<object[]>}
   * @throws {BlockedError} carrying whatever was parsed before the block.
   */
  async fetch(options = {}) {
    const limit = options.limit ?? null;
    const maxAgeDays = options.maxAgeDays ?? this.settings.maxAgeDays;
    const cap = Math.min(limit ?? Infinity, this.settings.maxDetails);

    const candidates = [];

    for (const [sitemapUrl, market] of await this.#sitemaps()) {
      try {
        const xml = await this.client.getHtml(sitemapUrl);
        candidates.push(...this.#candidates(xml, market));
      } catch (error) {
        if (error instanceof BlockedError) throw error;

        // One unreadable sitemap must not cost us the other.
        logger.warn('Could not read Wuzzuf sitemap', { sitemapUrl, error: error.message });
      }
    }

    const checked = this.#checkedIds();
    const wanted = [];

    // The sitemaps overlap — a Saudi job is listed in both — so the same URL
    // would otherwise be opened twice in one run, and a dead one retried
    // twice before the ledger takes effect on the next run.
    const seen = new Set();

    for (const candidate of candidates) {
      if (seen.has(candidate.externalId)) continue;
      seen.add(candidate.externalId);

      if (checked.has(candidate.externalId)) continue;
      if (this.#alreadyStored(candidate.externalId)) continue;

      wanted.push(candidate);
    }

    logger.debug('Wuzzuf candidates', {
      matchedSlug: candidates.length,
      unseen: wanted.length,
      fetching: Math.min(wanted.length, cap),
    });

    return this.#fetchPages(wanted.slice(0, cap), maxAgeDays);
  }

  /** @throws {BlockedError} carrying the items already parsed. */
  async #fetchPages(candidates, maxAgeDays) {
    const items = [];

    for (const candidate of candidates) {
      let html;

      try {
        html = await this.client.getHtml(candidate.url);
      } catch (error) {
        // Being throttled means stop — every later request would be refused
        // too. One unreachable page means skip it: the sitemap carries URLs
        // that no longer resolve, and one of those must not end the run.
        if (error instanceof BlockedError && !error.transport) {
          error.partialItems = items;
          throw error;
        }

        logger.warn('Could not fetch Wuzzuf job', { url: candidate.url, error: error.message });

        // Remembered as checked, or every run from here on stops on it again.
        this.#markChecked(candidate.externalId);
        continue;
      }

      // Opened, so never open it again — whatever is decided below.
      this.#markChecked(candidate.externalId);

      const parsed = parseJobPage(html, candidate.url);

      if (!parsed) {
        logger.warn('Wuzzuf page did not parse', {
          url: candidate.url,
          ...(this.client.lastResponse ?? {}),
        });
        continue;
      }

      if (isOlderThan(parsed.postedAt, maxAgeDays)) {
        logger.debug('Wuzzuf job is older than the age limit', {
          url: candidate.url,
          postedAt: parsed.postedAt,
        });
        continue;
      }

      items.push({
        source: KEY,
        itemType: 'job',
        url: candidate.url,
        // The sitemap the URL came from says which country it is, which the
        // location filter then trusts instead of re-reading the text.
        market: candidate.market,
        keyword: candidate.matched,
        ...parsed,
      });
    }

    return items;
  }

  /**
   * URLs whose slug names a role worth opening.
   *
   * The slug is all a sitemap gives, and it is enough: a Wuzzuf job URL ends
   * in the role, the company and the city.
   */
  /**
   * The job sitemaps to read this run, as the Android app finds them: from
   * Wuzzuf's sitemap index, per market the one changed most recently (that
   * is where new jobs are). The configured ones when the index cannot be
   * read or names none, or when WUZZUF_DISCOVER_SITEMAPS=false.
   *
   * @returns {Promise<[string, string][]>} [sitemap URL, market] pairs
   */
  async #sitemaps() {
    const configured = Object.entries(this.settings.sitemaps);
    if (!this.settings.discover) return configured;

    let index = '';
    try {
      index = (await this.client.getHtml(this.settings.indexUrl)) ?? '';
    } catch (error) {
      if (error instanceof BlockedError && !error.transport) throw error;
      logger.warn('Wuzzuf: could not read the sitemap index', { error: error.message });
    }

    // Only the markets configured (Saudi Arabia only with BOARDS_GULF).
    const wanted = new Set(configured.map(([, market]) => market));
    const listed = jobSitemaps(index).filter((sitemap) => wanted.has(sitemap.market));

    if (listed.length === 0) {
      logger.warn('Wuzzuf: the sitemap index named no job sitemaps; reading the known ones');
      return configured;
    }

    const chosen = chooseSitemaps(listed);
    logger.info('Wuzzuf sitemaps', {
      inIndex: listed.length,
      reading: chosen.map((sitemap) => `${sitemap.url.split('/').pop()}${sitemap.lastmod ? ` (${sitemap.lastmod})` : ''}`),
    });
    return chosen.map((sitemap) => [sitemap.url, sitemap.market]);
  }

  #candidates(xml, market) {
    const terms = this.settings.slugTerms;
    const found = [];

    for (const match of (xml ?? '').matchAll(/<loc>([^<]+)<\/loc>/g)) {
      const url = match[1];
      const externalId = extractExternalId(url);
      if (!externalId) continue;

      const slug = url.toLowerCase();
      const matched = terms.find((term) => slug.includes(term.toLowerCase()));

      if (matched) found.push({ url, externalId, market, matched });
    }

    return found;
  }

  /** Pages opened on an earlier run, kept whether or not they were stored. */
  #checkedIds() {
    if (!this.store?.checkedIds) return new Set();

    try {
      return this.store.checkedIds(KEY);
    } catch (error) {
      logger.warn('Could not read the Wuzzuf checked list', { error: error.message });
      return new Set();
    }
  }

  #markChecked(externalId) {
    try {
      this.store?.markChecked?.(KEY, externalId);
    } catch (error) {
      // Losing one ledger row costs a repeated fetch, nothing more.
      logger.warn('Could not record a checked Wuzzuf job', { error: error.message });
    }
  }

  /**
   * A sitemap lists every live job on every run, so without this the same
   * pages are fetched again every hour to be discarded by the store.
   */
  #alreadyStored(externalId) {
    if (!this.store) return false;

    try {
      return this.store.findByExternalId(KEY, externalId) !== null;
    } catch (error) {
      // A store that cannot answer is a reason to do more work, not to crash.
      logger.warn('Could not check Wuzzuf job against the store', { error: error.message });
      return false;
    }
  }
}

/** Wuzzuf states an age, not a date; a page without one is kept, not guessed at. */
export function isOlderThan(postedAt, maxAgeDays) {
  if (!postedAt || !Number.isFinite(maxAgeDays) || maxAgeDays <= 0) return false;

  const posted = new Date(postedAt).getTime();
  if (Number.isNaN(posted)) return false;

  return Date.now() - posted > maxAgeDays * 86_400_000;
}

/* The country a job sitemap's prefix stands for: sitemap-job-1, sitemap-saudi-job-1. */
const SITEMAP_MARKETS = { '': 'Egypt', saudi: 'Saudi Arabia' };
const JOB_SITEMAP_RE = /\/sitemap-(?:([a-z]+)-)?job-(\d+)\.xml$/i;

/** The job sitemaps a sitemap index names (not .gz: the client does not unpack it). */
export function jobSitemaps(index) {
  const seen = new Set();
  const out = [];

  for (const [, entry] of String(index ?? '').matchAll(/<sitemap>([\s\S]*?)<\/sitemap>/g)) {
    const url = entry.match(/<loc>([^<]+)<\/loc>/)?.[1]?.trim().replace(/&amp;/g, '&');
    const match = url?.match(JOB_SITEMAP_RE);
    const market = match ? SITEMAP_MARKETS[(match[1] ?? '').toLowerCase()] : null;
    if (!url || !market || seen.has(url)) continue;

    seen.add(url);
    out.push({ url, market, number: Number(match[2]), lastmod: entry.match(/<lastmod>([^<]+)<\/lastmod>/)?.[1]?.trim() ?? null });
  }

  return out;
}

/** Per market, the file changed most recently (the highest-numbered when undated), Egypt first. */
export function chooseSitemaps(sitemaps, perMarket = 1) {
  const byMarket = new Map();
  for (const sitemap of sitemaps) {
    if (!byMarket.has(sitemap.market)) byMarket.set(sitemap.market, []);
    byMarket.get(sitemap.market).push(sitemap);
  }

  return [...byMarket.entries()]
    .sort(([a], [b]) => (a === 'Egypt' ? 0 : 1) - (b === 'Egypt' ? 0 : 1))
    .flatMap(([, files]) =>
      files
        .sort((a, b) => String(b.lastmod ?? '').localeCompare(String(a.lastmod ?? '')) || b.number - a.number)
        .slice(0, perMarket),
    );
}

export default WuzzufSource;
