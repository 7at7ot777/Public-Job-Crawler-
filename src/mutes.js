import { containsTerm, fold, normalizeName } from './text.js';

/*
 * Companies and title words never wanted again, set from Telegram
 * (/mute_company, /mute_word) as the Android app sets them from a job's
 * screen. Filters are pure, so the list is a snapshot the pipeline loads from
 * the store at the start of a run (loadMutes) — the same shape as the app.
 */

let current = [];

/** @param {{kind: 'company'|'word', value: string, label?: string}[]} entries */
export function replaceMutes(entries) {
  current = Array.isArray(entries) ? [...entries] : [];
}

export const allMutes = () => current;

/** Reads the store's mutes into what evaluate() uses; a store without the table keeps what was there. */
export function loadMutes(store) {
  try {
    current = store.mutes?.() ?? current;
  } catch {
    // A diagnostic convenience, never a reason to stop a crawl.
  }
}

/** How a company is compared: the store's fingerprint normalization. */
export const muteKey = (company) => normalizeName(company) ?? String(company).trim().toLowerCase();

/** Words too common in titles to mean anything on their own: never penalised. */
const GENERIC = new Set([
  'developer', 'engineer', 'software', 'backend', 'back', 'end', 'full', 'stack', 'web', 'junior', 'mid',
  'level', 'senior', 'remote', 'the', 'and', 'for', 'with', 'job', 'jobs', 'hiring', 'role', 'team', 'new',
  'egypt', 'cairo', 'giza', 'hybrid', 'onsite', 'site', 'time', 'part',
]);

/** The words of a title worth muting or learning from: folded, three letters or more, not generic. */
export function titleWords(title) {
  const words = fold(title)
    .split(/[^\p{L}\p{N}+#.]+/u)
    .map((word) => word.replace(/^[.-]+|[.-]+$/g, ''))
    .filter((word) => word.length >= 3 && !GENERIC.has(word) && /\p{L}/u.test(word));
  return [...new Set(words)];
}

/** "muted-company" or "muted-title" when a mute matches, else null. */
export function muteReason(item, mutes = current) {
  if (!mutes?.length) return null;

  const company = item.company ? normalizeName(item.company) : null;
  if (company && mutes.some((m) => m.kind === 'company' && m.value === company)) return 'muted-company';

  const title = fold(item.title);
  const word = mutes.find((m) => m.kind === 'word' && containsTerm(title, m.value));
  if (word) return 'muted-title';

  return null;
}
