import * as cheerio from 'cheerio';
import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import { toIso as datesToIso } from '../dates.js';
import { couldPass } from '../filters.js';
import { SAUDI, UAE } from '../gulf.js';
import { clean, stripHtml } from '../text.js';
import BaseSource from './baseSource.js';
import { isOlderThan, parseJson, str, toIso } from './abroad.js';

export const KEY = 'drjob';

/**
 * Dr.Job (drjobpro.com, and drjobs.ae for the UAE), a Middle East board whose
 * robots.txt allows every crawler, ported from the Android app's DrJob.kt.
 * Each keyword has its own server-rendered page, newest first
 * ("/egypt/php-developer-jobs"), with twenty cards that already carry the
 * company, the place and the age. A card that could pass the filters and is
 * not stored yet has its page opened for the whole ad (JobPosting JSON-LD),
 * at most maxDetails a run.
 *
 * Part of it is re-listed from other boards; the store's company + title
 * fingerprint absorbs that overlap.
 */
export class DrJobSource extends BaseSource {
  constructor(client = null) {
    super(KEY, { label: 'Dr.Job', client });
  }

  async fetch(options = {}) {
    const keywords = options.keywords?.length ? options.keywords : (this.settings.keywords ?? config.keywords);
    const sites = sitesFor(options.gulf ?? config.boardsGulf);
    const seen = new Map();
    const now = Date.now();
    const resting = [];
    let answered = 0;
    let tried = 0;
    let failed = 0;

    try {
      for (const site of sites) {
        let siteAnswered = 0;
        let siteFailed = 0;

        for (const keyword of keywords) {
          const path = slug(keyword);
          if (!path) continue;

          const url = `${site.base}/${path}-jobs`;
          if (this.#isResting(url, now)) {
            resting.push(url);
            continue;
          }

          tried += 1;
          let html;

          try {
            html = await this.client.getHtml(url);
          } catch (error) {
            const status = serverStatus(error);
            if (status == null) throw error;

            /*
             * drjobs.ae answers HTTP 500, not an empty page, for a keyword it
             * has no listings under, while other keywords work. One such page
             * is that page's answer, not the site refusing us.
             */
            siteFailed += 1;
            failed += 1;
            const count = (this.#failure(url)?.count ?? 0) + 1;
            this.store?.setState(`${FAILED_PREFIX}${url}`, `${count}|${now}`);
            logger.warn(`Dr.Job: HTTP ${status}, skipped`, { url, retryInDays: Math.min(count, MAX_SKIP_DAYS) });

            if (siteAnswered === 0 && siteFailed >= SITE_DOWN_AFTER) {
              logger.warn(`Dr.Job: ${site.market} answered only HTTP 5xx; its other pages wait for the next run`);
              break;
            }
            continue;
          }

          siteAnswered += 1;
          answered += 1;
          if (this.#failure(url)) this.store?.setState(`${FAILED_PREFIX}${url}`, '');

          const cards = parseList(html, keyword, site);
          this.addListed(cards.length);

          if (cards.length === 0 && html.length > 2000 && !/No results matching/i.test(html)) {
            logger.warn('Dr.Job returned a page with no job cards', { keyword, market: site.market });
          }

          for (const card of cards) {
            if (isOlderThan(card.postedAt, this.settings.maxAgeDays)) continue;
            if (!seen.has(card.externalId)) seen.set(card.externalId, card);
          }
        }
      }
    } catch (error) {
      if (error instanceof BlockedError) error.partialItems = [...seen.values()];
      throw error;
    }

    if (resting.length) logger.info('Dr.Job: left out pages that answered HTTP 5xx recently', { pages: resting.length });

    if (answered === 0 && failed === tried && failed >= SITE_DOWN_AFTER) {
      throw new BlockedError(
        `Dr.Job returned HTTP 5xx for all ${failed} page(s) it tried; the site looks down`,
        [...seen.values()],
      );
    }

    return this.#enrich([...seen.values()]);
  }

  /** The whole ad for the cards worth it; a card keeps its snippet when the page does not answer. */
  async #enrich(items) {
    const wanted = items
      .filter((item) => couldPass(item) && !this.store?.findByExternalId(KEY, item.externalId))
      .slice(0, this.settings.maxDetails);

    for (const item of wanted) {
      try {
        const detail = parseDetail(await this.client.getHtml(item.url));
        if (!detail) continue;
        item.excerpt = detail.description ?? item.excerpt;
        item.company = item.company ?? detail.company;
        // The card's "15 hours ago" is finer than the page's date alone.
        item.postedAt = item.postedAt ?? detail.postedAt;
        item.employmentType = item.employmentType ?? detail.employmentType;
      } catch (error) {
        // A dead link or one page the server cannot render: that card keeps its snippet.
        if (error instanceof BlockedError && !error.transport && serverStatus(error) == null) {
          error.partialItems = items;
          throw error;
        }
      }
    }

    return items;
  }

  #failure(url) {
    const parts = String(this.store?.getState(`${FAILED_PREFIX}${url}`) ?? '').split('|');
    const count = Number.parseInt(parts[0], 10);
    const last = Number.parseInt(parts[1], 10);
    return count > 0 && Number.isFinite(last) ? { count, last } : null;
  }

  /** A path that 5xx'd n runs running waits n days (at most MAX_SKIP_DAYS) before it is tried again. */
  #isResting(url, now) {
    const failure = this.#failure(url);
    return failure != null && now < failure.last + Math.min(failure.count, MAX_SKIP_DAYS) * 86_400_000;
  }
}

export const FAILED_PREFIX = 'drjob.5xx.';
export const MAX_SKIP_DAYS = 7;
export const SITE_DOWN_AFTER = 3;

export const EGYPT_SITE = { base: 'https://www.drjobpro.com/egypt', market: 'Egypt' };

/* The UAE moved to its own domain: drjobpro.com's UAE pages answer 404 and point there. */
export const GULF_SITES = [
  { base: 'https://www.drjobpro.com/saudi-arabia', market: SAUDI },
  { base: 'https://drjobs.ae', market: UAE },
];

/** Egypt first, so a short run spends itself there. */
export const sitesFor = (gulf) => (gulf ? [EGYPT_SITE, ...GULF_SITES] : [EGYPT_SITE]);

/** The status when error is the client's "returned HTTP 5xx after n attempts"; null for a real block. */
export function serverStatus(error) {
  const match = String(error?.message ?? '').match(/returned HTTP (5\d\d) after/);
  return match ? Number(match[1]) : null;
}

/** "Node.js Developer" to "node-js-developer", as the site names its keyword pages. */
export function slug(keyword) {
  const text = String(keyword ?? '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  return text || null;
}

/* The job's id ends its address: ".../php-backend-developer-cairo-pentavalue-MVZW6DME27M83AG". */
const ID_RE = /-([A-Z0-9]{15})\/?$/;
const CARD_RE = /<div[^>]*class="card-item job-card[\s\S]*?(?=<div[^>]*class="card-item job-card|$)/gi;

const origin = (site) => site.base.match(/^https?:\/\/[^/]+/)?.[0] ?? site.base;

/** "15 hours ago" as a date; "30+ days ago", the oldest the site says, as thirty days. */
export const age = (text, now = Date.now()) => (text ? datesToIso(text.replace(/(\d+)\+/, '$1'), now) : null);

/**
 * Cards are cut out of the ~350 KB page first and parsed one at a time, so
 * no DOM of the whole page is ever built.
 */
export function parseList(page, keyword = null, site = EGYPT_SITE) {
  const items = new Map();

  for (const [fragment] of String(page).matchAll(CARD_RE)) {
    const $ = cheerio.load(fragment);
    const href = $('a.job-card-stretched-link').first().attr('href');
    if (!href) continue;

    const url = href.startsWith('http') ? href : `${origin(site)}${href.startsWith('/') ? '' : '/'}${href}`;
    const id = url.match(ID_RE)?.[1];
    const title = clean($('h2.job-name').first().text());
    if (!id || !title || items.has(id)) continue;

    items.set(id, {
      source: KEY,
      itemType: 'job',
      externalId: id,
      title,
      url,
      company: clean($('.company-name').first().text()),
      // "Cairo - Egypt".
      location: clean($('.location span').first().text())?.replace(/\s+-\s+/g, ', ') ?? null,
      postedAt: age(clean($('.posted-date span').first().text())),
      employmentType: clean($('.job-type').first().text()),
      excerpt: clean($('.job-desc-attr').first().text()),
      // One country's pages: the market is known.
      market: site.market,
      keyword,
      board: 'Dr.Job',
    });
  }

  return [...items.values()];
}

const LD_JSON = /<script[^>]*application\/ld\+json[^>]*>([\s\S]*?)<\/script>/gi;

/** The job page's JobPosting JSON-LD, or null when the page has none. */
export function parseDetail(page) {
  for (const [, json] of String(page).matchAll(LD_JSON)) {
    const data = parseJson(json.trim());
    if (!data || data['@type'] !== 'JobPosting') continue;

    return {
      description: stripHtml(data.description).slice(0, 12_000) || null,
      company: str(data.hiringOrganization?.name),
      postedAt: toIso(data.datePosted),
      employmentType: str(data.employmentType),
    };
  }

  return null;
}

export default DrJobSource;
