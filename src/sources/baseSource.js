import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import LinkedInGuestClient from './linkedinClient.js';

/**
 * The shape the sources ported from the Android app share: a key, its
 * settings in config.sources, a paced client of its own (its own delay, not
 * LinkedIn's REQUEST_DELAY_MS), the store for its checked-pages ledger, and a
 * `listed` count the pipeline's breakage check reads (see src/breakage.js).
 */
export class BaseSource {
  constructor(key, { label, headers = {}, client = null } = {}) {
    this.key = key;
    this.store = null;
    this.listed = null;
    // Set by the pipeline per run: a dry run writes nothing, not even the ledger.
    this.dryRun = false;

    const settings = config.sources[key] ?? {};
    this.client =
      client ??
      new LinkedInGuestClient({
        label: label ?? key,
        timeoutMs: settings.timeoutMs ?? config.http.timeoutMs,
        delayMs: settings.delayMs ?? 1500,
        headers,
      });
  }

  get settings() {
    return config.sources[this.key] ?? {};
  }

  isEnabled() {
    return Boolean(this.settings.enabled);
  }

  useStore(store) {
    this.store = store;
  }

  /** Every external id this source has already opened and judged. */
  checked() {
    try {
      return this.store?.checkedIds(this.key) ?? new Set();
    } catch {
      return new Set();
    }
  }

  markChecked(id) {
    if (this.dryRun) return;
    try {
      this.store?.markChecked(this.key, id);
    } catch {
      // A ledger miss costs one re-judged page next run, nothing more.
    }
  }

  /**
   * Walks a list longer than one run's time, starting where the last run
   * stopped. The position moves past an entry before it is visited, so an
   * entry that always fails is passed over next lap instead of being the one
   * every run starts on.
   */
  async walk(entries, stateKey, visit) {
    if (entries.length === 0) return;

    let start = 0;
    try {
      start = (Number.parseInt(this.store?.getState(stateKey) ?? '0', 10) || 0) % entries.length;
    } catch {
      start = 0;
    }

    for (let step = 0; step < entries.length; step += 1) {
      const index = (start + step) % entries.length;
      try {
        if (!this.dryRun) this.store?.setState(stateKey, (index + 1) % entries.length);
      } catch {
        // No store (a dry test): walk from the top every time.
      }
      if ((await visit(entries[index])) === false) return;
    }
  }

  /**
   * A page that parsed to nothing, said once: a 202 is a bot challenge
   * (Khamsat and Mostaql answer some hosts with one), not a quiet board.
   */
  warnIfChallenged(parsedCount) {
    const status = this.client.lastResponse?.status;
    if (parsedCount === 0 && status === 202) {
      logger.warn(`${this.key}: answered a 202 challenge page instead of the listing`, {
        hint: 'The board does not serve this host; it works from the phone app (a residential IP).',
      });
    }
  }

  /** What the remote listed this run, before the source's own checks. */
  addListed(n) {
    this.listed = (this.listed ?? 0) + n;
  }

  /**
   * One page, or null when the remote never answered (transport). A refusal
   * — a throttle, a block — is thrown, carrying partial results.
   */
  async get(url, query = {}, partial = []) {
    try {
      return await this.client.getHtml(url, query);
    } catch (error) {
      if (error instanceof BlockedError && error.transport) {
        logger.warn(`${this.key}: unreachable`, { url, error: error.message });
        return null;
      }
      if (error instanceof BlockedError) error.partialItems = partial;
      throw error;
    }
  }
}

export default BaseSource;
