import * as cheerio from 'cheerio';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import { clean } from '../text.js';
import BaseSource from './baseSource.js';
import { isOlderThan, toIso } from './abroad.js';
import { budget, fitsFreelance, freelanceMarket, freelanceStack } from './freelance.js';

/*
 * The Arabic freelance boards, ported from the Android app's Mostaql.kt and
 * Khamsat.kt: gigs, not jobs, posted under #Freelance. The shared filters
 * judge them with the freelance matcher (the CV's stack, in English or
 * Arabic) instead of the English title and stack lists.
 */

export const MOSTAQL_KEY = 'mostaql';
export const KHAMSAT_KEY = 'khamsat';

/**
 * Mostaql (mostaql.com): open projects in its programming category.
 * robots.txt only keeps bots off /search and /ajax, so the public category
 * listing and the project pages are read. One listing page a run (25 newest
 * projects), then the page of each new one, up to maxDetails: only it has the
 * budget, the duration, the skills and the full brief.
 */
export class MostaqlSource extends BaseSource {
  constructor(client = null) {
    super(MOSTAQL_KEY, { label: 'Mostaql', client });
  }

  async fetch() {
    const cards = new Map();

    const page = await this.get('https://mostaql.com/projects', { category: 'development' });
    if (page == null) return [];

    const rows = parseMostaqlList(page);
    this.warnIfChallenged(rows.length);

    for (const item of rows) {
      if (!isOlderThan(item.postedAt, this.settings.maxAgeDays) && !cards.has(item.externalId)) {
        cards.set(item.externalId, item);
      }
    }
    this.addListed(cards.size);

    return this.#enrich([...cards.values()]);
  }

  /**
   * Opens the page of each new project, the ones whose title and brief
   * already name the stack first: the skills tagged there often name it when
   * the brief does not. A project that has closed is dropped.
   */
  async #enrich(cards) {
    const checked = this.checked();
    const wanted = cards
      .filter((card) => !checked.has(card.externalId) && !this.store?.findByExternalId(MOSTAQL_KEY, card.externalId))
      .sort((a, b) => Number(fitsFreelance(`${b.title}\n${b.excerpt ?? ''}`)) - Number(fitsFreelance(`${a.title}\n${a.excerpt ?? ''}`)))
      .slice(0, this.settings.maxDetails);

    const result = new Map(cards.map((card) => [card.externalId, card]));
    const kept = () => [...result.values()].filter(Boolean);

    for (const card of wanted) {
      try {
        const detail = parseMostaqlDetail(await this.client.getHtml(card.url));
        if (!detail) continue;
        this.markChecked(card.externalId);
        // A closed project is dropped; an open one gets its page's facts.
        result.set(card.externalId, detail.open ? applyMostaqlDetail(card, detail) : null);
      } catch (error) {
        // A page that did not load keeps its card as listed.
        if (error instanceof BlockedError && !error.transport) {
          error.partialItems = kept();
          throw error;
        }
      }
    }

    const closed = [...result.values()].filter((value) => value === null).length;
    logger.info('Mostaql', { listed: cards.length, opened: wanted.length, closed });
    return kept();
  }
}

/** Mostaql prints UTC as "2026-11-06 18:20:43". */
export function mostaqlPosted(value) {
  const text = String(value ?? '').trim();
  return text.length >= 19 ? toIso(`${text.slice(0, 19).replace(' ', 'T')}Z`) : null;
}

/** Every project row on a listing page, newest first. A page without rows is no projects. */
export function parseMostaqlList(page) {
  const items = new Map();

  // Rows are cut out of the ~175 KB page first, so no DOM of the whole page is built.
  for (const chunk of String(page).split('<tr class="project-row"').slice(1)) {
    const $ = cheerio.load(`<table><tr${chunk.split('</tr>')[0]}</tr></table>`);
    const link = $('h2 a').first();
    const url = link.attr('href');
    const id = url?.match(/\/project\/(\d+)/)?.[1];
    const title = clean(link.text());
    if (!url || !id || !title || items.has(id)) continue;

    const brief = clean($('.project__brief').first().text());
    const offers = $('ul.project__meta li')
      .map((_, li) => $(li).text().match(/(\d+)\s*عرض/)?.[1] ?? null)
      .get()
      .find(Boolean);

    items.set(id, {
      source: MOSTAQL_KEY,
      itemType: 'job',
      externalId: id,
      title,
      url,
      company: clean($('ul.project__meta bdi').first().text()),
      board: 'Mostaql',
      market: freelanceMarket('Mostaql'),
      workplace: 'Remote',
      remote: true,
      postedAt: mostaqlPosted($('time[datetime]').first().attr('datetime')),
      keyword: freelanceStack(`${title}\n${brief ?? ''}`).join(', ') || null,
      excerpt: [offers ? `📨 ${offers} offers so far` : null, brief].filter(Boolean).join('\n') || null,
    });
  }

  return [...items.values()];
}

/** A project page's status, budget, duration, skills and brief; null when it is not a project page. */
export function parseMostaqlDetail(page) {
  if (!page || !String(page).trim()) return null;

  const $ = cheerio.load(page);
  const meta = {};
  $('div.meta-row').each((_, row) => {
    meta[clean($(row).find('.meta-label').first().text()) ?? ''] = clean($(row).find('.meta-value').first().text());
  });
  if (Object.keys(meta).length === 0) return null;

  const body = $('#projectDetailsTab .text-wrapper-div').first();
  const text = (body.length ? body : $('.carda__content').first()).text();

  return {
    // "مفتوح" is open; "مغلق", "مكتمل", "قيد التنفيذ" are not.
    open: meta['حالة المشروع'] == null ? true : meta['حالة المشروع'].includes('مفتوح'),
    budget: budget(meta['الميزانية']),
    duration: meta['مدة التنفيذ'] ?? null,
    skills: $('ul.skills li bdi').map((_, el) => clean($(el).text())).get().filter(Boolean),
    description: clean(text),
  };
}

/** The card with its page's facts: the budget as its salary line, the rest in the excerpt. */
export function applyMostaqlDetail(card, detail) {
  const lines = String(card.excerpt ?? '').split('\n');
  const offers = lines.find((line) => line.startsWith('📨')) ?? null;
  const brief = lines.filter((line) => line && !line.startsWith('📨')).join('\n') || null;

  const excerpt =
    [
      detail.duration ? `⏱ Delivery: ${detail.duration}` : null,
      offers,
      detail.skills.length ? `🏷 ${detail.skills.join(' · ')}` : null,
      detail.description ?? brief,
    ]
      .filter(Boolean)
      .join('\n')
      .slice(0, 4000) || null;

  const stack = freelanceStack([card.title, excerpt].filter(Boolean).join('\n')).join(', ');

  return { ...card, excerpt, salary: detail.budget ?? card.salary ?? null, keyword: stack || card.keyword };
}

/**
 * Khamsat's requests (khamsat.com/community/requests): buyers asking for a
 * service that is not on the market yet. The listing only: a request's own
 * page answers bots with an empty 202 challenge, so it is never opened. A
 * request is judged on its title, so only titles naming the stack get through.
 */
export class KhamsatSource extends BaseSource {
  constructor(client = null) {
    super(KHAMSAT_KEY, { label: 'Khamsat', client });
  }

  async fetch() {
    const page = await this.get('https://khamsat.com/community/requests');
    if (page == null) return [];

    const kept = new Map();
    const rows = parseKhamsatList(page);
    this.warnIfChallenged(rows.length);

    for (const item of rows) {
      if (!isOlderThan(item.postedAt, this.settings.maxAgeDays) && !kept.has(item.externalId)) {
        kept.set(item.externalId, item);
      }
    }
    this.addListed(kept.size);

    return [...kept.values()];
  }
}

/** Khamsat's "05/11/2026 17:26:45 GMT", day first, as ISO. */
export function khamsatPosted(value) {
  const match = String(value ?? '').match(/(\d{2})\/(\d{2})\/(\d{4})\s+(\d{2}):(\d{2}):(\d{2})/);
  if (!match) return null;
  const [, day, month, year, hour, minute, second] = match;
  return toIso(`${year}-${month}-${day}T${hour}:${minute}:${second}Z`);
}

/** Every request row on the listing, newest first. A page without rows is no requests. */
export function parseKhamsatList(page) {
  const items = new Map();
  const host = 'https://khamsat.com';

  for (const chunk of String(page).split('<tr id="forum_post-').slice(1)) {
    const id = chunk.match(/^\d+/)?.[0];
    if (!id || items.has(id)) continue;

    const $ = cheerio.load(`<table><tr id="forum_post-${chunk.split('</tr>')[0]}</tr></table>`);
    const link = $('h3.details-head a[href]').first();
    const href = link.attr('href');
    const title = clean(link.text());
    if (!href || !title) continue;

    items.set(id, {
      source: KHAMSAT_KEY,
      itemType: 'job',
      externalId: id,
      title,
      url: href.startsWith('http') ? href : host + href,
      company: clean($('a.user').first().text())?.replace(/^\./, '').trim() ?? null,
      board: 'Khamsat',
      market: freelanceMarket('Khamsat'),
      workplace: 'Remote',
      remote: true,
      // The first time on the row is when it was posted; the next is the last reply.
      postedAt: khamsatPosted($('span[title]').first().attr('title')),
      keyword: freelanceStack(title).join(', ') || null,
      excerpt: title,
    });
  }

  return [...items.values()];
}
