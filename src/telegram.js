import config from './config.js';
import logger from './logger.js';
import { TelegramPermanentError, TelegramRateLimitError } from './errors.js';

/** Minimal Telegram Bot API client — just enough to post to one chat. */
export class TelegramClient {
  constructor(settings = config.telegram) {
    this.settings = settings;
  }

  isEnabled() {
    return Boolean(this.settings.enabled && this.settings.botToken && this.settings.chatId);
  }

  /**
   * @returns {Promise<true>} on a confirmed `ok: true` response.
   * @throws {TelegramRateLimitError} caller should retry after N seconds
   * @throws {TelegramPermanentError} malformed request; retrying will not help
   * @throws {Error} transient failure; safe to retry
   */
  /**
   * @param {string|{chatId: string, threadId?: number|null}|null} to  a chat id, or a
   *        destination with a forum topic (src/topics.js); null is the channel.
   */
  async sendMessage(html, to = null) {
    const chatId = typeof to === 'object' && to !== null ? to.chatId : to;
    const threadId = typeof to === 'object' && to !== null ? (to.threadId ?? null) : null;

    const url = `${this.settings.apiBase.replace(/\/+$/, '')}/bot${this.settings.botToken}/sendMessage`;

    const response = await fetch(url, {
      method: 'POST',
      signal: AbortSignal.timeout(this.settings.timeoutMs),
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        chat_id: chatId ?? this.settings.chatId,
        text: this.#truncate(html),
        parse_mode: 'HTML',
        disable_web_page_preview: true,
        ...(threadId ? { message_thread_id: threadId } : {}),
      }),
    });

    let body = {};
    try {
      body = await response.json();
    } catch {
      // Non-JSON response; handled by the status checks below.
    }

    if (response.status === 429) {
      throw new TelegramRateLimitError(
        body.description || 'Too Many Requests',
        Number(body?.parameters?.retry_after) || 30,
      );
    }

    if (response.status === 400) {
      // Bad markup, unknown chat, or the bot was removed from the channel.
      throw new TelegramPermanentError(body.description || `HTTP 400: ${response.statusText}`);
    }

    if (!response.ok || body.ok !== true) {
      throw new Error(
        `Telegram sendMessage failed (HTTP ${response.status}): ${body.description || response.statusText}`,
      );
    }

    return true;
  }

  /**
   * Hard-cap the body at Telegram's limit. The cut is made before any trailing
   * partial tag or entity so the message stays valid HTML.
   */
  #truncate(html) {
    const max = this.settings.maxMessageLength;
    if (html.length <= max) return html;

    logger.warn('Telegram message truncated', { length: html.length, max });

    let cut = html.slice(0, max - 1);

    const lastOpen = cut.lastIndexOf('<');
    const lastClose = cut.lastIndexOf('>');
    if (lastOpen > lastClose) cut = cut.slice(0, lastOpen);

    const lastAmp = cut.lastIndexOf('&');
    const lastSemi = cut.lastIndexOf(';');
    if (lastAmp > lastSemi) cut = cut.slice(0, lastAmp);

    return `${cut}…`;
  }
}

export default TelegramClient;
