import { stripHtml } from './recruiteraParser.js';

/**
 * vertextech-eg.com, VerteX Technologies' own careers pages (Smart Village,
 * Cairo; the NIX brand's Egyptian office).
 *
 * A Framer site. The /vacancies list renders only its first ten cards on the
 * server, sorted by title, and loads the rest in the browser — so a new
 * opening that sorts after "J" never appears in it. The sitemap lists every
 * published vacancy, and each vacancy page is server-rendered, so those two
 * are read instead.
 *
 * Framer's classes are hashes, but the layers carry the names the designer
 * gave them ("Vacancy name", "Vacancy description"), and those are what this
 * leans on. Neither the sitemap nor the page carries a date.
 */

export const COMPANY = 'VerteX Technologies';

const VACANCY_URL = /^https?:\/\/(?:www\.)?vertextech-eg\.com\/vacancies\/([^/?#]+)\/?$/i;

/** The level chips the list filters by, in the site's own words. */
const LEVELS = ['Trainee', 'Junior', 'Middle', 'Senior', 'Tech lead', 'Architect', 'Principal'];

/**
 * @param {string} xml
 * @returns {{url: string, externalId: string}[]} every vacancy page, once.
 */
export function parseSitemap(xml) {
  const found = new Map();

  for (const match of (xml ?? '').matchAll(/<loc>\s*([^<]+?)\s*<\/loc>/g)) {
    const url = decodeEntities(match[1]);
    const externalId = extractExternalId(url);
    if (externalId && !found.has(externalId)) found.set(externalId, { url, externalId });
  }

  return [...found.values()];
}

/** The slug: /vacancies/junior-golang-developer. */
export function extractExternalId(url) {
  const slug = VACANCY_URL.exec(String(url ?? '').trim())?.[1];
  if (!slug) return null;

  try {
    return decodeURIComponent(slug).toLowerCase();
  } catch {
    return slug.toLowerCase();
  }
}

/**
 * @param {string} html  a vacancy page
 * @param {string} url
 * @returns {object|null}
 */
export function parseVacancyPage(html, url) {
  const page = html ?? '';
  const externalId = extractExternalId(url);
  if (!externalId || page.trim() === '') return null;

  const title =
    clean(stripHtml(firstGroup(page, /<h1[^>]*>([\s\S]*?)<\/h1>/i))) ??
    clean(firstGroup(page, /<title[^>]*>([^<]*)<\/title>/i)?.replace(/\s+-\s+VerteX Technologies\s*$/i, ''));

  if (!title) return null;

  const tags = tagsOf(page);
  const level = tags.find((tag) => LEVELS.some((name) => name.toLowerCase() === tag.toLowerCase())) ?? null;
  const description = descriptionOf(page);

  const excerpt = [tags.join(' · '), description]
    .filter(Boolean)
    .join('\n')
    .slice(0, 4000);

  return {
    externalId,
    title,
    url,
    company: COMPANY,
    location: locationOf(page),
    seniority: level,
    postedAt: null,
    excerpt: excerpt === '' ? null : excerpt,
  };
}

/** The level and the stack, the chips between the heading and the description. */
function tagsOf(page) {
  const start = page.indexOf('</h1>');
  const end = page.indexOf('data-framer-name="Vacancy description"');
  if (start === -1 || end === -1 || end < start) return [];

  const tags = [];

  for (const match of page.slice(start, end).matchAll(/<p[^>]*>([\s\S]*?)<\/p>/gi)) {
    const tag = clean(stripHtml(match[1]));
    if (tag && tag.length <= 60 && !tags.includes(tag)) tags.push(tag);
  }

  return tags;
}

/**
 * The rich-text layer named "Vacancy description". Framer puts no data-framer
 * attribute inside rich text, so the next one marks where it ends.
 */
function descriptionOf(page) {
  const marker = page.indexOf('data-framer-name="Vacancy description"');
  if (marker === -1) return null;

  const open = page.indexOf('>', marker);
  if (open === -1) return null;

  const next = page.indexOf('data-framer-', open);
  let window = page.slice(open + 1, next === -1 ? open + 20_000 : next);

  // The cut lands inside the next layer's opening tag.
  const lastTag = window.lastIndexOf('<');
  if (lastTag !== -1 && window.indexOf('>', lastTag) === -1) window = window.slice(0, lastTag);

  const text = stripHtml(window.replace(/&#x27;|&rsquo;/gi, "'"));
  return text === '' ? null : text;
}

/** "…job position in Cairo, Smart Village. Apply…" — the page's own place, plus the country. */
function locationOf(page) {
  const description = metaContent(page, 'description') ?? metaContent(page, 'og:description');
  const place = clean(description?.match(/\bposition in\s+([^.!|]+?)\s*[.!|]/i)?.[1]);

  if (!place) return 'Cairo, Egypt';
  return /egypt/i.test(place) ? place : `${place}, Egypt`;
}

function metaContent(page, name) {
  const tag = page.match(new RegExp(`<meta[^>]*(?:name|property)="${name}"[^>]*>`, 'i'))?.[0];
  return tag ? decodeEntities(firstGroup(tag, /content="([^"]*)"/i)) : null;
}

function firstGroup(text, pattern) {
  return pattern.exec(text)?.[1] ?? null;
}

function decodeEntities(value) {
  if (value == null) return null;

  return String(value)
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#x27;|&#0?39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>');
}

function clean(value) {
  const text = String(value ?? '')
    .replace(/\s+/g, ' ')
    .trim();

  return text === '' ? null : text;
}

export default parseVacancyPage;
