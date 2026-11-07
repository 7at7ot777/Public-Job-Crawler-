import logger from '../logger.js';
import { relativeToIso } from '../dates.js';

/**
 * Turns a Wuzzuf job page into an item — without building a DOM.
 *
 * Wuzzuf pages are 500-700 KB, and loading one into cheerio costs 10-15 MB of
 * RSS that is slow to come back; twenty-five of them in a run would cost more
 * than the crawl's own work put together. Everything needed here is four small
 * pieces, so they are cut straight out of the markup instead, and only the
 * window around the description is ever detagged.
 *
 * The other reason not to use selectors: Wuzzuf renders with emotion, so every
 * class on the page is a hash that changes when they rebuild. What this reads
 * instead — the h1, the title, the og: tags, fixed heading words — held across
 * every page tried.
 */

/*
 * Wuzzuf titles a job page one of two ways, and which one you get depends on
 * the posting rather than on anything in the URL:
 *
 *   "<role> job at <company> in <location> – Apply on Wuzzuf"
 *   "<role> vacancy in <location> | 5 to 10 years experience"
 *
 * Only the first names the company, so a page in the second shape has none to
 * report — better than inventing one out of the slug.
 */
const TITLE_RE = /^(.*?)\s+job at\s+(.*?)\s+in\s+(.*?)\s+[–-]\s*Apply on Wuzzuf/i;
const VACANCY_RE = /^(.*?)\s+vacancy in\s+([^|]+?)\s*(?:\||$)/i;

/** The second shape states the experience the employer wants; the first does not. */
const EXPERIENCE_RE = /(\d+)\s*to\s*(\d+)\s*years?(?:\s+of)?\s+experience/i;

/** How much of the page after the description heading is worth keeping. */
const DESCRIPTION_WINDOW = 12_000;

/**
 * @param {string} html
 * @param {string} url   the page it came from, used for the id and the link
 * @returns {object|null} null when the page is not a readable job page
 */
export function parseJobPage(html, url) {
  const page = html ?? '';
  if (page.trim() === '') return null;

  const externalId = extractExternalId(url);
  if (!externalId) return null;

  const heading = detag(firstMatch(page, /<h1[^>]*>([\s\S]*?)<\/h1>/i));
  const pageTitle =
    detag(firstMatch(page, /<title[^>]*>([\s\S]*?)<\/title>/i)) ?? metaContent(page, 'og:title');

  const named = pageTitle ? pageTitle.match(TITLE_RE) : null;
  const vacancy = named || !pageTitle ? null : pageTitle.match(VACANCY_RE);

  const title = heading || named?.[1] || vacancy?.[1] || null;

  // No title at all means this is not a job page — a redirect, or a shape we
  // do not know. Skipped, never guessed at.
  if (!title) {
    logger.warn('Wuzzuf page had no readable title', { url });
    return null;
  }

  const description = metaContent(page, 'og:description');

  return {
    externalId,
    title,
    company: named ? clean(named[2]) : null,
    location: clean(named?.[3] ?? vacancy?.[2] ?? null),
    postedAt: extractPostedAt(page),
    excerpt: extractDescription(page) ?? description,
    // Shown in the message, and read by the filters like any other field:
    // "5 to 10 years experience" says more about the level than most titles.
    seniority: extractExperience(`${pageTitle ?? ''} ${description ?? ''}`),
  };
}

/**
 * The id is the slug's leading token: /jobs/p/yqpqphojdzwj-middle-full-stack…
 *
 * Stable across re-slugs of the same posting, and the only part of the URL
 * that is.
 */
export function extractExternalId(url) {
  const match = (url ?? '').match(/\/jobs\/p\/([a-z0-9]+)-/i);
  return match ? match[1] : null;
}

/** "5 to 10 years experience", as Wuzzuf states it, or null when it does not. */
export function extractExperience(text) {
  const match = (text ?? '').match(EXPERIENCE_RE);
  return match ? `${match[1]} to ${match[2]} years experience` : null;
}

/**
 * Wuzzuf never prints a posting date, only an age ("3 days ago"). Converted
 * here so the age filter and the message both have something absolute to work
 * with; the conversion itself is shared, since Serper returns the same shape.
 */
export function extractPostedAt(text, now = Date.now()) {
  return relativeToIso(text, now);
}

/**
 * The description, taken from the "Job Description" heading onward.
 *
 * Only a window of the page is detagged, rather than the whole document: the
 * point of parsing this way is never to hold a 700 KB page in expanded form.
 */
function extractDescription(page) {
  const start = page.search(/>\s*Job Description\s*</i);
  if (start === -1) return null;

  const window = page.slice(start, start + DESCRIPTION_WINDOW);
  const body = detag(window.replace(/^[^<]*</, '<'));
  if (!body) return null;

  // Cut at whatever section follows, so the message does not carry the page's
  // company blurb and similar-jobs list.
  const end = body.search(/Job Requirements|Job Details|About the Company|Similar Jobs/i);
  const text = (end === -1 ? body : body.slice(0, end)).replace(/^Job Description\s*/i, '').trim();

  return text === '' ? null : text;
}

/** Attribute order varies, so the tag is found first and read afterwards. */
function metaContent(page, property) {
  const pattern = new RegExp(`<meta[^>]*(?:property|name)=["']${property}["'][^>]*>`, 'i');
  const tag = page.match(pattern)?.[0];
  if (!tag) return null;

  return clean(decode(firstMatch(tag, /content=["']([\s\S]*?)["']/i)));
}

/** Strips tags — scripts and styles content included — and collapses space. */
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

function firstMatch(text, pattern) {
  return text.match(pattern)?.[1] ?? null;
}

function clean(value) {
  const text = (value ?? '').replace(/\s+/g, ' ').trim();
  return text === '' ? null : text;
}

export default parseJobPage;
