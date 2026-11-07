import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import { decodeEntities, stripHtml } from '../text.js';
import BaseSource from './baseSource.js';
import {
  arr, isOlderThan, judgeFeedJob, newCounts, parseJson, str, summarize, tally, titleFits, toIso,
} from './abroad.js';

export const KEY = 'jobgether';
export const API_URL = 'https://jobgether.com/api/v1/jobs';

/**
 * Jobgether, a remote-jobs board with a public search API made for crawlers
 * (robots.txt allows it, two seconds apart), ported from the Android app.
 * Searched with each keyword for jobs open to Egypt, newest first.
 *
 * The list gives a title, the company and a place ("Anywhere", "EMEA"), not
 * the ad. So a job whose title fits is opened once: its page carries the
 * description and the countries that may apply, and a job that leaves Egypt
 * out is dropped there. Opened jobs are remembered; a run opens at most
 * maxDetails, the rest wait for the next.
 */
export class JobgetherSource extends BaseSource {
  constructor(client = null) {
    super(KEY, { label: 'Jobgether', headers: { Accept: 'application/json, text/html' }, client });
  }

  async fetch(options = {}) {
    const checked = this.checked();
    const counts = newCounts();
    const cards = new Map();

    try {
      for (const keyword of queries(options.keywords?.length ? options.keywords : this.settings.keywords)) {
        let listed;
        try {
          const body = await this.get(API_URL, params(keyword), counts.kept);
          if (body == null) continue;
          listed = parseList(body);
        } catch (error) {
          if (error instanceof BlockedError) throw error;
          logger.warn(`Jobgether: "${keyword}" did not parse`, { error: error.message });
          continue;
        }
        counts.listed += listed.length;
        for (const card of listed) if (!cards.has(card.id)) cards.set(card.id, card);
      }

      let opened = 0;

      for (const card of cards.values()) {
        const id = `jobgether:${card.id}`;
        if (checked.has(id)) {
          counts.seen += 1;
          continue;
        }
        if (isOlderThan(card.postedAt, this.settings.maxAgeDays)) {
          counts.old += 1;
          continue;
        }

        // Settled from the list alone, without opening the page.
        const early = earlyReject(card);
        if (early) {
          this.markChecked(id);
          counts.judged += 1;
          counts.rejected[early] = (counts.rejected[early] ?? 0) + 1;
          continue;
        }

        if (opened >= this.settings.maxDetails) continue;
        opened += 1;

        const page = await this.get(card.url, {}, counts.kept);
        if (page == null) continue;

        this.markChecked(id);
        counts.judged += 1;

        const offer = parseOffer(page);
        if (offer.countries.size > 0 && !offer.countries.has('EG')) {
          counts.rejected['region-locked'] = (counts.rejected['region-locked'] ?? 0) + 1;
          continue;
        }

        tally(judgeFeedJob(asJob(card, offer), KEY), counts);
      }
    } finally {
      this.addListed(counts.listed);
      logger.info('Jobgether', summarize(counts));
    }

    return counts.kept;
  }
}

/* The CV's stack, when no keywords are configured. */
export const DEFAULT_QUERIES = ['Backend Developer', 'PHP Developer', 'Laravel Developer', 'Node.js Developer', 'Full Stack Developer'];

/** The keywords, each once (case aside). */
export function queries(keywords) {
  const seen = new Set();
  const out = [];
  for (const raw of keywords ?? []) {
    const keyword = String(raw).trim();
    if (keyword === '' || seen.has(keyword.toLowerCase())) continue;
    seen.add(keyword.toLowerCase());
    out.push(keyword);
  }
  return out.length ? out : DEFAULT_QUERIES;
}

/*
 * "egypt" also brings the worldwide and EMEA jobs Egypt may take. An unknown
 * parameter is ignored silently (it returns the default US feed), so these
 * names are exactly what the docs give.
 */
export const params = (keyword) => ({ keyword, locations: 'egypt', sort: 'date', limit: 25, page: 1 });

/** Why a listed job is dropped before its page is opened, or null to open it. */
export function earlyReject(card) {
  const type = card.contractType ?? '';
  if (!titleFits({ title: card.title, location: card.location })) return 'title';
  if (/intern/i.test(type)) return 'internship';
  if (/part/i.test(type)) return 'part-time';
  return null;
}

export function parseList(body) {
  return arr(parseJson(body)?.jobs)
    .map((job) => {
      const id = str(job?.id);
      const title = str(decodeEntities(job?.title));
      const url = str(job?.url);
      if (!id || !title || !url) return null;
      return {
        id, title, url,
        company: str(job.company),
        location: str(job.location),
        remote: str(job.remote),
        contractType: str(job.contractType),
        experience: str(job.experience),
        postedAt: toIso(job.postedAt),
      };
    })
    .filter(Boolean);
}

const LD_JSON = /<script[^>]*application\/ld\+json[^>]*>([\s\S]*?)<\/script>/gi;

/** The job page's JobPosting JSON-LD; an empty offer when the page has none. */
export function parseOffer(page) {
  for (const [, json] of String(page).matchAll(LD_JSON)) {
    const data = parseJson(json.trim());
    if (!data || data['@type'] !== 'JobPosting') continue;

    const places = data.applicantLocationRequirements;
    const list = Array.isArray(places) ? places : places && typeof places === 'object' ? [places] : [];

    return {
      description: stripHtml(data.description),
      countries: new Set(list.map((place) => str(place?.name)?.toUpperCase()).filter(Boolean)),
      postedAt: toIso(data.datePosted),
      company: str(data.hiringOrganization?.name),
    };
  }

  return { description: '', countries: new Set(), postedAt: null, company: null };
}

/** The card and its page as a feed job. Egypt being on the page's list is said in the place. */
export function asJob(card, offer) {
  const remote = card.remote == null ? true : /remote/i.test(card.remote);
  const restriction = [offer.countries.has('EG') ? 'Egypt' : null, card.location].filter(Boolean).join(' · ') || null;
  const facts = [card.contractType ? `Contract: ${card.contractType}` : null, card.experience ? `Experience: ${card.experience}` : null];

  return {
    feed: 'jobgether',
    id: card.id,
    title: card.title,
    company: offer.company ?? card.company,
    url: card.url,
    location: null,
    restriction,
    description: [...facts, offer.description].filter((part) => part && part.trim()).join('\n'),
    postedAt: offer.postedAt ?? card.postedAt,
    remote,
  };
}

export default JobgetherSource;
