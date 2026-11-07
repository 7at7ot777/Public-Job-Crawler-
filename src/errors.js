/**
 * Thrown when a remote endpoint rate-limits or blocks us and the run must be
 * abandoned for that source. Carries whatever was already parsed, because
 * partial results are independently valid and should still be delivered.
 */
export class BlockedError extends Error {
  /**
   * @param {boolean} transport  true when the request never got an answer —
   *        DNS, a reset connection, a timeout — rather than being refused by
   *        the far end. The distinction matters to a source that walks many
   *        separate URLs: being throttled means stop, while one unreachable
   *        page means skip that page.
   */
  constructor(message, partialItems = [], transport = false) {
    super(message);
    this.name = 'BlockedError';
    this.partialItems = partialItems;
    this.transport = transport;
  }
}

/** Telegram returned 429 with a `parameters.retry_after` hint. */
export class TelegramRateLimitError extends Error {
  constructor(message, retryAfterSeconds = 30) {
    super(message);
    this.name = 'TelegramRateLimitError';
    this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
  }
}

/** Telegram rejected the request in a way retrying cannot fix. */
export class TelegramPermanentError extends Error {
  constructor(message) {
    super(message);
    this.name = 'TelegramPermanentError';
  }
}
