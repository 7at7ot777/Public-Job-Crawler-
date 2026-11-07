import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import { toIso as datesToIso } from '../dates.js';
import { countryOf } from '../gulf.js';
import { containsAny, fold } from '../text.js';
import BaseSource from './baseSource.js';
import LinkedInGuestClient from './linkedinClient.js';
import { SerperBudget } from './serperBudget.js';
import { blocked, US_ONLY, VISA_WORDS as ABROAD_VISA_WORDS, WORLDWIDE } from './abroadFit.js';
import { isOlderThan, parseJson, str } from './abroad.js';
import { stackOf } from './cvStack.js';

export const KEY = 'glassdoor';
const SERPER_URL = 'https://google.serper.dev/search';

export const REMOTE_LANE = '🌍 Remote, open worldwide';
export const VISA_LANE = '✈️ Visa sponsorship / relocation';

/**
 * Glassdoor job listings, found through Google (Serper), since glassdoor.com
 * itself refuses crawlers. Ported from the Android app's Glassdoor.kt, and
 * off by default there and here: every query is a paid Serper search, counted
 * under SERPER_DAILY_CAP with the hiring posts. Run it once a day (the
 * container's daily lane).
 *
 * Only what Google shows is read — the title ("Acme hiring Backend Developer
 * Job in Cairo"), the snippet and the link. Three kinds of search: Egypt
 * (the Egypt location rule applies), abroad (kept only in a lane — visa or
 * remote open worldwide — and naming the CV's stack), and with BOARDS_GULF
 * Saudi Arabia and the UAE, posted like Egypt's and marked with the country.
 */
export class GlassdoorSource extends BaseSource {
  constructor(client = null, budget = null) {
    super(KEY, {
      client:
        client ??
        new LinkedInGuestClient({
          label: 'Serper',
          delayMs: config.serper.delayMs,
          headers: { 'X-API-KEY': config.serper.apiKey, Accept: 'application/json' },
        }),
    });
    this.budget = budget;
  }

  isEnabled() {
    return Boolean(this.settings.enabled && config.serper.apiKey);
  }

  async fetch() {
    const budget = this.budget ?? new SerperBudget(this.store);
    const kept = new Map();
    const rejected = {};

    try {
      for (const query of queries(config.boardsGulf)) {
        budget.spend('Glassdoor');
        const body = await this.client.postJson(SERPER_URL, request(query));
        if (!body || !body.trim()) continue;

        for (const result of parseSerper(body)) {
          const judged = judgeGlassdoor(result, query);
          const reason = judged.rejected ?? (isOlderThan(judged.item.postedAt, 7) ? 'too-old' : null);
          if (reason) rejected[reason] = (rejected[reason] ?? 0) + 1;
          else if (!kept.has(judged.item.externalId)) kept.set(judged.item.externalId, judged.item);
        }
      }
    } catch (error) {
      // Its alert (out of credits, the cap) is the pipeline's to send.
      if (error instanceof BlockedError) error.partialItems = [...kept.values()];
      throw error;
    } finally {
      logger.info('Glassdoor', { kept: kept.size, dropped: rejected });
    }

    return [...kept.values()];
  }
}

// Glassdoor's country sites; Google takes an OR of site: filters.
const SITES =
  '(site:glassdoor.com OR site:glassdoor.co.uk OR site:glassdoor.de OR site:glassdoor.ca OR site:glassdoor.nl OR site:glassdoor.ie)';
const STACK = '(Laravel OR PHP OR "Node.js" OR NestJS OR Golang)';
const GULF_STACK = '(Laravel OR PHP OR "Node.js" OR NestJS OR Golang OR "backend developer")';

/** Eight paid searches a run: three in Egypt, five abroad (two of them Canada and Australia). */
export const QUERIES = [
  { text: 'site:glassdoor.com inurl:job-listing (Laravel OR PHP) Egypt', scope: 'egypt', country: 'eg' },
  { text: 'site:glassdoor.com inurl:job-listing ("Node.js" OR NestJS OR Golang) Egypt', scope: 'egypt', country: 'eg' },
  { text: 'site:glassdoor.com inurl:job-listing "backend developer" Egypt', scope: 'egypt', country: 'eg' },
  { text: `${SITES} inurl:job-listing ${STACK} "visa sponsorship"`, scope: 'abroad', country: 'gb' },
  { text: `${SITES} inurl:job-listing ${STACK} relocation package`, scope: 'abroad', country: 'de' },
  { text: `${SITES} inurl:job-listing ${STACK} remote (worldwide OR anywhere OR EMEA)`, scope: 'abroad', country: 'us' },
  { text: `site:glassdoor.ca inurl:job-listing ${STACK} ("visa sponsorship" OR LMIA OR remote)`, scope: 'abroad', country: 'ca' },
  { text: `site:glassdoor.com.au inurl:job-listing ${STACK} ("visa sponsorship" OR 482 OR remote)`, scope: 'abroad', country: 'au' },
];

/** With BOARDS_GULF: one search each, under that country's gl. */
export const GULF_QUERIES = [
  { text: `site:glassdoor.com inurl:job-listing ${GULF_STACK} ("Saudi Arabia" OR Riyadh OR Jeddah)`, scope: 'gulf', country: 'sa' },
  { text: `site:glassdoor.com inurl:job-listing ${GULF_STACK} (UAE OR Dubai OR "Abu Dhabi")`, scope: 'gulf', country: 'ae' },
];

export const queries = (gulf) => (gulf ? [...QUERIES, ...GULF_QUERIES] : QUERIES);

// Google's week; a listing older than this is likely filled or reposted.
export const request = (query) => ({ q: query.text, gl: query.country, hl: 'en', num: 10, page: 1, tbs: 'qdr:w' });

/** A single listing, never a search page such as /Job/egypt-php-jobs-SRCH_…. */
export const isListing = (url) => /glassdoor\.[a-z.]+\/(job-listing\/|partner\/jobListing)/i.test(url);

/** @throws {BlockedError} when Serper answers with anything but results: out of credits, a bad key. */
export function parseSerper(body) {
  const payload = parseJson(String(body).trim());
  if (!payload) throw new BlockedError(`Serper did not return JSON: ${String(body).slice(0, 200).replace(/\s+/g, ' ')}`);

  if (!Array.isArray(payload.organic)) {
    if (payload.message) {
      const error = new BlockedError(`Serper refused the request: ${payload.message}`);
      error.alert = 'serper-refused';
      throw error;
    }
    return [];
  }

  return payload.organic
    .map((result) => {
      const url = str(result?.link);
      if (!url || !isListing(url)) return null;
      return { url, title: str(result.title), snippet: str(result.snippet), postedAt: result.date ? datesToIso(result.date) : null };
    })
    .filter(Boolean);
}

/** The listing id: ?jl=1009… on every Glassdoor site, else the listing's path. */
export function idOf(url) {
  return url.match(/[?&]jl=(\d+)/)?.[1] ?? (url.split('job-listing/')[1]?.split('?')[0] || null);
}

/** "Acme hiring Backend Developer Job in Cairo, Cairo | Glassdoor", or "Backend Developer - Acme - Cairo | Glassdoor". */
export function splitTitle(raw) {
  const text = String(raw ?? '').replace(/\s*[|\-–]\s*Glassdoor.*$/i, '').trim();
  if (!text) return null;

  const hiring = text.match(/^(.+?)\s+hiring\s+(.+?)\s+Job\s+in\s+(.+)$/i);
  if (hiring) {
    return { role: hiring[2].trim(), company: hiring[1].trim(), place: hiring[3].trim().replace(/[.…]+$/, '') };
  }

  const parts = text.split(/\s+[-–]\s+/).map((part) => part.trim()).filter(Boolean);
  return { role: parts[0], company: parts[1] ?? null, place: parts[2] ?? null };
}

const VISA_WORDS = [
  ...ABROAD_VISA_WORDS,
  'visa sponsorship', 'sponsorship provided', 'relocation', 'relocation bonus', 'skilled worker visa',
  'blue card', 'work permit provided', 'tier 2', 'visa support',
];

/** Where an abroad listing stands; null when it is neither lane. */
export function laneOf(text) {
  const folded = fold(text);
  const us = containsAny(folded, US_ONLY);
  if (containsAny(folded, ['remote']) && containsAny(folded, WORLDWIDE) && !us) return REMOTE_LANE;
  if (containsAny(folded, VISA_WORDS)) return VISA_LANE;
  return null;
}

/** One Google result as an item, and why it is dropped, if it is. */
export function judgeGlassdoor(result, query) {
  const title = splitTitle(result.title);
  const text = [result.title, result.snippet].filter(Boolean).join('\n');
  const egypt = containsAny(fold(text), config.filters.egyptPlaces);

  // A listing in Egypt found by an abroad search is still an Egypt one; a
  // Gulf search keeps only listings in its Gulf country, marked with it.
  const gulf = egypt || query.scope !== 'gulf' ? null : countryOf(text);
  const lane = egypt || query.scope === 'gulf' ? null : laneOf(text);
  const stack = stackOf(text);

  const item = {
    source: KEY,
    itemType: 'job',
    externalId: idOf(result.url) ?? result.url,
    title: title?.role ?? 'Glassdoor listing',
    url: result.url,
    company: title?.company ?? null,
    location: title?.place ?? null,
    // An Egypt listing keeps no market, so the Egypt location rule reads its words.
    market: gulf ?? lane,
    postedAt: result.postedAt,
    keyword: stack.join(', ') || null,
    excerpt: [lane, result.snippet].filter(Boolean).join('\n') || null,
    remote: lane === REMOTE_LANE,
    board: 'Glassdoor, found on Google',
  };

  let rejected = null;
  if (!title) rejected = 'no-title';
  else if (query.scope === 'egypt' && !egypt) rejected = 'not-egypt';
  else if (query.scope === 'gulf' && !egypt && !gulf) rejected = 'not-gulf';
  else if (gulf) rejected = null;
  else if (!egypt && !lane) rejected = 'no-lane';
  else if (!egypt && stack.length === 0) rejected = 'stack';
  else if (!egypt) rejected = blocked(text);

  return { item, rejected };
}

export default GlassdoorSource;
