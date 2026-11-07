import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import config from './config.js';

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };
const threshold = LEVELS[config.log.level] ?? LEVELS.info;

let stream = null;

/** Opened lazily so a --dry-run on a read-only checkout never creates files. */
function fileStream() {
  if (stream) return stream;

  // Same problem the database has: the app directory is not always writable.
  // Console output is the real log on a hosted platform anyway, so a failure
  // here is a downgrade, not an error.
  const candidates = [config.log.file, path.join(os.tmpdir(), 'linkedin-jobs-telegram', 'crawler.log')];

  for (const candidate of candidates) {
    try {
      fs.mkdirSync(path.dirname(candidate), { recursive: true });

      // createWriteStream reports failure asynchronously, via an 'error' event,
      // so a try/catch around it catches nothing. Check writability up front.
      fs.accessSync(path.dirname(candidate), fs.constants.W_OK);

      const candidateStream = fs.createWriteStream(candidate, { flags: 'a' });

      // Belt and braces: an unhandled 'error' event on a stream terminates the
      // process. Logging must never be able to do that.
      candidateStream.on('error', (error) => {
        console.warn(`File logging disabled: ${error.message}`);
        stream = { write() {} };
      });

      stream = candidateStream;

      if (candidate !== config.log.file) {
        console.warn(`File logging fell back to ${candidate} (${config.log.file} is not writable)`);
      }

      return stream;
    } catch {
      // Try the next location.
    }
  }

  console.warn('File logging disabled: no writable location. Console output only.');
  stream = { write() {} };

  return stream;
}

function write(level, message, context) {
  if (LEVELS[level] < threshold) return;

  const entry = {
    time: new Date().toISOString(),
    level,
    message,
    ...(context ? { context } : {}),
  };

  const line = `${entry.time} ${level.toUpperCase().padEnd(5)} ${message}${
    context ? ` ${JSON.stringify(context)}` : ''
  }`;

  if (level === 'error' || level === 'warn') {
    console.error(line);
  } else {
    console.log(line);
  }

  fileStream().write(`${JSON.stringify(entry)}\n`);
}

export const logger = {
  debug: (message, context) => write('debug', message, context),
  info: (message, context) => write('info', message, context),
  warn: (message, context) => write('warn', message, context),
  error: (message, context) => write('error', message, context),
};

export default logger;
