import logger from '../logger.js';
import { couldPass } from '../filters.js';
import { clean, decodeEntities, detag, stripHtml } from '../text.js';
import BaseSource from './baseSource.js';
import { blocked } from './abroadFit.js';
import { isOlderThan, parseJson, str, toIso } from './abroad.js';
import { MAX_YEARS, minYears, stackOf } from './cvStack.js';

export const KEY = 'relocateme';
const BASE = 'https://relocate.me';
const LIST_URL = `${BASE}/international-jobs`;

/**
 * relocate.me: tech jobs abroad (Europe, Japan) whose employer pays for the
 * move and the visa, ported from the Android app's RelocateMe.kt. Every job
 * there is a relocation, so there is no lane to decide: what is checked is
 * the CV.
 *
 * The board is small (about 25 open jobs over two pages), server-rendered.
 * A card carries title, company and place; the job page carries a JSON-LD
 * JobPosting with the date and the full description. So: read every list
 * page, open only the jobs never opened before whose title could pass, and
 * remember each one opened, so a job is fetched once.
 */
export class RelocateMeSource extends BaseSource {
  constructor(client = null) {
    super(KEY, { label: 'Relocate.me', client });
  }

  async fetch() {
    const checked = this.checked();
    const kept = [];
    const rejected = {};
    const cards = new Map();
    let seen = 0;

    try {
      for (let page = 1; page <= this.settings.maxPages; page += 1) {
        const body = await this.get(LIST_URL, { page: page > 1 ? page : null }, kept);
        if (body == null) break;
        const fresh = parseRelocateList(body).filter((card) => !cards.has(card.id) && cards.set(card.id, card));
        if (fresh.length === 0) break;
      }
      this.addListed(cards.size);

      for (const card of cards.values()) {
        if (checked.has(card.id)) {
          seen += 1;
          continue;
        }

        let reason = null;
        if (card.sponsored) reason = 'advert';
        else if (!couldPass(cardItem(card))) reason = 'title';

        if (reason) {
          rejected[reason] = (rejected[reason] ?? 0) + 1;
          this.markChecked(card.id);
          continue;
        }

        const page = await this.get(card.url, {}, kept);
        if (page == null) continue;

        const job = parseRelocateDetail(page, card);
        this.markChecked(card.id);

        const why = job.rejected ?? (isOlderThan(job.item.postedAt, this.settings.maxAgeDays) ? 'too-old' : null);
        if (why) rejected[why] = (rejected[why] ?? 0) + 1;
        else kept.push(job.item);
      }
    } finally {
      logger.info('relocate.me', { listed: cards.size, seenBefore: seen, kept: kept.length, dropped: rejected });
    }

    return kept;
  }
}

/** The market a relocation job is in, as the channel shows it. */
export const relocateLane = (country) =>
  String(country).toLowerCase() === 'remote' ? '🌍 Remote (via Relocate.me)' : `✈️ Relocation to ${country}, visa paid`;

const place = (city, country) =>
  !city || String(country).toLowerCase() === 'remote' ? country : `${city}, ${country}`;

/** "san-sebastian" → "San Sebastian". */
const words = (slug) =>
  String(slug).split('-').filter(Boolean).map((w) => w[0].toUpperCase() + w.slice(1)).join(' ');

/** Enough for couldPass, before the job page is spent on it. */
export function cardItem(card) {
  return {
    source: KEY,
    externalId: card.id,
    title: card.title,
    url: card.url,
    company: card.company,
    location: place(card.city, card.country),
    market: relocateLane(card.country),
    remote: card.remote,
  };
}

const LINK = /href="(\/([a-z0-9-]+)\/([a-z0-9-]+)\/([a-z0-9-]+)\/([a-z0-9-]+)-(\d+))"/;
const BOLD = /<b>([\s\S]*?)<\/b>/;
const COMPANY = /job__company[\s\S]*?<p>([\s\S]*?)<\/p>/;

/** Every job card on a list page, in page order. A page without cards is no jobs. */
export function parseRelocateList(page) {
  return String(page)
    .split(/class="jobs-list__job"/)
    .slice(1)
    .map((chunk) => {
      const link = chunk.match(LINK);
      if (!link) return null;
      const [, path, country, city, , slug, id] = link;
      const title = detag(chunk.match(BOLD)?.[1]);
      if (!title) return null;

      const countryName = words(country);
      return {
        id,
        url: BASE + path,
        title,
        company: detag(chunk.match(COMPANY)?.[1]),
        country: countryName,
        city: city.toLowerCase() === country.toLowerCase() ? null : words(city),
        // The board's own paid listing, pinned to the top of every search.
        sponsored: slug.includes('paid-option'),
        remote: countryName.toLowerCase() === 'remote',
      };
    })
    .filter(Boolean);
}

const LD = /<script type="application\/ld\+json">([\s\S]*?)<\/script>/g;
const INTERN = /\bintern(ship)?s?\b/i;

/** The job page: its JSON-LD JobPosting, judged against the CV. */
export function parseRelocateDetail(page, card) {
  let posting = null;

  for (const [, json] of String(page).matchAll(LD)) {
    // The descriptions carry raw line breaks, which strict JSON refuses.
    // eslint-disable-next-line no-control-regex
    const data = parseJson(json.replace(/[\u0000-\u001f]+/g, ' '));
    if (data?.['@type'] === 'JobPosting') {
      posting = data;
      break;
    }
  }

  const title =
    clean(decodeEntities(posting?.title))?.replace(/\s*\|\s*Relocation Offered\s*$/i, '') ?? card.title;
  const address = posting?.jobLocation?.address;
  const city = str(address?.addressLocality) ?? card.city;
  const country = str(address?.addressCountry) ?? card.country;
  const description = stripHtml(decodeEntities(posting?.description ?? ''));
  const rawType = str(posting?.employmentType)?.replace(/_/g, ' ').toLowerCase();
  const type = rawType ? rawType[0].toUpperCase() + rawType.slice(1) : null;

  const text = `${title}\n${description}`;
  const stack = stackOf(text);
  const years = minYears(description);
  const market = card.remote ? relocateLane('Remote') : relocateLane(country);

  let rejected = null;
  if (!posting) rejected = 'no-posting';
  else if (stack.length === 0) rejected = 'stack';
  else if (INTERN.test(title) || /intern/i.test(type ?? '')) rejected = 'internship';
  else if (/part/i.test(type ?? '')) rejected = 'part-time';
  else if (years != null && years > MAX_YEARS) rejected = 'years';
  else rejected = blocked(text);

  return {
    rejected,
    item: {
      source: KEY,
      itemType: 'job',
      externalId: card.id,
      title,
      url: card.url,
      company: str(posting?.hiringOrganization?.name) ?? card.company,
      location: place(city, country),
      market,
      postedAt: toIso(posting?.datePosted),
      keyword: stack.join(', ') || null,
      excerpt: [market, years != null ? `Needs ${years}+ years` : null, description || null].filter(Boolean).join('\n').slice(0, 4000),
      employmentType: type,
      workplace: card.remote ? 'Remote' : 'On-site, relocation',
      remote: card.remote,
    },
  };
}

export default RelocateMeSource;
