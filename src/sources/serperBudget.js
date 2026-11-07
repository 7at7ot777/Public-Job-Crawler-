import config from '../config.js';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';

/*
 * What Serper has cost, and the cap that stops it costing more.
 *
 * The key carries no spend limit of its own, so a misconfigured list (ten
 * keywords x twenty places), a restart loop or a stray manual run could spend
 * the month's credits in a day. The count lives in the store's state table —
 * one row per day — so the sources that share the key share the cap.
 */

const PREFIX = 'serper.queries.';
const BALANCE_DAY = 'serper.balance.day';
const BALANCE = 'serper.balance';

/** 'YYYY-MM-DD' in the crawler's timezone, which is when "a day" turns over. */
export function dayOf(date = new Date(), timeZone = config.schedule.timezone) {
  // en-CA formats as ISO.
  return new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date);
}

/**
 * One line per search-engine source run: how many queries it sent, and for a
 * billed engine where that leaves the day's cap and the account balance.
 */
export function logSearchBudget(source, engine, queries, budget = null) {
  const usage = budget?.usage();

  logger.info('Search budget', {
    source,
    engine: engine?.label ?? 'unknown',
    billed: Boolean(engine?.billed),
    queriesThisRun: queries,
    ...(usage
      ? {
          queriesToday: usage.today,
          dailyCap: usage.dailyCap || 'none',
          queriesThisMonth: usage.month,
          lastBalance: budget.lastBalance(),
        }
      : {}),
  });
}

export class SerperBudget {
  /**
   * @param {object|null} store  anything with getState / setState; without one
   *                             the count lives in memory, for this process only
   */
  constructor(store = null, settings = config.serper, now = () => new Date()) {
    this.store = store;
    this.settings = settings;
    this.now = now;
    this.memory = new Map();
  }

  #today() {
    return dayOf(this.now());
  }

  /** Queries spent on a day. */
  used(day = this.#today()) {
    const value = this.store ? this.store.getState(PREFIX + day) : this.memory.get(day);
    return Number.parseInt(value ?? '0', 10) || 0;
  }

  /**
   * Count one billed query, before it is sent.
   *
   * @throws {BlockedError} flagged as an alert when the day's cap is reached:
   *         the source stops, and what it found so far is delivered.
   */
  spend(label = 'Serper') {
    const day = this.#today();
    const used = this.used(day);
    const cap = this.settings.dailyCap;

    if (cap > 0 && used >= cap) {
      const error = new BlockedError(
        `${label}: the daily Serper cap of ${cap} queries is reached ` +
          `(SERPER_DAILY_CAP); the rest of the search waits for tomorrow`,
      );
      error.alert = 'serper-cap';
      throw error;
    }

    const next = String(used + 1);
    if (this.store) this.store.setState(PREFIX + day, next);
    else this.memory.set(day, next);
  }

  /** @returns {{today: number, month: number, dailyCap: number, days: object}} */
  usage() {
    const today = this.#today();
    const month = today.slice(0, 7);
    const days = {};

    if (this.store?.statesWithPrefix) {
      for (const { key, value } of this.store.statesWithPrefix(PREFIX + month)) {
        days[key.slice(PREFIX.length)] = Number.parseInt(value, 10) || 0;
      }
    } else {
      for (const [day, value] of this.memory) {
        if (day.startsWith(month)) days[day] = Number.parseInt(value, 10) || 0;
      }
    }

    return {
      today: days[today] ?? 0,
      month: Object.values(days).reduce((sum, value) => sum + value, 0),
      dailyCap: this.settings.dailyCap,
      days,
    };
  }

  /**
   * Credits left on the account, or null when it cannot be told: no key, the
   * check switched off, or an answer in a shape this does not recognise. Never
   * throws — a balance check must not cost a run.
   *
   * @returns {Promise<number|null>}
   */
  async balance(fetchImpl = fetch) {
    if (!this.settings.apiKey || this.settings.lowBalance <= 0) return null;

    try {
      const response = await fetchImpl(this.settings.accountUrl, {
        headers: { 'X-API-KEY': this.settings.apiKey, Accept: 'application/json' },
        signal: AbortSignal.timeout(config.http.timeoutMs),
      });
      if (!response.ok) return null;

      const payload = await response.json();
      const credits = Number(payload?.balance ?? payload?.credits);
      if (!Number.isFinite(credits)) return null;

      this.store?.setState(BALANCE, credits);
      return credits;
    } catch (error) {
      logger.debug('Serper balance check failed', { error: error.message });
      return null;
    }
  }

  /**
   * lowBalanceAlert, asked at most once a day: the balance moves by a few
   * dozen credits a day, and the alert itself is once a day anyway. The last
   * balance read is kept for `--stats`. Needs a store to remember the day.
   *
   * @returns {Promise<{kind: string, message: string}|null>}
   */
  async dailyBalanceAlert(fetchImpl = fetch) {
    if (!this.store) return null;

    const day = this.#today();
    if (this.store.getState(BALANCE_DAY) === day) return null;
    this.store.setState(BALANCE_DAY, day);

    return this.lowBalanceAlert(fetchImpl);
  }

  /** The balance last read from Serper, or null. */
  lastBalance() {
    const value = this.store?.getState(BALANCE);
    return value === null || value === undefined ? null : Number(value);
  }

  /**
   * An alert when the balance is under SERPER_LOW_BALANCE, else null.
   *
   * @returns {Promise<{kind: string, message: string}|null>}
   */
  async lowBalanceAlert(fetchImpl = fetch) {
    const credits = await this.balance(fetchImpl);
    if (credits === null || credits >= this.settings.lowBalance) return null;

    return {
      kind: 'serper-low-balance',
      message:
        `Serper has ${credits} credits left (alert below ${this.settings.lowBalance}). ` +
        'Top up at serper.dev, or the hiring-posts search stops when they run out.',
    };
  }
}

export default SerperBudget;
