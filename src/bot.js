import fs from 'node:fs';
import path from 'node:path';
import config from './config.js';

/**
 * Telegram commands that ask the crawl loop to run a source now, instead of
 * waiting for its lane to come round.
 *
 * The bot never crawls. It drops a request file and the loop picks it up
 * between sources or while it sleeps, so a requested crawl runs in its own
 * short-lived process, one at a time, like every other crawl. Running it here
 * would put a second crawl's peak beside the scheduled one, and this host is
 * sized for one crawl at a time.
 *
 * Long polling rather than a webhook: it needs no public URL and no secret,
 * and it lives in the heartbeat server, a process that is resident anyway.
 *
 * Control directory layout, shared with docker-crawl-loop.sh:
 *   queue/<source>   one requesting chat id per line
 *   hours/<source>   optional: crawl the last N hours instead of the
 *                    configured window (LinkedIn Jobs only; the loop passes
 *                    it on as --hours)
 *   current          the source the loop is running now
 *   done/<file>      "<exit status> <seconds>\n<source>\n<chat ids…>"
 */

const ALIASES = {
  posts: 'linkedin_posts',
  jobs: 'linkedin_jobs',
};

/** The one source a manual run can be given a time window for. */
export const HOURS_SOURCE = 'linkedin_jobs';
export const MAX_HOURS = 168;
/** The buttons /jobs offers; any other whole number up to MAX_HOURS can be typed. */
export const QUICK_HOURS = [1, 6, 10, 24, 48, 168];
/** How long a "How many hours back?" prompt waits for a typed number. */
const PROMPT_TTL_MS = 5 * 60_000;

const hoursLabel = (hours) => (hours === 168 ? '1 week' : `${hours}h`);

/** "10" → { hours: 10 }; anything but a whole number in 1..MAX_HOURS → { error }. */
export function parseHours(text) {
  const value = String(text ?? '').trim().replace(/h$/i, '');
  if (!/^\d+$/.test(value)) return { error: `Hours must be a whole number from 1 to ${MAX_HOURS}.` };

  const hours = Number(value);
  if (hours < 1 || hours > MAX_HOURS) return { error: `Hours must be from 1 to ${MAX_HOURS}.` };

  return { hours };
}

/** Keys `/run` accepts: every configured source, enabled or not. */
export const knownSources = () => Object.keys(config.sources);

/**
 * "/run@MyBot linkedin_posts" → { command: 'run', args: ['linkedin_posts'] }.
 * Null for anything that is not a command.
 */
export function parseCommand(text) {
  const match = /^\/([a-z_]+)(?:@\S+)?(?:\s+(.*))?$/i.exec(String(text ?? '').trim());
  if (!match) return null;

  return {
    command: match[1].toLowerCase(),
    args: (match[2] ?? '').split(/\s+/).filter(Boolean),
  };
}

/** The sources a command asks for, or an error naming what was not understood. */
export function resolveSources({ command, args }, known = knownSources()) {
  // A number among the arguments is a time window, not a source.
  const isNumber = (arg) => /^\d+h?$/i.test(arg);
  const numbers = args.filter(isNumber);
  const names = args.filter((arg) => !isNumber(arg));

  const requested = command === 'run' ? names.map((arg) => arg.toLowerCase()) : [ALIASES[command]];
  const sources = requested.map((name) => ALIASES[name] ?? name);

  if (sources.length === 0) return { error: 'Which source? Try /sources.' };

  const unknown = sources.filter((source) => !known.includes(source));
  if (unknown.length) return { error: `Unknown source: ${unknown.join(', ')}. Try /sources.` };

  const result = { sources: [...new Set(sources)] };
  if (numbers.length === 0) return result;

  if (numbers.length > 1) return { error: 'Give one number of hours.' };
  if (!result.sources.includes(HOURS_SOURCE)) {
    return { error: 'Hours only apply to LinkedIn Jobs: /jobs <hours>.' };
  }

  const { hours, error } = parseHours(numbers[0]);
  return error ? { error } : { ...result, hours };
}

/**
 * "/rejected title 48" → filters for the decision log: a number is the window
 * in hours, a decision word (duplicate, accepted, all) picks the decision,
 * and a source key or anything else is the source or the reason.
 */
export function decisionArgs(args, known = knownSources()) {
  const filters = { decision: 'rejected', since: '24' };

  for (const raw of args) {
    const arg = raw.toLowerCase();

    if (/^\d+h?$/.test(arg)) filters.since = arg.replace(/h$/, '');
    else if (['rejected', 'duplicate', 'accepted', 'all'].includes(arg)) filters.decision = arg;
    else if (known.includes(arg)) filters.source = arg;
    else filters.reason = arg;
  }

  return filters;
}

/**
 * Mutes and verdicts, the Android app's "Is this a fit?": a muted company or
 * title word drops matching jobs from the next run on, and two 👎 on a
 * company or title word lower its later scores. Imported lazily, like the
 * decision log, so the bot's process stays small.
 */
export async function muteCommand(command, args) {
  const { withStore } = await import('./decisions.js');
  const { muteKey } = await import('./mutes.js');
  const text = args.join(' ').trim();

  return withStore((store) => {
    if (command === 'mutes') {
      const all = store.mutes();
      if (all.length === 0) return 'Nothing muted.';
      return `Muted:\n${all.map((m) => `${m.kind === 'company' ? '🏢' : '🔤'} ${m.label}`).join('\n')}`;
    }

    if (command === 'up' || command === 'down') {
      const id = Number.parseInt(args[0] ?? '', 10);
      if (!Number.isFinite(id)) return `Which job? /${command} <id> (the 🆔 on its message).`;
      const row = store.findById(id);
      if (!row) return `No job ${id}.`;
      store.setFeedback(id, command);
      return `${command === 'up' ? '👍' : '👎'} ${row.title}${row.company ? ` @ ${row.company}` : ''}`;
    }

    if (text === '') return `What to ${command.replace('_', ' ')}? /${command} <name>`;

    if (command === 'mute_company') {
      store.addMute('company', muteKey(text), text);
      return `Muted company: ${text}. Its jobs are dropped from the next run on.`;
    }

    if (command === 'mute_word') {
      store.addMute('word', text.toLowerCase(), text);
      return `Muted title word: ${text}.`;
    }

    // unmute: a company or a word, whichever it was.
    const removed = store.removeMute('company', muteKey(text)) || store.removeMute('word', text.toLowerCase());
    return removed ? `Unmuted: ${text}.` : `${text} was not muted. /mutes lists them.`;
  });
}

/** The decision log as one message. Imported lazily: the bot's process stays small. */
async function decisionReport(args) {
  const { decisionFilters, formatReport, withStore } = await import('./decisions.js');
  return withStore((store) => formatReport(store, decisionFilters(decisionArgs(args))));
}

/**
 * Queues `source` for `chatId`. With `hours`, also asks for that window; two
 * requests waiting together keep the wider one, which covers both.
 *
 * @returns {boolean} whether the source was already queued
 */
export function queueRequest(controlDir, source, chatId, hours = null) {
  const queueDir = path.join(controlDir, 'queue');
  fs.mkdirSync(queueDir, { recursive: true });

  const file = path.join(queueDir, source);
  const already = fs.existsSync(file);

  if (hours) {
    const hoursDir = path.join(controlDir, 'hours');
    fs.mkdirSync(hoursDir, { recursive: true });

    const hoursFile = path.join(hoursDir, source);
    let current = 0;
    try {
      current = Number.parseInt(fs.readFileSync(hoursFile, 'utf8'), 10) || 0;
    } catch {
      // None asked for yet.
    }
    if (hours > current) fs.writeFileSync(hoursFile, `${hours}\n`);
  }

  // Last, so the loop never claims the queue entry before its hours are written.
  fs.appendFileSync(file, `${chatId}\n`);

  return already;
}

/** The window a queued source will be crawled with, or null for the configured one. */
export function queuedHours(controlDir, source) {
  try {
    return Number.parseInt(fs.readFileSync(path.join(controlDir, 'hours', source), 'utf8'), 10) || null;
  } catch {
    return null;
  }
}

export function readState(controlDir) {
  const read = (file) => {
    try {
      return fs.readFileSync(path.join(controlDir, file), 'utf8').trim();
    } catch {
      return '';
    }
  };

  let queued = [];
  try {
    queued = fs.readdirSync(path.join(controlDir, 'queue'));
  } catch {
    // Nothing queued yet.
  }

  return { current: read('current'), queued };
}

/** Finished requests, removed as they are read so each is reported once. */
export function takeResults(controlDir) {
  const doneDir = path.join(controlDir, 'done');
  let files = [];
  try {
    files = fs.readdirSync(doneDir).filter((file) => !file.startsWith('.'));
  } catch {
    return [];
  }

  const results = [];
  for (const file of files) {
    const full = path.join(doneDir, file);
    try {
      const [summary = '', source = '', ...chats] = fs.readFileSync(full, 'utf8').split('\n');
      fs.rmSync(full, { force: true });

      const [status, seconds, hours] = summary.trim().split(/\s+/).map(Number);
      results.push({
        source: source.trim(),
        status,
        seconds,
        ...(hours ? { hours } : {}),
        chatIds: [...new Set(chats.map((chat) => chat.trim()).filter(Boolean))],
      });
    } catch {
      // Half-written by the loop; the next tick reads it whole.
    }
  }

  return results;
}

const HELP = [
  'Run a crawl now instead of waiting for its schedule:',
  '/posts — LinkedIn hiring posts',
  `/jobs [hours] — LinkedIn jobs; pick how far back (1–${MAX_HOURS}h)`,
  '/run <source> [source…] — any source',
  '/sources — list source keys',
  '/status — what is running and queued',
  '/rejected [reason|source] [hours] — why items were dropped (also: duplicate, accepted, all)',
  '/mute_company <name>, /mute_word <word> — never see these again; /unmute <name>, /mutes',
  '/up <id>, /down <id> — is this a fit? Two 👎 on a company or title word lower its scores',
].join('\n');

export class CommandBot {
  constructor({
    settings = config.telegram,
    allowedIds = config.telegram.commandUserIds,
    controlDir = config.telegram.controlDir,
    log = console,
    decisions = decisionReport,
  } = {}) {
    this.settings = settings;
    this.allowedIds = allowedIds.map(String);
    this.controlDir = controlDir;
    this.log = log;
    this.decisions = decisions;
    this.offset = 0;
    this.stopped = false;
    // chat id → when its "How many hours back?" prompt stops taking a number.
    this.pendingHours = new Map();
    this.now = () => Date.now();
  }

  async call(method, body, timeoutMs = this.settings.timeoutMs) {
    const url = `${this.settings.apiBase.replace(/\/+$/, '')}/bot${this.settings.botToken}/${method}`;
    const response = await fetch(url, {
      method: 'POST',
      signal: AbortSignal.timeout(timeoutMs),
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });

    const json = await response.json().catch(() => ({}));
    if (!response.ok || json.ok !== true) {
      const error = new Error(`${method}: ${json.description || `HTTP ${response.status}`}`);
      error.status = response.status;
      throw error;
    }

    return json.result;
  }

  /** `reply` is text, or { text, markup } for a message with buttons. */
  reply(chatId, reply) {
    const { text, markup } = typeof reply === 'string' ? { text: reply } : reply;
    const body = { chat_id: chatId, text, ...(markup ? { reply_markup: markup } : {}) };

    return this.call('sendMessage', body).catch((error) => {
      this.log.error(`[bot] reply failed: ${error.message}`);
    });
  }

  isAllowed(user) {
    return this.allowedIds.includes(String(user?.id ?? ''));
  }

  /** The /jobs prompt: quick buttons, or type any number while it is open. */
  askHours(chatId) {
    this.pendingHours.set(String(chatId), this.now() + PROMPT_TTL_MS);

    const buttons = QUICK_HOURS.map((hours) => ({
      text: hoursLabel(hours),
      callback_data: `jobs:${hours}`,
    }));

    return {
      text: `LinkedIn jobs: how many hours back? Tap one, or type a number (1–${MAX_HOURS}).`,
      markup: {
        inline_keyboard: [
          buttons.slice(0, 3),
          buttons.slice(3),
          [{ text: 'Default window', callback_data: 'jobs:default' }],
        ],
      },
    };
  }

  /** Whether `chatId` has an open prompt; an expired one is dropped. */
  takePending(chatId) {
    const key = String(chatId);
    const until = this.pendingHours.get(key);
    this.pendingHours.delete(key);

    return Boolean(until && until > this.now());
  }

  /** Queues the sources and says so; `hours` only ever applies to HOURS_SOURCE. */
  queue(sources, chatId, hours = null) {
    const { current } = readState(this.controlDir);
    const lines = sources.map((source) => {
      const forSource = source === HOURS_SOURCE ? hours : null;
      const already = queueRequest(this.controlDir, source, chatId, forSource);
      const window = forSource ? ` (last ${hoursLabel(queuedHours(this.controlDir, source))})` : '';
      return already ? `${source}: already queued${window}` : `${source}: queued${window}`;
    });

    lines.push(
      current
        ? `Starts after ${current} finishes. I'll reply when done.`
        : "Starts within ~15s. I'll reply when done.",
    );
    return lines.join('\n');
  }

  /**
   * @returns {string|{text: string, markup: object}|Promise<string>|null} the reply, or null
   *   for a message that is neither a command nor an answer to a prompt
   */
  handle(message) {
    const parsed = parseCommand(message.text);

    if (!parsed) {
      // A typed number answering an open /jobs prompt.
      if (!/^\s*\d+\s*h?\s*$/i.test(String(message.text ?? ''))) return null;
      if (!this.isAllowed(message.from)) return null;
      if (!this.takePending(message.chat.id)) return null;

      const { hours, error } = parseHours(message.text);
      if (error) {
        this.pendingHours.set(String(message.chat.id), this.now() + PROMPT_TTL_MS);
        return `${error} Try again, or tap a button.`;
      }

      return this.queue([HOURS_SOURCE], message.chat.id, hours);
    }

    const userId = String(message.from?.id ?? '');
    const allowed = this.allowedIds.includes(userId);

    // Who asked for what: the start of every requested crawl.
    this.log.log(
      `[bot] /${parsed.command}${parsed.args.length ? ` ${parsed.args.join(' ')}` : ''} ` +
        `from user ${userId} in chat ${message.chat?.id}${allowed ? '' : ' (not allowed)'}`,
    );

    if (!allowed) {
      // Anyone can message a bot. Saying their id is what lets the owner fill
      // in TELEGRAM_COMMAND_USER_IDS without digging for it; it gives a
      // stranger nothing.
      return `Not allowed. Your user id is ${userId}; add it to TELEGRAM_COMMAND_USER_IDS to use commands.`;
    }

    const { command } = parsed;

    if (command === 'start' || command === 'help') return HELP;
    if (command === 'sources') return `Sources:\n${knownSources().join('\n')}`;

    if (command === 'status') {
      const { current, queued } = readState(this.controlDir);
      return [
        `Running: ${current || 'nothing'}`,
        `Queued: ${queued.length ? queued.join(', ') : 'nothing'}`,
      ].join('\n');
    }

    if (command === 'rejected') {
      // A promise, unlike the other replies: it reads the database.
      return this.decisions(parsed.args)
        .then((text) => (text.length > 4000 ? `${text.slice(0, 3990)}\n…` : text))
        .catch((error) => `Could not read the decision log: ${error.message}`);
    }

    if (['mute_company', 'mute_word', 'unmute', 'mutes', 'up', 'down'].includes(command)) {
      return muteCommand(command, parsed.args).catch((error) => `Could not update mutes: ${error.message}`);
    }

    if (command !== 'run' && !ALIASES[command]) return `Unknown command /${command}.\n\n${HELP}`;

    const { sources, hours, error } = resolveSources(parsed);
    if (error) return error;

    // LinkedIn Jobs alone with no window: ask for one instead of guessing.
    if (!hours && sources.length === 1 && sources[0] === HOURS_SOURCE) {
      return this.askHours(message.chat.id);
    }

    return this.queue(sources, message.chat.id, hours);
  }

  /**
   * A tap on one of the /jobs buttons.
   *
   * @returns {string} the text that replaces the prompt
   */
  handleCallback(query) {
    if (!this.isAllowed(query.from)) {
      return `Not allowed. Your user id is ${query.from?.id}; add it to TELEGRAM_COMMAND_USER_IDS to use commands.`;
    }

    const match = /^jobs:(default|\d+)$/.exec(String(query.data ?? ''));
    if (!match) return 'That button has expired. Send /jobs again.';

    const chatId = query.message?.chat?.id;
    this.pendingHours.delete(String(chatId));

    if (match[1] === 'default') return this.queue([HOURS_SOURCE], chatId);

    const { hours, error } = parseHours(match[1]);
    return error ?? this.queue([HOURS_SOURCE], chatId, hours);
  }

  async answerCallback(query) {
    const text = this.handleCallback(query);

    await this.call('answerCallbackQuery', { callback_query_id: query.id }).catch((error) => {
      this.log.error(`[bot] answerCallbackQuery failed: ${error.message}`);
    });

    const chatId = query.message?.chat?.id;
    if (chatId === undefined) return;

    // Replace the prompt, so its buttons can't be tapped twice.
    await this.call('editMessageText', {
      chat_id: chatId,
      message_id: query.message.message_id,
      text,
    }).catch(() => this.reply(chatId, text));
  }

  async reportResults() {
    for (const { source, status, seconds, hours, chatIds } of takeResults(this.controlDir)) {
      const name = hours ? `${source} (last ${hoursLabel(hours)})` : source;
      const text =
        status === 0
          ? `✅ ${name} finished in ${seconds}s. New items are in the channel.`
          : `❌ ${name} failed (exit ${status}) after ${seconds}s. Check the logs.`;

      for (const chatId of chatIds) await this.reply(chatId, text);
    }
  }

  async poll() {
    // A webhook left over from anything else makes getUpdates answer 409.
    await this.call('deleteWebhook', {}).catch((error) => {
      this.log.error(`[bot] deleteWebhook failed: ${error.message}`);
    });

    while (!this.stopped) {
      try {
        const updates = await this.call(
          'getUpdates',
          { offset: this.offset, timeout: 50, allowed_updates: ['message', 'callback_query'] },
          60_000,
        );

        for (const update of updates) {
          this.offset = update.update_id + 1;
          if (update.callback_query) {
            await this.answerCallback(update.callback_query);
            continue;
          }
          if (!update.message?.text) continue;

          const text = await this.handle(update.message);
          if (text) await this.reply(update.message.chat.id, text);
        }
      } catch (error) {
        if (this.stopped) return;

        // 409 is a second poller on the same token, usually a local run. Back
        // off long enough not to fight it over every update.
        const wait = error.status === 409 ? 60_000 : 10_000;
        this.log.error(`[bot] getUpdates failed: ${error.message}; retrying in ${wait / 1000}s`);
        await new Promise((resolve) => setTimeout(resolve, wait));
      }
    }
  }

  start() {
    this.log.log(`[bot] commands on; ${this.allowedIds.length} allowed user(s)`);
    this.timer = setInterval(() => this.reportResults(), 5_000);
    this.poll();
  }

  stop() {
    this.stopped = true;
    clearInterval(this.timer);
  }
}

export const commandsEnabled = () =>
  Boolean(config.telegram.botToken && config.telegram.commandsEnabled);
