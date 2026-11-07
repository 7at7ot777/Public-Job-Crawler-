import { containsAny, containsTerm, fold, normalizeName } from './text.js';
import { boostScore, stackOf } from './sources/cvStack.js';
import { scoreItem as abroadScore } from './sources/abroadFit.js';
import { isAbroad, regionOf, REGION } from './region.js';
import { titleWords } from './mutes.js';

/*
 * How well a job fits, 0–100, so the best of a run goes out first and the
 * message can show it. The filters already decided the job is worth sending;
 * this only ranks what passed. Ported from the Android app's Score.kt.
 *
 * At home (Egypt and the Gulf): the title's stack 30, the stack in the text
 * 25, years in reach 15, freshness 15, remote 5, an address to write to 5,
 * the region 5. Abroad, abroadFit already measures the CV fit and the chance
 * of being taken from Egypt, so its score is used with freshness. Then what
 * the person said: two 👎 on a company or a title word cost it.
 */

const DOWNS_TO_LEARN = 2;
const BACKEND_TERMS = ['backend', 'back-end', 'back end', 'api', 'server'];

let learned = { companies: new Map(), words: new Map(), liked: new Set() };

export const currentLearned = () => learned;

export function scoreOf(item, now = Date.now(), taught = learned) {
  const raw = isAbroad(item)
    ? Math.trunc(abroadScore(item) * 0.85 + freshness(item, now))
    : home(item, now);

  return Math.max(0, Math.min(100, raw + adjustment(item, taught)));
}

function home(item, now) {
  const title = item.title ?? '';
  const text = `${title}\n${item.excerpt ?? ''}`;
  const folded = fold(title);

  let titlePoints = 15;
  if ((item.itemType ?? item.item_type) === 'post') titlePoints = 15;
  else if (stackOf(title).length > 0) titlePoints = 30;
  else if (containsAny(folded, BACKEND_TERMS)) titlePoints = 22;

  const stack = Math.min(stackOf(text).length * 8, 17) + Math.min(boostScore(text) * 2, 8);
  const workplace = String(item.workplace ?? '').toLowerCase();
  const remote =
    item.remote === true || containsTerm(fold(text), 'remote') || workplace.includes('remote') || workplace.includes('hybrid')
      ? 5
      : 0;
  const email = (item.contactEmails ?? []).length > 0 ? 5 : 0;

  const regionPoints = { [REGION.EGYPT.name]: 5, [REGION.GULF.name]: 3, [REGION.WORLDWIDE.name]: 2, [REGION.FREELANCE.name]: 3 };
  const region = regionPoints[regionOf(item).name] ?? 0;

  return titlePoints + stack + yearsPoints(item) + freshness(item, now) + remote + email + region;
}

function yearsPoints(item) {
  const junior = containsAny(fold(item.title), ['junior', 'jr', 'mid', 'intermediate']);
  const min = item.yearsMin;
  if (min == null) return junior ? 15 : 8;

  const max = item.yearsMax;
  if (min <= 2 || (max != null && max <= 3) || junior) return 15;
  if (min === 3) return 12;
  if (min === 4) return 6;
  return 0;
}

/** When it was posted: a fresh one is worth applying to first. */
function freshness(item, now) {
  const at = Date.parse(item.postedAt ?? '');
  if (Number.isNaN(at)) return 8;

  const days = Math.trunc((now - at) / 3_600_000) / 24;
  if (days <= 1) return 15;
  if (days <= 3) return 11;
  if (days <= 7) return 7;
  if (days <= 14) return 3;
  return 0;
}

function adjustment(item, taught) {
  let points = 0;
  const company = item.company ? normalizeName(item.company) : null;

  if (company) {
    if ((taught.companies.get(company) ?? 0) >= DOWNS_TO_LEARN) points -= 25;
    if (taught.liked.has(company)) points += 5;
  }

  const wordPenalty =
    titleWords(item.title).filter((word) => (taught.words.get(word) ?? 0) >= DOWNS_TO_LEARN).length * 10;

  return points - Math.min(wordPenalty, 20);
}

/** Counts the 👎 per company and title word, and the companies with a 👍. */
export function learn(down = [], up = []) {
  const companies = new Map();
  const words = new Map();

  for (const item of down) {
    const company = item.company ? normalizeName(item.company) : null;
    if (company) companies.set(company, (companies.get(company) ?? 0) + 1);
    for (const word of titleWords(item.title)) words.set(word, (words.get(word) ?? 0) + 1);
  }

  const liked = new Set(up.map((item) => (item.company ? normalizeName(item.company) : null)).filter(Boolean));
  return { companies, words, liked };
}

/** At the start of a run: what the store's 👍 and 👎 say now. */
export function reloadLearned(store) {
  try {
    learned = learn(store.withFeedback('down'), store.withFeedback('up'));
  } catch {
    // Keep what was learned before.
  }
}
