import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import LinkedInGuestClient from './linkedinClient.js';
import { parseSitemap, parseVacancyPage } from './vertexParser.js';

export const KEY = 'vertex';

/**
 * VerteX Technologies' vacancies (vertextech-eg.com/vacancies), read through
 * the site's sitemap and each vacancy's own page — see vertexParser for why
 * the list page itself is not enough.
 *
 * Nothing on the site is dated, so "new" can only mean "not listed last
 * time". The checked ledger is that memory: every slug opened is recorded,
 * and only slugs it has never seen are opened. The very first run has no
 * memory, so it records what is open today and sends none of it — otherwise
 * the channel would get the whole board, months-old openings included, the
 * day this source is switched on. VERTEX_BASELINE=false sends them instead.
 */
export class VertexSource {
  constructor(client = null, store = null) {
    this.key = KEY;
    this.store = store;
    this.client =
      client ??
      new LinkedInGuestClient({
        label: 'VerteX',
        timeoutMs: config.sources[KEY].timeoutMs,
      });
  }

  get settings() {
    return config.sources[KEY];
  }

  isEnabled() {
    return Boolean(this.settings.enabled);
  }

  /** The pipeline hands the store over so the source can skip known vacancies. */
  useStore(store) {
    this.store = store;
  }

  /**
   * @param {{limit?: number}} options
   * @returns {Promise<object[]>}
   * @throws {BlockedError} carrying whatever was parsed before the block.
   */
  async fetch(options = {}) {
    const cap = Math.min(options.limit ?? Infinity, this.settings.maxDetails);

    const xml = await this.client.getHtml(this.settings.sitemapUrl);
    const candidates = parseSitemap(xml);

    if (candidates.length === 0) {
      logger.warn('VerteX sitemap listed no vacancies', {
        sitemapUrl: this.settings.sitemapUrl,
        ...(this.client.lastResponse ?? {}),
      });
      return [];
    }

    const checked = this.#checkedIds();

    if (checked.size === 0 && this.settings.baseline && this.store?.markChecked) {
      for (const { externalId } of candidates) this.#markChecked(externalId);

      logger.info('VerteX: first run, recorded the open vacancies without sending them', {
        vacancies: candidates.length,
        hint: 'Set VERTEX_BASELINE=false to send what is already open instead.',
      });
      return [];
    }

    const wanted = [];

    for (const candidate of candidates) {
      if (checked.has(candidate.externalId)) {
        // Still listed: touched again, so a ledger that ages entries out never
        // forgets a vacancy that is still open and sends it as new.
        this.#markChecked(candidate.externalId);
        continue;
      }

      if (this.#alreadyStored(candidate.externalId)) continue;
      wanted.push(candidate);
    }

    logger.debug('VerteX candidates', {
      listed: candidates.length,
      unseen: wanted.length,
      fetching: Math.min(wanted.length, cap),
    });

    return this.#fetchPages(wanted.slice(0, cap));
  }

  /** @throws {BlockedError} carrying the items already parsed. */
  async #fetchPages(candidates) {
    const items = [];

    for (const candidate of candidates) {
      let html;

      try {
        html = await this.client.getHtml(candidate.url);
      } catch (error) {
        if (error instanceof BlockedError && !error.transport) {
          error.partialItems = items;
          throw error;
        }

        logger.warn('Could not fetch VerteX vacancy', { url: candidate.url, error: error.message });
        this.#markChecked(candidate.externalId);
        continue;
      }

      // Opened, so never open it again — whatever is decided below.
      this.#markChecked(candidate.externalId);

      const parsed = parseVacancyPage(html, candidate.url);

      if (!parsed) {
        logger.warn('VerteX page did not parse', {
          url: candidate.url,
          ...(this.client.lastResponse ?? {}),
        });
        continue;
      }

      items.push({
        source: KEY,
        itemType: 'job',
        // Every VerteX vacancy is at its Smart Village office.
        market: 'Egypt',
        ...parsed,
      });
    }

    return items;
  }

  #checkedIds() {
    if (!this.store?.checkedIds) return new Set();

    try {
      return this.store.checkedIds(KEY);
    } catch (error) {
      logger.warn('Could not read the VerteX checked list', { error: error.message });
      return new Set();
    }
  }

  #markChecked(externalId) {
    try {
      this.store?.markChecked?.(KEY, externalId);
    } catch (error) {
      logger.warn('Could not record a checked VerteX vacancy', { error: error.message });
    }
  }

  #alreadyStored(externalId) {
    if (!this.store) return false;

    try {
      return this.store.findByExternalId(KEY, externalId) !== null;
    } catch (error) {
      logger.warn('Could not check VerteX vacancy against the store', { error: error.message });
      return false;
    }
  }
}

export default VertexSource;
