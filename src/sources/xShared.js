/*
 * What both X sources need to know about X itself: which URLs are posts, what
 * a post's identity is, and how old it is.
 */

/** Twitter's epoch: status ids count milliseconds from here. */
const SNOWFLAKE_EPOCH = 1288834974657n;

const STATUS_RE = /^https?:\/\/(?:www\.|mobile\.)?(?:x|twitter)\.com\/([A-Za-z0-9_]{1,15})\/status(?:es)?\/(\d{5,25})/i;

/** Handles that are X's own pages, never an author. */
const RESERVED = new Set(['i', 'home', 'search', 'explore', 'hashtag', 'intent', 'share']);

/**
 * `{handle, id}` for a post permalink, or null for anything else — profiles,
 * searches and hashtag pages all match `site:x.com` too.
 */
export function parseStatusUrl(url) {
  const match = String(url ?? '').match(STATUS_RE);
  if (!match || RESERVED.has(match[1].toLowerCase())) return null;

  return { handle: match[1], id: match[2] };
}

export function isStatusUrl(url) {
  return parseStatusUrl(url) !== null;
}

/**
 * One canonical link per post. The same status is reachable on twitter.com,
 * mobile.twitter.com and with /photo/1 or ?s=20 on the end; all of them are
 * one row.
 */
export function statusUrl(handle, id) {
  return `https://x.com/${handle}/status/${id}`;
}

/**
 * When a post was written, read off its id. Every status id since 2010 is a
 * snowflake whose top bits are a timestamp, so even a search result with no
 * date of its own has an exact one.
 *
 * @returns {string|null} ISO timestamp
 */
export function snowflakeDate(id) {
  if (!/^\d{15,25}$/.test(String(id ?? ''))) return null;

  const ms = Number((BigInt(id) >> 22n) + SNOWFLAKE_EPOCH);
  const date = new Date(ms);

  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/**
 * Whether a post is older than a cutoff, given its own timestamp. Missing or
 * unparseable dates are never "too old" — absence of a date is not evidence —
 * and a cutoff of zero disables the test. Shared by both X sources.
 */
export function olderThanDays(iso, days) {
  if (!days || days <= 0 || !iso) return false;

  const age = Date.now() - new Date(iso).getTime();
  return Number.isFinite(age) && age > days * 86_400_000;
}

export function firstLine(text) {
  if (!text) return null;

  const line = text
    .split(/\r?\n/)
    .map((part) => part.trim())
    .find((part) => part !== '');

  return line ?? null;
}

export function truncate(value, max) {
  const text = (value ?? '').trim();
  if (text.length <= max) return text;

  return `${text.slice(0, max - 1).trimEnd()}…`;
}
