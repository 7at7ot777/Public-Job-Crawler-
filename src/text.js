/*
 * Term matching shared by the filters and everything that ranks or places a
 * job (score, region, abroad fit). Kept apart from filters.js so those modules
 * can use it without importing the filter gates, and the gates can use them.
 */

/*
 * Apostrophes as LinkedIn, phones and word processors write them. Every term
 * list here is typed with a plain ', so "We’re hiring" matched nothing until
 * these were folded into it.
 */
const APOSTROPHES = /[‘’‛ʼ′]/g;

/** Text as the term lists are written: lowercase, with plain apostrophes. */
export function fold(text) {
  return String(text ?? '').toLowerCase().replace(APOSTROPHES, "'");
}

const termCache = new Map();

/** The whole-word regex for one folded term, cached: the lists are matched thousands of times a run. */
export function termRegex(needle) {
  let re = termCache.get(needle);

  if (!re) {
    const escaped = needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    re = new RegExp(`(?<![a-z0-9])${escaped}(?![a-z0-9])`, 'i');
    termCache.set(needle, re);
  }

  return re;
}

/** Word-ish boundary match so "node" does not hit "nodemon" or "anode". */
export function containsTerm(text, term) {
  const needle = fold(term).trim();
  if (needle === '') return false;

  // Allow the term to sit next to punctuation but not inside a longer word.
  return termRegex(needle).test(text);
}

export const containsAny = (text, terms) => (terms ?? []).some((term) => containsTerm(text, term));

/** The first of `terms` found in `text`, or null: what made a rule fire, for the decision log. */
export const firstMatch = (text, terms) =>
  (terms ?? []).find((term) => containsTerm(text, term)) ?? null;

/** Telegram HTML text escape. */
export function escapeHtml(value) {
  return String(value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** An https anchor, or the escaped label alone for anything else. */
export function htmlLink(url, label) {
  if (!url || !String(url).startsWith('https://')) return escapeHtml(label);
  return `<a href="${escapeHtml(url).replace(/"/g, '&quot;')}">${escapeHtml(label)}</a>`;
}

/** Lowercased, punctuation dropped, whitespace collapsed: how companies are compared. */
export function normalizeName(value) {
  if (!value) return null;

  const text = String(value)
    .toLowerCase()
    .normalize('NFKD')
    // Keep letters and digits in any script: company names here are as often
    // Arabic as Latin.
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim()
    .replace(/\s+/g, ' ');

  return text === '' ? null : text;
}

/** Shared by the regex-only parsers, which never build a DOM for a whole page. */
export function decodeEntities(value) {
  if (value == null) return null;

  return String(value)
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/&#(\d+);/g, (match, code) => safeChar(Number(code), match))
    .replace(/&#x([0-9a-f]+);/gi, (match, code) => safeChar(parseInt(code, 16), match));
}

function safeChar(code, fallback) {
  try {
    return String.fromCodePoint(code);
  } catch {
    return fallback;
  }
}

/** Whitespace collapsed; null when nothing is left. */
export function clean(value) {
  const text = String(value ?? '').replace(/\s+/g, ' ').trim();
  return text === '' ? null : text;
}

/** Tags dropped (script and style with their contents), entities decoded, whitespace collapsed. */
export function detag(fragment) {
  if (fragment == null) return null;

  const text = String(fragment)
    .replace(/<(script|style|noscript)\b[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<[^>]*>/g, ' ');

  return clean(decodeEntities(text));
}

/** Line breaks kept, tags dropped: for descriptions that arrive as HTML in JSON. */
export function stripHtml(value) {
  if (!value) return '';

  return (
    decodeEntities(
      String(value)
        .replace(/<\s*(br|\/p|\/li|\/h\d)\s*\/?>/gi, '\n')
        .replace(/<[^>]+>/g, ' '),
    ) ?? ''
  )
    .replace(/[ \t]+/g, ' ')
    .replace(/\s*\n\s*/g, '\n')
    .trim();
}
