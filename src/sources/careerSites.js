import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import { toIso as datesToIso } from '../dates.js';
import { clean, decodeEntities, detag, stripHtml } from '../text.js';
import BaseSource from './baseSource.js';
import { parseJson } from './abroad.js';

export const KEY = 'career_sites';
const MAX_SITEMAPS = 5;
const MAX_EXCERPT = 4000;

/**
 * Companies' own careers sites: a sitemap that lists every open job, and one
 * page per job, ported from the Android app's CareerSites.kt (its "Add a
 * careers site"). Framer, Webflow, WordPress and most hand-built sites look
 * like this, so adding a company is one CAREER_SITES entry, not a new source:
 *
 *   id|Company|https://acme.com/sitemap.xml|^https://acme\.com/jobs/([^/]+)/?$[|Location[|Market]]
 *
 * A job page is read through its JobPosting JSON-LD when it has one,
 * otherwise through its heading, og: tags and main text. Most such sites
 * date nothing, so "new" means "not listed last time": each site keeps its
 * own ledger, and a site's first run records what is open and sends none of
 * it — otherwise adding a company would post its whole board at once.
 *
 * VerteX Technologies, the app's first career site, stays on the vertex
 * source, which reads its markup in more detail.
 */
export class CareerSitesSource extends BaseSource {
  constructor(client = null) {
    super(KEY, { label: 'Career sites', client });
  }

  isEnabled() {
    return Boolean(this.settings.enabled) && this.#sites().length > 0;
  }

  #sites() {
    return (this.settings.sites ?? []).map(parseSite).filter(Boolean);
  }

  async fetch() {
    const items = [];

    await this.walk(this.#sites(), `${KEY}.rotation`, async (site) => {
      try {
        items.push(...(await this.#visit(site)));
      } catch (error) {
        // One company's site being down is not a reason to skip the rest.
        if (error instanceof BlockedError && !error.transport) {
          error.partialItems = items;
          throw error;
        }
        logger.warn(`Career sites: could not read ${site.company}`, { error: error.message });
      }
      return true;
    });

    return items;
  }

  async #visit(site) {
    const pages = await this.#jobPages(site);
    if (pages.length === 0) {
      logger.warn(`Career sites: ${site.company}'s sitemap listed no jobs`);
      return [];
    }
    // Listed, even if every one is known already: the sitemap reads fine.
    this.addListed(pages.length);

    const ledger = `${KEY}:${site.id}`;
    const checked = this.store?.checkedIds(ledger) ?? new Set();
    const mark = (id) => {
      if (!this.dryRun) this.store?.markChecked(ledger, id);
    };

    if (checked.size === 0) {
      for (const page of pages) mark(page.externalId);
      logger.info(`Career sites: first run for ${site.company}, recorded ${pages.length} open jobs without sending them`);
      return [];
    }

    // Still listed: touched again, so pruning never forgets an open job and sends it as new.
    for (const page of pages) if (checked.has(page.externalId)) mark(page.externalId);

    const wanted = pages
      .filter((page) => !checked.has(page.externalId) && !this.store?.findByExternalId(KEY, page.externalId))
      .slice(0, this.settings.maxDetails);

    logger.info(`Career sites: ${site.company}: ${wanted.length} new jobs to open`);

    const items = [];
    for (const page of wanted) {
      let html;
      try {
        html = await this.client.getHtml(page.url);
      } catch (error) {
        if (error instanceof BlockedError && !error.transport) {
          error.partialItems = items;
          throw error;
        }
        mark(page.externalId);
        continue;
      }

      // Opened, so never opened again — whatever is decided below.
      mark(page.externalId);

      const item = parseCareerPage(html, page);
      if (item) items.push(item);
      else logger.warn(`Career sites: ${page.url} had no readable job`);
    }

    return items;
  }

  /** Every job page the site's sitemap lists, following a sitemap index one level down. */
  async #jobPages(site) {
    const xml = await this.client.getHtml(site.sitemap);
    const sitemaps = /<sitemapindex/i.test(xml)
      ? await Promise.all(locs(xml).slice(0, MAX_SITEMAPS).map((url) => this.client.getHtml(url)))
      : [xml];

    const seen = new Set();
    const pages = [];
    for (const url of sitemaps.flatMap(locs)) {
      const id = idOf(site, url);
      if (!id || seen.has(id)) continue;
      seen.add(id);
      pages.push({ url, externalId: `${site.id}:${id}`, site });
    }
    return pages;
  }
}

/** "id|Company|sitemap|jobUrlRegex[|Location[|Market]]" → a site, or null. */
export function parseSite(entry) {
  const [id, company, sitemap, pattern, location = 'Cairo, Egypt', market = 'Egypt'] = String(entry ?? '')
    .split('|')
    .map((part) => part.trim());
  if (!id || !company || !sitemap || !pattern) return null;

  try {
    return { id, company, sitemap, jobUrl: new RegExp(pattern, 'i'), location, market };
  } catch {
    logger.warn('Career sites: ignoring an entry whose job pattern is not a regex', { entry });
    return null;
  }
}

export const locs = (xml) =>
  [...String(xml ?? '').matchAll(/<loc>\s*([^<]+?)\s*<\/loc>/gi)].map((m) => decodeEntities(m[1]).trim());

/** The job's id within its site: the pattern's group, or else the path. */
export function idOf(site, url) {
  const match = String(url).trim().match(site.jobUrl);
  if (!match) return null;

  const raw = match[1] || String(url).trim().replace(/^https?:\/\/[^/]+\/?/, '').replace(/\/+$/, '');
  if (!raw) return null;

  try {
    return decodeURIComponent(raw).toLowerCase();
  } catch {
    return raw.toLowerCase();
  }
}

const LD_JSON = /<script[^>]*type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi;

function findPosting(node) {
  if (Array.isArray(node)) {
    for (const child of node) {
      const found = findPosting(child);
      if (found) return found;
    }
    return null;
  }
  if (!node || typeof node !== 'object') return null;

  const type = node['@type'];
  const types = Array.isArray(type) ? type : [type];
  if (types.some((t) => String(t).toLowerCase() === 'jobposting')) return node;
  return findPosting(node['@graph']);
}

/** "Cairo, Egypt" from jobLocation, which may be one place or several. */
function placeOf(posting) {
  const locations = Array.isArray(posting.jobLocation) ? posting.jobLocation : posting.jobLocation ? [posting.jobLocation] : [];
  const places = locations
    .map((location) => {
      const address = location?.address;
      if (!address) return null;
      const country = address.addressCountry?.name ?? address.addressCountry;
      return [...new Set([address.addressLocality, address.addressRegion, country].map(clean).filter(Boolean))].join(', ') || null;
    })
    .filter(Boolean);
  return [...new Set(places)].join(' / ') || null;
}

export function meta(page, name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const tag = String(page).match(new RegExp(`<meta[^>]*(?:name|property)=["']${escaped}["'][^>]*>`, 'i'))?.[0];
  if (!tag) return null;
  // The closing quote is the opening one: "We're hiring" is in double quotes.
  return clean(decodeEntities(tag.match(/content=(["'])([\s\S]*?)\1/i)?.[2]));
}

/** "Backend Developer - Acme" or "Backend Developer | Acme Careers" to the role alone. */
function withoutCompany(title, company) {
  const text = clean(title);
  if (!text) return null;
  const parts = text.split(/\s+[|\-–—]\s+/);
  return clean(parts.find((part) => !part.toLowerCase().includes(company.toLowerCase())) ?? parts[0]);
}

/** The page's <main> or <article>, without its scripts and chrome. */
function mainText(page) {
  const body =
    String(page).match(/<main\b[^>]*>([\s\S]*?)<\/main>/i)?.[1] ?? String(page).match(/<article\b[^>]*>([\s\S]*?)<\/article>/i)?.[1];
  if (!body) return null;
  return stripHtml(body.replace(/<(script|style|noscript|nav|header|footer|form|svg)\b[\s\S]*?<\/\1>/gi, ' ')) || null;
}

/** A job page read through JSON-LD when it has a JobPosting, through its markup otherwise. */
export function parseCareerPage(page, info, now = Date.now()) {
  if (!page || !String(page).trim()) return null;

  const base = { source: KEY, itemType: 'job', externalId: info.externalId, url: info.url, market: info.site.market };

  for (const [, json] of String(page).matchAll(LD_JSON)) {
    const posting = findPosting(parseJson(json.trim()));
    if (!posting) continue;

    const title = clean(decodeEntities(posting.title));
    if (!title) return null;

    // Past its own closing date: closed, whatever the sitemap still says.
    const validThrough = Date.parse(datesToIso(posting.validThrough) ?? '');
    if (Number.isFinite(validThrough) && validThrough < now) return null;

    const remote = String(posting.jobLocationType ?? '').toUpperCase() === 'TELECOMMUTE';
    const type = Array.isArray(posting.employmentType) ? posting.employmentType.join(', ') : posting.employmentType;

    return {
      ...base,
      title,
      company: clean(posting.hiringOrganization?.name) ?? info.site.company,
      location: placeOf(posting) ?? info.site.location,
      postedAt: posting.datePosted ? datesToIso(posting.datePosted) : null,
      excerpt: stripHtml(posting.description).slice(0, MAX_EXCERPT) || null,
      employmentType: clean(type),
      remote,
      workplace: remote ? 'Remote' : null,
    };
  }

  const company = info.site.company;
  const title =
    detag(String(page).match(/<h1[^>]*>([\s\S]*?)<\/h1>/i)?.[1]) ??
    withoutCompany(meta(page, 'og:title'), company) ??
    withoutCompany(detag(String(page).match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1]), company);
  if (!title) return null;

  return {
    ...base,
    title,
    company,
    location: info.site.location,
    excerpt: (mainText(page) ?? meta(page, 'og:description') ?? meta(page, 'description'))?.slice(0, MAX_EXCERPT) ?? null,
  };
}

export default CareerSitesSource;
