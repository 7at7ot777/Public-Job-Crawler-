import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import LinkedInGuestClient from './linkedinClient.js';
import {
  parseSitemapSites,
  pickCategories,
  parseOpportunities,
  parseCompanyName,
  parseJobDetail,
  jobUrl,
  isJunkTitle,
} from './recruiteraParser.js';

/** ISO country code -> the market a job there is in. */
const COUNTRY_MARKETS = { EG: 'Egypt', SA: 'Saudi Arabia', AE: 'United Arab Emirates' };

export const KEY = 'recruitera';

/**
 * Unanswered requests in a row that mean the network is down rather than one
 * site. Past this the run stops and says so, instead of timing out on every
 * remaining site and then reporting a clean pass that found nothing.
 */
export const OUTAGE_AFTER = 5;

/**
 * Recruitera, the ATS behind the career pages of Paymob, Fawry, Banque Misr
 * and some eighty other employers — most of them Egyptian.
 *
 * Read through the public JSON API its own careers pages call, found from the
 * sitemap rather than from a list of companies, so an employer that moves its
 * hiring onto Recruitera is picked up on the next run without anyone adding it:
 *
 *   sitemap.xml                         every company career site
 *   /{site}/opportunities/filters       which job categories it has open
 *   /{site}/opportunities?category=…    the jobs in the tech ones, by cursor
 *   /{site}/jobs/{slug}                 the country, the date and the text
 *
 * Most sites have no tech category open at all, which settles them in one
 * request. Only a job not seen on an earlier run is opened, so a daily run
 * after the first costs little more than one request per company.
 *
 * The API allows 60 requests a minute; the shared 2.5 s pacing is 24.
 */
export class RecruiteraSource {
  constructor(client = null, store = null) {
    this.key = KEY;
    this.client =
      client ??
      new LinkedInGuestClient({
        label: 'Recruitera',
        timeoutMs: config.sources[KEY].timeoutMs,
        headers: { Accept: 'application/json' },
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
    const maxAgeDays = options.maxAgeDays ?? this.settings.maxAgeDays;
    const cap = Math.min(options.limit ?? Infinity, this.settings.maxDetails);

    this.transportFailures = 0;
    this.sitemapUnreachable = false;

    const sites = await this.#sites();
    const checked = this.#checkedIds();
    const wanted = [];
    const seen = new Set();

    for (const site of sites) {
      let listed;

      try {
        listed = await this.#techJobs(site);
      } catch (error) {
        if (error instanceof BlockedError && !error.transport) {
          error.partialItems = [];
          throw error;
        }

        logger.warn('Could not list Recruitera jobs', { site, error: error.message });
        this.#unanswered(error, []);
        continue;
      }

      this.transportFailures = 0;

      for (const { job, category } of listed) {
        if (seen.has(job.id)) continue;
        seen.add(job.id);

        if (checked.has(job.id) || this.#alreadyStored(job.id)) continue;

        // Generated QA postings and untitled drafts. Dropped before their
        // page is opened, or a test account fills the whole per-run cap.
        if (isJunkTitle(job.title)) {
          this.#markChecked(job.id);
          continue;
        }

        // The listing states an age for most jobs, which spares opening a
        // page only to find it is months old. Age only grows, so it is never
        // worth opening later either.
        if (isAgeOver(job.days_since_posting, maxAgeDays)) {
          this.#markChecked(job.id);
          continue;
        }

        wanted.push({ site, job, category });
      }
    }

    logger.debug('Recruitera candidates', {
      sites: sites.length,
      techJobs: seen.size,
      unseen: wanted.length,
      fetching: Math.min(wanted.length, cap),
    });

    const items = await this.#fetchDetails(wanted.slice(0, cap), maxAgeDays);

    // Without the sitemap the run covered the hand-configured sites only.
    // What it found is delivered; the pass is still reported as one to redo.
    if (this.sitemapUnreachable) {
      throw outage('Recruitera sitemap was unreachable; only the configured sites were crawled', items);
    }

    return items;
  }

  /**
   * Counts a request that got no answer at all, and gives up on the run once
   * enough have failed in a row. A refusal is not counted: that is the far
   * end answering.
   *
   * @throws {BlockedError} retryable, carrying the items parsed so far.
   */
  #unanswered(error, items) {
    if (!(error instanceof BlockedError) || !error.transport) {
      this.transportFailures = 0;
      return;
    }

    this.transportFailures += 1;

    if (this.transportFailures >= OUTAGE_AFTER) {
      throw outage(
        `Recruitera unreachable: ${this.transportFailures} requests in a row got no answer (${error.message})`,
        items,
      );
    }
  }

  /** Career sites from the sitemap, plus any configured by hand. */
  async #sites() {
    const sites = new Set(this.settings.extraSites);

    try {
      for (const site of parseSitemapSites(await this.client.getHtml(this.settings.sitemapUrl))) {
        sites.add(site);
      }
    } catch (error) {
      if (error instanceof BlockedError && !error.transport) throw error;
      if (error instanceof BlockedError) this.sitemapUnreachable = true;

      // Without the sitemap only the configured sites are left, which is a
      // smaller run, not a reason to have none.
      logger.warn('Could not read the Recruitera sitemap', { error: error.message });
    }

    // Demo and test accounts: named by hand, plus any slug that says so.
    const excluded = new Set(this.settings.excludeSites.map((site) => site.toLowerCase()));

    return [...sites].filter((site) => !excluded.has(site.toLowerCase()) && !/^test(?:-|$)/i.test(site));
  }

  /** Every job a site has open in a wanted category. */
  async #techJobs(site) {
    const base = `${this.settings.apiBase}/${encodeURIComponent(site)}`;
    const categories = pickCategories(
      await this.client.getHtml(`${base}/opportunities/filters`),
      this.settings.categories,
    );

    const found = [];

    for (const category of categories) {
      let cursor = null;

      for (let page = 0; page < this.settings.maxPages; page += 1) {
        const { jobs, nextCursor } = parseOpportunities(
          await this.client.getHtml(`${base}/opportunities`, { category: category.id, cursor }),
        );

        for (const job of jobs) found.push({ job, category: category.name });

        if (!nextCursor || jobs.length === 0) break;
        cursor = nextCursor;
      }
    }

    return found;
  }

  /** @throws {BlockedError} carrying the items already parsed. */
  async #fetchDetails(candidates, maxAgeDays) {
    const items = [];
    const companies = new Map();

    for (const { site, job, category } of candidates) {
      const url = jobUrl(site, job.slug);
      let detail;

      try {
        detail = parseJobDetail(
          await this.client.getHtml(
            `${this.settings.apiBase}/${encodeURIComponent(site)}/jobs/${encodeURIComponent(job.slug)}`,
          ),
        );
      } catch (error) {
        if (error instanceof BlockedError && !error.transport) {
          error.partialItems = items;
          throw error;
        }

        logger.warn('Could not fetch Recruitera job', { url, error: error.message });
        this.#unanswered(error, items);
        continue;
      }

      this.transportFailures = 0;

      // Opened, so never open it again — whatever is decided below. A job in
      // Dubai is still in Dubai tomorrow.
      this.#markChecked(job.id);

      if (!detail) {
        logger.warn('Recruitera job did not parse', { url, ...(this.client.lastResponse ?? {}) });
        continue;
      }

      if (!detail.available || isJunkTitle(detail.title)) continue;

      if (!this.settings.countries.includes(detail.country)) {
        logger.debug('Recruitera job is outside the wanted countries', { url, country: detail.country });
        continue;
      }

      const postedAt = detail.publishedAt ?? ageToIso(job.days_since_posting);

      if (isOlderThan(postedAt, maxAgeDays)) continue;

      items.push({
        source: KEY,
        itemType: 'job',
        externalId: detail.id,
        url,
        title: detail.title,
        company: await this.#companyName(site, companies),
        location: detail.location,
        // The country came from the job itself, so the location filter has
        // nothing left to work out.
        // Saudi/UAE (BOARDS_GULF) as the market the Gulf rules read.
        market: COUNTRY_MARKETS[detail.country] ?? detail.location,
        remote: detail.workModel === 'remote',
        workplace: detail.workplace,
        employmentType: detail.employmentType,
        seniority: detail.experience,
        keyword: category,
        postedAt,
        excerpt: detail.excerpt,
      });
    }

    return items;
  }

  /**
   * The employer's name, which no job response carries. One request per site
   * with something to send, cached for the run, and the slug if it fails —
   * a message with an ugly company name beats no message.
   */
  async #companyName(site, cache) {
    if (!cache.has(site)) {
      let name = null;

      try {
        name = parseCompanyName(
          await this.client.getHtml(`${this.settings.apiBase}/${encodeURIComponent(site)}`),
        );
      } catch (error) {
        if (error instanceof BlockedError && !error.transport) throw error;
      }

      cache.set(site, name ?? site);
    }

    return cache.get(site);
  }

  #checkedIds() {
    if (!this.store?.checkedIds) return new Set();

    try {
      return this.store.checkedIds(KEY);
    } catch (error) {
      logger.warn('Could not read the Recruitera checked list', { error: error.message });
      return new Set();
    }
  }

  #markChecked(externalId) {
    try {
      this.store?.markChecked?.(KEY, externalId);
    } catch (error) {
      // Losing one ledger row costs a repeated fetch, nothing more.
      logger.warn('Could not record a checked Recruitera job', { error: error.message });
    }
  }

  #alreadyStored(externalId) {
    if (!this.store) return false;

    try {
      return this.store.findByExternalId(KEY, externalId) !== null;
    } catch (error) {
      logger.warn('Could not check Recruitera job against the store', { error: error.message });
      return false;
    }
  }
}

/**
 * A run cut short by the network. `retryable` tells the pipeline, and through
 * the exit code the crawl loop, that this pass should be run again soon rather
 * than counted as done. Nothing is lost by redoing it: a job is only marked
 * checked once its page has actually been read.
 */
function outage(message, items) {
  const error = new BlockedError(message, items, true);
  error.retryable = true;
  return error;
}

/** A listing age in days past the cap. Unknown is never too old. */
export function isAgeOver(days, maxAgeDays) {
  if (days === null || days === undefined || !Number.isFinite(maxAgeDays) || maxAgeDays <= 0) {
    return false;
  }

  return Number(days) > maxAgeDays;
}

function ageToIso(days) {
  if (days === null || days === undefined || !Number.isFinite(Number(days))) return null;
  return new Date(Date.now() - Number(days) * 86_400_000).toISOString();
}

/** A posting without a date is kept: an unknown age is not an old one. */
export function isOlderThan(postedAt, maxAgeDays) {
  if (!postedAt || !Number.isFinite(maxAgeDays) || maxAgeDays <= 0) return false;

  const posted = new Date(postedAt).getTime();
  if (Number.isNaN(posted)) return false;

  return Date.now() - posted > maxAgeDays * 86_400_000;
}

export default RecruiteraSource;
