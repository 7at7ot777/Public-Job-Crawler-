import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import LinkedInGuestClient from './linkedinClient.js';
import { containsAny, locationAllowed } from '../filters.js';
import { parseJobCards } from './jobCardParser.js';
import { parseJobDetail } from './jobDetailParser.js';

export const KEY = 'linkedin_jobs';

/**
 * Crawls LinkedIn's public guest job search for every configured
 * keyword x location pair.
 *
 * Only the logged-out endpoint is used: no credentials, no cookies, no account.
 */
export class LinkedInJobsSource {
  constructor(client = new LinkedInGuestClient()) {
    this.key = KEY;
    this.client = client;
  }

  get settings() {
    return config.sources[KEY];
  }

  isEnabled() {
    return Boolean(this.settings.enabled);
  }

  /**
   * The pipeline hands the store over for two things: skipping detail pages
   * of postings already stored, and remembering where the location rotation
   * stopped between the short-lived processes the container runs.
   */
  useStore(store) {
    this.store = store;
  }

  /**
   * @param {{keywords?: string[], locations?: string[], pages?: number, limit?: number}} options
   * @returns {Promise<object[]>}
   * @throws {BlockedError} carrying whatever was parsed before the block.
   */
  async fetch(options = {}) {
    const rotation = this.#rotation(options);
    const plan = rotation ? rotation.pairs : this.#plan(options);
    const limit = options.limit ?? null;
    const timeWindow = rotation ? rotation.timeWindow : (options.timeWindow ?? null);

    if (plan.length === 0) {
      logger.warn('LinkedIn jobs source has no keywords or locations configured');
      return [];
    }

    if (rotation) {
      logger.info('LinkedIn location rotation', {
        location: rotation.label,
        slot: rotation.slot + 1,
        of: rotation.count,
        searches: plan.length,
        timeWindow,
      });
    }

    // The same posting surfaces under several keywords, so dedup within the run.
    const seen = new Map();

    for (const { keyword, target } of plan) {
      const pages = options.pages ?? target.maxPages;

      try {
        await this.#fetchPair(keyword, target, pages, limit, seen, timeWindow);
      } catch (error) {
        if (error instanceof BlockedError) {
          // Hand back what we already have: partial results are independently
          // valid and should still reach Telegram.
          error.partialItems = await this.#enrich([...seen.values()]);
        }
        throw error;
      }

      if (limit !== null && seen.size >= limit) break;
    }

    // Only a pass that finished moves the rotation on. A blocked one throws
    // above and searches the same location again next time; the one interval
    // of margin in the window absorbs the delay that puts on the others.
    if (rotation) this.#advanceRotation(rotation, options);

    return this.#enrich([...seen.values()]);
  }

  /**
   * This run's share of the search when LINKEDIN_ROTATE_LOCATIONS is on: one
   * location (with every keyword) per run, the next one on the next run, so a
   * full cycle takes one run per location and every run costs the same
   * number of requests however many locations are configured.
   *
   * Returns null — search everything — when rotation is off, or when the run
   * asked for something specific: a --keyword / --location run, or a time
   * window of its own (the startup pass in --watch, a bot /jobs request).
   * Those want the whole picture, not one slice of it.
   *
   * @returns {{pairs: object[], label: string, slot: number, count: number,
   *            timeWindow: string}|null}
   */
  #rotation(options) {
    if (!this.settings.rotateLocations) return null;
    if (options.keywords?.length || options.locations?.length) return null;
    if (options.timeWindow !== undefined && options.timeWindow !== null) return null;

    const groups = this.#groups();
    if (groups.length === 0) return null;

    const slot = this.#rotationSlot() % groups.length;

    return {
      ...groups[slot],
      slot,
      count: groups.length,
      timeWindow: rotationWindow(
        this.settings.timeWindow,
        groups.length,
        this.settings.rotateIntervalMinutes,
      ),
    };
  }

  /**
   * Every location the rotation steps through, each with all of its
   * searches: the stack keywords for each market, then the remoteKeywords
   * for each of their borderless markets. Same searches as #plan, grouped by
   * location instead of flattened.
   */
  #groups() {
    const groups = this.#targets({}).map((target) => ({
      label: target.location,
      pairs: config.keywords.map((keyword) => ({ keyword, target })),
    }));

    for (const location of config.remoteKeywordLocations) {
      const target = {
        location,
        workType: this.settings.remoteWorkType,
        maxPages: this.settings.remoteMaxPages,
        experienceLevels: this.settings.remoteKeywordExperience,
      };

      groups.push({
        label: `${location} (remote keywords)`,
        pairs: config.remoteKeywords.map((keyword) => ({ keyword, target })),
      });
    }

    return groups.filter((group) => group.pairs.length > 0);
  }

  /** Where the rotation stands: in the store when there is one, else in memory. */
  #rotationSlot() {
    if (this.store) {
      try {
        const stored = Number.parseInt(this.store.getState(ROTATION_KEY) ?? '', 10);
        if (Number.isInteger(stored) && stored >= 0) return stored;
      } catch (error) {
        logger.warn('Could not read the LinkedIn rotation; starting over', { error: error.message });
      }
      return 0;
    }

    return this.rotationSlot ?? 0;
  }

  #advanceRotation(rotation, options) {
    // A dry run stores nothing, and that includes where the rotation is.
    if (options.dryRun) return;

    const next = (rotation.slot + 1) % rotation.count;

    if (this.store) {
      try {
        this.store.setState(ROTATION_KEY, next);
      } catch (error) {
        // Losing it repeats one location next run, nothing more.
        logger.warn('Could not save the LinkedIn rotation', { error: error.message });
      }
      return;
    }

    this.rotationSlot = next;
  }

  /**
   * Postings the store already holds, dropped before their detail pages are
   * fetched. Only matches are stored, so a stored posting was already judged
   * and delivered (or queued for a retry, which the pipeline does from the
   * store): opening its page again is a request spent for nothing. A posting
   * stays inside the time window for several runs, so this is most of the
   * detail traffic.
   */
  #dropStored(items) {
    if (!this.store) return items;

    const fresh = items.filter((item) => {
      try {
        return this.store.findByExternalId(KEY, item.externalId) === null;
      } catch (error) {
        // A store that cannot answer is a reason to do more work, not to crash.
        logger.warn('Could not check LinkedIn job against the store', { error: error.message });
        return true;
      }
    });

    if (fresh.length < items.length) {
      logger.debug('Skipped postings already stored', { skipped: items.length - fresh.length });
    }

    return fresh;
  }

  /**
   * Every search this run will make, as keyword/market pairs.
   *
   * A plain cross product would not do: `remoteKeywords` are wanted only as
   * remote work in the borderless markets, and the stack keywords are not
   * wanted there at all, so the two groups are paired separately and then
   * interleaved. A manual `--keyword` or `--location` run skips the second
   * group entirely — it asked for something specific.
   */
  #plan(options) {
    const keywords = options.keywords?.length ? options.keywords : config.keywords;
    const targets = this.#targets(options);

    const pairs = [];

    for (const keyword of keywords) {
      for (const target of targets) pairs.push({ keyword, target });
    }

    if (options.keywords?.length || options.locations?.length) return pairs;

    const remote = [];

    for (const keyword of config.remoteKeywords) {
      for (const location of config.remoteKeywordLocations) {
        remote.push({
          keyword,
          target: {
            location,
            workType: this.settings.remoteWorkType,
            maxPages: this.settings.remoteMaxPages,
            experienceLevels: this.settings.remoteKeywordExperience,
          },
        });
      }
    }

    return interleave(pairs, remote);
  }

  /**
   * Every market this run will search: the configured locations as they are,
   * then the remote-only markets.
   *
   * They are kept apart because they are asked for differently — the remote
   * markets carry f_WT=2 and page less deeply — and because `--location`
   * overrides only the local list, so a narrow manual run does not silently
   * drag nine foreign markets along with it.
   */
  #targets(options) {
    const locations = options.locations?.length ? options.locations : config.locations;

    const local = locations.map((location) => ({
      location,
      workType: '',
      maxPages: this.settings.maxPages,
    }));

    if (options.locations?.length) return local;

    const remote = config.remoteLocations.map((location) => ({
      location,
      workType: this.settings.remoteWorkType,
      maxPages: this.settings.remoteMaxPages,
    }));

    // Interleaved, not appended: a run that stops at --limit or at a block
    // would otherwise spend its whole budget on the local markets and never
    // reach the remote ones.
    return interleave(local, remote);
  }

  /**
   * Fetch each posting's detail page and attach its description as `excerpt`.
   *
   * A search card is only a title, which is not enough to judge seniority or to
   * find a contact address. This costs one extra request per posting, so it is
   * opt-in via LINKEDIN_FETCH_DETAILS.
   */
  async #enrich(found) {
    const items = this.#dropStored(found);

    if (!this.settings.fetchDetails || items.length === 0) return items;

    /*
     * Only fetch details for cards that could still pass. A detail page costs a
     * paced request each, so enriching everything means hundreds of requests to
     * discard nearly all of them — slow, and a fast route to a 429.
     *
     * The pre-check only rejects on things a description cannot change: the
     * posting's location and its title. See #couldPass.
     */
    const candidates = config.filters.enabled
      ? items.filter((item) => this.#couldPass(item))
      : items;

    logger.debug('Fetching job details', {
      candidates: candidates.length,
      skipped: items.length - candidates.length,
    });

    let enriched = 0;

    for (const item of candidates) {
      try {
        const html = await this.client.getHtml(`${this.settings.detailUrl}${item.externalId}`);
        const { description, criteria } = parseJobDetail(html);

        item.excerpt = description;
        item.seniority = criteria['seniority level'] ?? null;
        item.employmentType = criteria['employment type'] ?? null;
        enriched += 1;
      } catch (error) {
        /*
         * A block is about the client, not this page: every further request
         * would be throttled too, and the parsed items are worth more than
         * complete descriptions. Stop here and hand the items back — the tail
         * is judged on its card alone, and because the search's time window
         * still covers it, the next pass fetches its detail page fresh.
         */
        if (error instanceof BlockedError) {
          logger.warn('Detail fetches stopped at a block', {
            externalId: item.externalId,
            enriched,
            remaining: candidates.length - enriched,
            error: error.message,
          });

          return items;
        }

        // A single unreadable detail page must not cost us the posting.
        logger.warn('Could not fetch job detail', {
          externalId: item.externalId,
          error: error.message,
        });
      }
    }

    return items;
  }

  async #fetchPair(keyword, target, maxPages, limit, seen, timeWindowOverride = null) {
    const { searchUrl, pageSize } = this.settings;
    const timeWindow = timeWindowOverride ?? this.settings.timeWindow;

    for (let page = 0; page < maxPages; page += 1) {
      const html = await this.client.getHtml(searchUrl, {
        keywords: keyword,
        location: target.location,
        start: page * pageSize,
        f_TPR: timeWindow,
        // Server-side experience filter: 1 internship, 2 entry, 3 associate,
        // 4 mid-senior, 5 director. Narrowing here saves requests later.
        // The remoteKeywords group carries its own, narrower levels.
        f_E: target.experienceLevels ?? this.settings.experienceLevels,
        // Work type, set only for the remote-only markets: 2 is remote.
        // Empty is dropped by the client, leaving the search unrestricted.
        f_WT: target.workType,
      });

      const parsed = parseJobCards(html, keyword, target.location);

      // Empty body or no cards: we are past the end of this result set.
      if (parsed.length === 0) {
        // On the first page with a body, it is either a narrow search or a
        // markup change. Debug, since a narrow keyword x location is common.
        if (page === 0 && html.trim() !== '') {
          logger.debug('LinkedIn search page had no job cards', {
            keyword,
            location: target.location,
            ...(this.client.lastResponse ?? {}),
          });
        }
        return;
      }

      for (const item of parsed) {
        // LinkedIn answered this search with f_WT=2, so the posting is remote
        // by the provider's own classification — the filters then need not go
        // looking for the word in a description that may never use it.
        item.remote = target.workType === '2';

        if (!seen.has(item.externalId)) {
          seen.set(item.externalId, item);
        }

        if (limit !== null && seen.size >= limit) return;
      }
    }
  }

  /**
   * Cheap pre-filter using only the search card.
   *
   * Conservative by design: it rejects solely on signals a detail page cannot
   * overturn — where the job is, and what the title says. The body technology
   * check is left to the real filter, which needs the description.
   */
  #couldPass(item) {
    const { locations, seniorityExclude, titleTerms, blockedLocations } = config.filters;

    const card = [item.title, item.company, item.location]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();

    // Both read only the title and the card's location, so a detail page can
    // never overturn them.
    const placed = [item.title, item.location].filter(Boolean).join(' ').toLowerCase();

    if (blockedLocations.length > 0 && containsAny(placed, blockedLocations)) return false;

    if (titleTerms.length > 0 && !containsAny((item.title ?? '').toLowerCase(), titleTerms)) {
      return false;
    }

    // Same rule the filters apply: a search that named a market has already
    // settled where the posting is. Reading the card instead would reject
    // every US or UK result, whose card names a city and no country — and a
    // rejected card is never enriched, so it would then fail the tech rule
    // too, for want of the description that would have passed it.
    if (locations.length > 0 && !locationAllowed(item, card)) {
      return false;
    }

    const title = (item.title ?? '').toLowerCase();

    return !seniorityExclude.some((term) =>
      new RegExp(`(?<![a-z0-9])${term.toLowerCase().replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![a-z0-9])`).test(
        title,
      ),
    );
  }
}

const ROTATION_KEY = `${KEY}:rotation_next`;

/**
 * The f_TPR window a rotating run searches with.
 *
 * With rotation, a location is searched once per cycle — one run per
 * location — so the window has to reach back at least that far or postings
 * between two visits are never seen. One more interval is added as margin,
 * for a pass that starts late or a blocked run that repeats a location.
 *
 * LINKEDIN_TIME_WINDOW stays the floor: the window is only ever widened, so
 * a short list keeps the configured r3600 and nothing changes. Five locations
 * at 10 minutes is exactly 3600 s; the sixth is where it starts to grow.
 *
 * @param {string} configured  LinkedIn's own spelling, e.g. "r3600".
 * @returns {string}
 */
export function rotationWindow(configured, locationCount, intervalMinutes) {
  const needed = (locationCount + 1) * Math.max(1, intervalMinutes) * 60;
  const match = /^r(\d+)$/.exec(String(configured ?? '').trim());

  // Not in seconds form, so not comparable: trust what was configured.
  if (configured && !match) return configured;

  const floor = match ? Number(match[1]) : 0;
  return `r${Math.max(floor, needed)}`;
}

/** Takes from each list in turn, so neither waits for the other to finish. */
function interleave(first, second) {
  const merged = [];

  for (let index = 0; index < Math.max(first.length, second.length); index += 1) {
    if (index < first.length) merged.push(first[index]);
    if (index < second.length) merged.push(second[index]);
  }

  return merged;
}

export default LinkedInJobsSource;
