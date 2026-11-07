import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import LinkedInGuestClient from './linkedinClient.js';
import { parseBoard, parseBoardResponse, PLATFORMS } from './atsParser.js';
import { placeInGulf } from '../gulf.js';

export const KEY = 'ats';

/**
 * Career boards of named companies, on Lever, Greenhouse, Ashby, BambooHR,
 * Recruitee, Teamtailor, SuccessFactors, Breezy and Zoho Recruit, each read
 * through the public feed its own careers page is built from — Klivvr, Thndr,
 * Tamara, Instabug and so on.
 *
 * For the employers no board search reaches. Most Egyptian companies hire
 * through Wuzzuf, LinkedIn or Recruitera, which other sources already crawl,
 * so the list is short and named by hand: one "platform:slug" per company,
 * one request per company per run.
 *
 * A board is everything the company has open, sales and support included.
 * The shared filters narrow it to the wanted stack and places like any other
 * source.
 */
export class AtsSource {
  constructor(client = null) {
    this.key = KEY;
    this.client =
      client ??
      new LinkedInGuestClient({
        label: 'ATS',
        timeoutMs: config.sources[KEY].timeoutMs,
        headers: { Accept: 'application/json' },
      });
  }

  get settings() {
    return config.sources[KEY];
  }

  isEnabled() {
    return Boolean(this.settings.enabled);
  }

  /**
   * @param {{limit?: number, maxAgeDays?: number}} options
   * @returns {Promise<object[]>}
   * @throws {BlockedError} carrying whatever was parsed before the block.
   */
  async fetch(options = {}) {
    const limit = options.limit ?? null;
    const boards = this.#boards();
    const boardAges = boardMaxAgeDays(this.settings.boardMaxAgeDays);

    if (boards.length === 0) {
      logger.warn('ATS source has no boards configured');
      return [];
    }

    const items = [];

    for (const board of boards) {
      let jobs;

      try {
        const adapter = PLATFORMS[board.platform];
        // Workday's search is a paged POST; every other feed is one GET.
        const body = adapter.fetchBody
          ? await adapter.fetchBody(this.client, board.slug)
          : await this.client.getHtml(adapter.url(board.slug));
        jobs = parseBoardResponse(body, board);
      } catch (error) {
        // One company's board being down is not a reason to skip the rest.
        if (error instanceof BlockedError && !error.transport) {
          error.partialItems = items;
          throw error;
        }

        logger.warn('Could not read ATS board', { board: label(board), error: error.message });
        continue;
      }

      // A renamed or closed board answers 404, which arrives here as nothing.
      if (jobs === null) {
        logger.warn('ATS board returned no feed', {
          board: label(board),
          ...(this.client.lastResponse ?? {}),
        });
        continue;
      }

      // An explicit run option wins, then the board's own cutoff, then the default.
      const maxAgeDays =
        options.maxAgeDays ?? boardAges.get(label(board).toLowerCase()) ?? this.settings.maxAgeDays;

      for (const job of jobs) {
        if (isOlderThan(job.postedAt, maxAgeDays)) continue;

        const item = {
          source: KEY,
          itemType: 'job',
          // A board belongs to a company, not a market: the location filter
          // reads where each job is.
          market: null,
          ...job,
        };

        // Saudi/UAE on (BOARDS_GULF): a job placed in Riyadh or Dubai gets that
        // country as its market, so the Egypt location rule lets it through.
        items.push(config.boardsGulf ? placeInGulf(item) : item);

        if (limit !== null && items.length >= limit) return items;
      }
    }

    return items;
  }

  #boards() {
    const boards = [];

    for (const entry of this.settings.boards) {
      const board = parseBoard(entry);

      if (board) {
        boards.push(board);
      } else {
        logger.warn('Ignoring ATS board with an unknown platform', {
          entry,
          platforms: Object.keys(PLATFORMS),
        });
      }
    }

    return boards;
  }
}

function label(board) {
  return `${board.platform}:${board.slug}`;
}

/**
 * "zoho:careers.migrationit.com:3" -> Map { "zoho:careers.migrationit.com" => 3 }.
 * Keys are lower-cased; an entry without a positive whole number of days is ignored.
 */
export function boardMaxAgeDays(entries) {
  const ages = new Map();

  for (const entry of entries ?? []) {
    const text = String(entry ?? '').trim();
    const cut = text.lastIndexOf(':');
    const days = Number(text.slice(cut + 1));

    if (cut <= 0 || !Number.isInteger(days) || days <= 0) {
      logger.warn('Ignoring ATS board age that is not platform:slug:days', { entry });
      continue;
    }

    ages.set(text.slice(0, cut).trim().toLowerCase(), days);
  }

  return ages;
}

/**
 * A posting without a date is kept: an unknown age is not an old one.
 *
 * A date with no time (Zoho's Date_Opened arrives as UTC midnight) is
 * compared by calendar day, so with 3 days a job opened on the 31st is still
 * kept all through the 3rd instead of being dropped at midnight.
 */
export function isOlderThan(postedAt, maxAgeDays, now = Date.now()) {
  if (!postedAt || !Number.isFinite(maxAgeDays) || maxAgeDays <= 0) return false;

  const posted = new Date(postedAt).getTime();
  if (Number.isNaN(posted)) return false;

  if (posted % 86_400_000 === 0) {
    const today = now - (now % 86_400_000);
    return today - posted > maxAgeDays * 86_400_000;
  }

  return now - posted > maxAgeDays * 86_400_000;
}

export default AtsSource;
