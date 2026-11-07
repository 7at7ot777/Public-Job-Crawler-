import config from './config.js';
import logger from './logger.js';

/*
 * Which forum topic each region's jobs are posted to, as the Android app's
 * Topics.kt does it.
 *
 * A group with Topics turned on is one chat with numbered threads, so a job
 * goes to TELEGRAM_CHAT_ID unless .env names a topic for its region:
 * TELEGRAM_TOPIC_EGYPT, TELEGRAM_TOPIC_GULF, TELEGRAM_TOPIC_WORLDWIDE,
 * TELEGRAM_TOPIC_FREELANCE.
 */

/** Thread 1 is a forum's General topic, which Telegram wants addressed with no thread id. */
const GENERAL = 1;

const LINK_RE = /^(?:https?:\/\/)?(?:t\.me|telegram\.me)\/(c\/)?([^/?#\s]+)(?:\/(\d+))?(?:\/\d+)?\/?(?:[?#].*)?$/i;
const PAIR_RE = /^(-?\d+|@\w+)(?::(\d+))?$/;

const topicOf = (value) => {
  const n = Number.parseInt(value ?? '', 10);
  return Number.isFinite(n) && n > GENERAL ? n : null;
};

/**
 * A destination as written in .env:
 *   https://t.me/c/1234567890/3     private group topic (Copy Link on the topic)
 *   https://t.me/c/1234567890/3/42  a message inside topic 3
 *   https://t.me/mygroup/3          public group, by username
 *   -1001234567890:3                chat id and topic
 *   3                               topic 3 of defaultChat
 *   -1001234567890                  a whole chat, no topic
 *
 * @returns {{chatId: string, threadId: number|null}|null}
 */
export function parseTopic(value, defaultChat) {
  const text = String(value ?? '').trim();
  if (text === '') return null;

  const link = text.match(LINK_RE);
  if (link) {
    const [, isPrivate, id, topic] = link;
    if (isPrivate && !/^\d+$/.test(id)) return null;
    // A private link drops the "-100" every supergroup id starts with.
    return { chatId: isPrivate ? `-100${id}` : `@${id}`, threadId: topicOf(topic) };
  }

  const pair = text.match(PAIR_RE);
  if (pair) {
    const [, chat, topic] = pair;
    // A short bare number is a topic of the default chat: real chat ids are long.
    if (!topic && chat.length <= 6 && /^\d+$/.test(chat)) {
      return defaultChat ? { chatId: defaultChat, threadId: topicOf(chat) } : null;
    }
    return { chatId: chat, threadId: topicOf(topic) };
  }

  return null;
}

/** raw if it reads as a destination, else the whole defaultChat. */
export const resolveTopic = (raw, defaultChat) =>
  parseTopic(raw, defaultChat) ?? { chatId: defaultChat, threadId: null };

/** Where a job of region is posted. An unreadable value is logged and falls back to the channel. */
export function destinationFor(region, settings = config.telegram) {
  const defaultChat = settings.chatId;
  const raw = settings.topics?.[region.name] ?? '';
  const parsed = parseTopic(raw, defaultChat);

  if (!parsed && String(raw).trim() !== '') {
    logger.warn(`Unreadable TELEGRAM_TOPIC_${region.name}; posting to the channel`);
  }

  return parsed ?? { chatId: defaultChat, threadId: null };
}
