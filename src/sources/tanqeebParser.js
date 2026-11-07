import * as cheerio from 'cheerio';
import logger from '../logger.js';

/**
 * Turns Tanqeeb pages into items.
 *
 * Tanqeeb is an aggregator: each card says which board the posting came from
 * — LinkedIn, Bayt, Wuzzuf — which is how Bayt's listings reach this crawler
 * at all, its own site answering 403 to everything that is not a browser.
 *
 * A search page is ~450 KB, and loading one into cheerio costs tens of
 * megabytes that are slow to return; eight of them in a run would cost more
 * than the crawl's own work put together. So the cards are cut out of the
 * markup first and parsed one at a time, each ~1 KB. The markup here is
 * semantic, unlike Wuzzuf's hashed classes, so it is worth keeping a real
 * parser for the cards themselves — just never for the whole page.
 */

/** Each result card, as a fragment: <div class="search-job-card-top"> … */
const CARD_RE = /<div[^>]*class="[^"]*search-job-card-top[^"]*"[\s\S]*?(?=<div[^>]*class="[^"]*search-job-card-top|$)/gi;

/** "On-site - Egypt - Cairo" — work mode first, then country, then city. */
const LOCATION_SEPARATOR = /\s+-\s+/;

export function parseSearchResults(html, keyword = null) {
  const page = html ?? '';
  if (page.trim() === '') return [];

  const items = [];
  const seen = new Set();

  for (const match of page.matchAll(CARD_RE)) {
    try {
      const item = parseCard(match[0], keyword);
      if (!item || seen.has(item.externalId)) continue;

      seen.add(item.externalId);
      items.push(item);
    } catch (error) {
      logger.warn('Failed to parse a Tanqeeb card', { error: error.message });
    }
  }

  return items;
}

function parseCard(fragment, keyword) {
  // One card at a time, and not as a document: no html/head/body scaffolding
  // around a kilobyte of markup.
  const $ = cheerio.load(fragment, null, false);

  const link = $('a.search-job-title-link, .search-job-title a').first();
  const href = link.attr('href');

  const title = clean($('.search-job-title').attr('title')) ?? clean(link.text());
  const externalId = extractExternalId(href);

  // The page pads the list with share links and empty rows.
  if (!href || !title || !externalId) return null;

  const { workplace, location } = splitLocation(clean($('.search-job-workplace-location').text()));

  return {
    externalId,
    title,
    url: absolute(href),
    // Which board the posting is really on. Rendered in the message, because
    // it decides where you go to apply.
    board: clean($('.search-job-source').text()),
    employmentType: clean($('.search-job-tag').first().text()),
    workplace,
    location,
    postedAt: parseDate(clean($('.search-job-date').text())),
    category: clean($('.search-job-categories').text()),
    keyword,
  };
}

/**
 * The employer and the description, which the search card does not carry.
 *
 * The company matters beyond display: it is half of the fingerprint the store
 * dedups on, and without it the same job listed on Tanqeeb and on LinkedIn
 * cannot be recognised as one job.
 *
 * Read off the string for the same reason as the search page — a detail page
 * is ~135 KB, and a DOM of it is not worth two fields.
 */
export function parseJobPage(html) {
  const page = html ?? '';
  if (page.trim() === '') return { company: null, excerpt: null };

  return {
    company: clean(detag(sliceTag(page, 'job-meta-company'))),
    excerpt: extractDescription(page),
  };
}

/**
 * The text of the first element carrying `className`.
 *
 * Reads to the next tag rather than to a fixed window, so a short field does
 * not drag in whatever follows it — the company name sits in its own element,
 * with the location in the next one.
 */
function sliceTag(page, className) {
  const open = page.search(new RegExp(`<[a-z]+[^>]*class="[^"]*${className}[^"]*"`, 'i'));
  if (open === -1) return null;

  const textStart = page.indexOf('>', open);
  if (textStart === -1) return null;

  const textEnd = page.indexOf('<', textStart);
  return page.slice(textStart + 1, textEnd === -1 ? textStart + 200 : textEnd);
}

/**
 * The posting body, taken from the "Job Details" heading onward.
 *
 * Anchored to heading text rather than to a container, because the page wraps
 * the description in the same generic markup as its sidebars.
 */
function extractDescription(page) {
  const start = page.search(/>\s*Job Details\s*</i);
  if (start === -1) return null;

  // Start inside the heading's tag, so the slice does not open with the '>'
  // that anchored the search.
  const body = detag(page.slice(page.indexOf('>', start) + 1, start + 12_000));
  if (!body) return null;

  const text = body.replace(/^Job Details\s*/i, '').trim();
  return text === '' ? null : text;
}

/** "/jobs-in-egypt/all/jobs/021158212.html" -> "021158212" */
export function extractExternalId(href) {
  const match = (href ?? '').match(/\/jobs\/(\d+)\.html/i);
  return match ? match[1] : null;
}

/**
 * Splits "On-site - Egypt - Cairo" into the work mode and the place.
 *
 * Kept apart because the work mode is not a location: leaving "On-site" in
 * the location would have it read as a place by anything matching on text.
 * Split on the spaced hyphen only — the work mode contains one of its own.
 */
export function splitLocation(value) {
  if (!value) return { workplace: null, location: null };

  const parts = value.split(LOCATION_SEPARATOR).map((part) => part.trim()).filter(Boolean);
  if (parts.length < 2) return { workplace: null, location: value };

  // Country and city arrive as separate segments; joined so the location
  // filter sees "Egypt, Cairo" rather than two fragments.
  return { workplace: clean(parts[0]), location: clean(parts.slice(1).join(', ')) };
}

/** Tanqeeb prints a real date ("13 August 2026"), unlike Wuzzuf's "3 days ago". */
export function parseDate(value) {
  if (!value) return null;

  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

function detag(fragment) {
  if (!fragment) return null;

  const text = fragment
    .replace(/<(script|style|noscript)\b[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<[^>]*>/g, ' ');

  return clean(decode(text));
}

/** The handful of entities that actually show up in this markup. */
function decode(value) {
  if (!value) return null;

  return value
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)))
    .replace(/&#x([0-9a-f]+);/gi, (_, code) => String.fromCharCode(Number.parseInt(code, 16)));
}

function absolute(href) {
  if (/^https?:\/\//i.test(href)) return href;
  return `https://egypt.tanqeeb.com${href.startsWith('/') ? '' : '/'}${href}`;
}

function clean(value) {
  const text = (value ?? '').replace(/\s+/g, ' ').trim();
  return text === '' ? null : text;
}

export default parseSearchResults;
