import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import { cannotApply } from '../filters.js';
import { containsAny, fold } from '../text.js';
import BaseSource from './baseSource.js';
import LinkedInGuestClient from './linkedinClient.js';
import { parseJobCards } from './jobCardParser.js';
import { parseJobDetail } from './jobDetailParser.js';
import { blocked, US_ONLY, WORLDWIDE } from './abroadFit.js';
import { newCounts, summarize, tally, titleFits } from './abroad.js';

export const KEY = 'linkedin_worldwide';
export const PLACE = 'Worldwide';

/** Where a kept job is filed: a 🌍 market is Worldwide to the region and settled for the location rule. */
export const LANE = '🌍 Remote, worldwide';

/**
 * Remote LinkedIn jobs open to someone anywhere — or at least to EMEA,
 * Africa, Egypt or UTC+2/+3 — searched as "Worldwide" with LinkedIn's remote
 * filter (f_WT=2), with the Egypt search's keywords. Ported from the Android
 * app's LinkedInWorldwide.kt, and off by default there and here: it spends
 * LinkedIn's patience, which the Egypt search needs.
 *
 * LinkedIn has no "accepts any country" filter, and most "Worldwide, remote"
 * results are remote inside one country. So each opened job passes two
 * checks (judgeWorldwide): nothing that limits who may apply, and something
 * in the description that says it takes people from anywhere.
 */
export class LinkedInWorldwideSource extends BaseSource {
  constructor(client = null) {
    super(KEY, {
      client: client ?? new LinkedInGuestClient({ label: 'LinkedIn worldwide' }),
    });
  }

  async fetch(options = {}) {
    const linkedin = config.sources.linkedin_jobs;
    const keywords = options.keywords?.length ? options.keywords : config.keywords;
    const window = windowSince(Number.parseInt(this.store?.getState(`${KEY}.lastOk`) ?? '', 10));
    const cards = new Map();
    const counts = newCounts();

    try {
      for (const keyword of keywords) {
        const html = await this.client.getHtml(linkedin.searchUrl, params(keyword, window));
        for (const card of parseJobCards(html, keyword, null)) {
          if (!cards.has(card.externalId)) cards.set(card.externalId, { ...card, source: KEY, itemType: 'job' });
        }
      }

      const checked = this.checked();
      counts.listed = cards.size;
      counts.seen = [...cards.keys()].filter((id) => checked.has(id)).length;

      const wanted = [...cards.values()]
        .filter((card) => !checked.has(card.externalId) && !this.store?.findByExternalId(KEY, card.externalId) && titleFits(card))
        .slice(0, this.settings.maxDetails);

      for (const card of wanted) {
        const { description, criteria } = parseJobDetail(await this.client.getHtml(`${linkedin.detailUrl}${card.externalId}`));
        this.markChecked(card.externalId);
        counts.judged += 1;
        tally(judgeWorldwide(card, description, criteria), counts);
      }
    } catch (error) {
      if (error instanceof BlockedError) error.partialItems = counts.kept;
      throw error;
    } finally {
      this.addListed(counts.listed);
      logger.info('LinkedIn worldwide', summarize(counts));
    }

    if (!this.dryRun) this.store?.setState(`${KEY}.lastOk`, Date.now());
    return counts.kept;
  }
}

/** f_TPR reaching back to the last clean run (and a little more), an hour to a week. */
export function windowSince(lastOk, now = Date.now()) {
  const seconds = Number.isFinite(lastOk) ? Math.ceil((now - lastOk) / 1000) + 600 : 3600;
  return `r${Math.max(3600, Math.min(604_800, seconds))}`;
}

export const params = (keyword, window) => ({
  keywords: keyword,
  location: PLACE,
  start: 0,
  f_TPR: window,
  // 1 internship, 2 entry, 3 associate, 4 mid-senior: the Egypt search's levels.
  f_E: '1,2,3,4',
  // Remote only.
  f_WT: '2',
});

/**
 * The job filled in from its page, and why it is dropped, if it is. Check
 * one, on everything the job says: nothing that limits who may apply. Check
 * two, on the description alone: something that says it is open — the
 * card's "Worldwide" is only the search, so it does not count.
 */
export function judgeWorldwide(card, description, criteria = {}) {
  const body = description ?? '';
  const text = `${card.title}\n${card.location ?? ''}\n${body}`;
  const folded = fold(text);
  const type = criteria['employment type'] ?? null;

  const item = {
    ...card,
    market: LANE,
    seniority: criteria['seniority level'] ?? null,
    employmentType: type,
    workplace: 'Remote',
    remote: true,
    excerpt: [LANE, body].filter(Boolean).join('\n').slice(0, 4000),
  };

  let rejected = null;
  if (!body.trim()) rejected = 'no-description';
  else if (/intern/i.test(type ?? '')) rejected = 'internship';
  else if (/part/i.test(type ?? '')) rejected = 'part-time';
  else if (blocked(text)) rejected = blocked(text);
  else if (containsAny(folded, US_ONLY)) rejected = 'us-only';
  else if (cannotApply(folded, config.filters.ineligible, config.filters.egyptPlaces)) rejected = 'ineligible';
  else if (!containsAny(fold(body), WORLDWIDE)) rejected = 'not-open-worldwide';

  return { item, rejected };
}

export default LinkedInWorldwideSource;
