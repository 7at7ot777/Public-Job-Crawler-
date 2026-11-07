import http from 'node:http';
import logger from './logger.js';
import { JOB_STATUS } from './jobs.js';
import { decisionFilters, withStore } from './decisions.js';

/**
 * Tiny HTTP server: a status page, and an endpoint that starts a crawl on
 * demand.
 *
 * The crawler is a background job and opens no port of its own, which makes a
 * PaaS health check fail and the router answer 502. Binding $PORT here keeps
 * the platform happy, doubles as a status page, and gives the schedule a
 * manual override — "crawl the last 24 hours, now".
 *
 * @param {object} state  live run state, rendered as the status page
 * @param {{queue?: import('./jobs.js').JobQueue, port?: number}} options
 *        The queue is shared with the scheduler, so a requested crawl and a
 *        scheduled one take turns instead of overlapping. Without it the job
 *        endpoints are not mounted.
 */
export function startHealthServer(state, { queue = null, port: portOverride = null } = {}) {
  const port = portOverride ?? Number.parseInt(process.env.PORT ?? '', 10);

  if (!Number.isFinite(port)) {
    logger.info('No PORT set; health server not started');
    return null;
  }

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);

    if (url.pathname === '/healthz' || url.pathname === '/health') {
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end('ok');
      return;
    }

    if (url.pathname === '/run') {
      handleRun(req, res, url, queue);
      return;
    }

    if (url.pathname === '/jobs') {
      handleJobList(req, res, url, queue);
      return;
    }

    if (url.pathname.startsWith('/jobs/')) {
      handleJob(req, res, url, queue);
      return;
    }

    if (url.pathname === '/decisions' || url.pathname === '/decisions/summary') {
      handleDecisions(req, res, url);
      return;
    }

    json(res, 200, status(state, queue));
  });

  server.listen(port, '0.0.0.0', () => {
    logger.info('Health server listening', { port, jobs: queue ? '/run' : 'disabled' });
  });

  server.on('error', (error) => {
    logger.error('Health server failed', { error: error.message });
  });

  return server;
}

/**
 * POST /run — queue a crawl of the last `hours` hours (24 by default).
 *
 * Answers 202 with a job id, because a crawl runs for minutes: long enough for
 * a proxy or a client to time out and retry, which would queue the work twice.
 * The id is the handle — GET /jobs/<id> — and `wait=true` holds the connection
 * until that job finishes instead.
 */
function handleRun(req, res, url, queue) {
  if (!queue) {
    json(res, 404, { error: 'This process does not run crawls.' });
    return;
  }

  // GET is reserved for the status page: a crawl sends messages and must not
  // be startable by a link, a crawler or a browser preload.
  if (req.method !== 'POST') {
    json(res, 405, { error: 'Use POST /run.' });
    return;
  }

  const denied = authorize(req, url);
  if (denied) {
    json(res, 401, { error: denied });
    return;
  }

  const hours = Number.parseFloat(url.searchParams.get('hours') ?? '24');

  if (!Number.isFinite(hours) || hours <= 0) {
    json(res, 400, { error: 'hours must be a positive number.' });
    return;
  }

  const options = {
    hours,
    sources: csv(url.searchParams.get('sources')),
    keywords: csv(url.searchParams.get('keywords')),
    locations: csv(url.searchParams.get('locations')),
    limit: integer(url.searchParams.get('limit')),
    pages: integer(url.searchParams.get('pages')),
    dryRun: flag(url.searchParams.get('dryRun')),
    noSend: flag(url.searchParams.get('noSend')),
  };

  const wait = flag(url.searchParams.get('wait'));

  let job;
  let done;

  try {
    ({ job, done } = queue.submit(options, {
      trigger: 'http',
      from: req.headers['x-forwarded-for'] ?? req.socket.remoteAddress ?? null,
    }));
  } catch (error) {
    // The queue is full: something is asking far faster than a crawl can run.
    json(res, 429, { error: error.message });
    return;
  }

  if (!wait) {
    json(res, 202, {
      accepted: true,
      jobId: job.id,
      status: job.status,
      queuePosition: job.queuePosition,
      hours,
      statusUrl: `/jobs/${job.id}`,
    });
    return;
  }

  // `done` records a failure on the job rather than rejecting, so a waiting
  // caller sees status: "failed" with the reason, like any other reader.
  done.then((finished) => json(res, finished.status === JOB_STATUS.FAILED ? 500 : 200, finished));
}

/**
 * GET /decisions — the decision log, newest first.
 *   ?decision=rejected|duplicate|accepted|all &reason= &source= &since=<hours> &limit=
 * GET /decisions/summary — counts by reason and source, and the titles
 *   rejected most often for each reason. Same filters.
 *
 * Behind the same token as POST /run: rows carry job titles and contacts.
 */
function handleDecisions(req, res, url) {
  const denied = authorize(req, url);
  if (denied) {
    json(res, 401, { error: denied });
    return;
  }

  const filters = decisionFilters(Object.fromEntries(url.searchParams));

  try {
    const body = withStore((store) =>
      url.pathname.endsWith('/summary')
        ? store.decisionSummary(filters)
        : { decisions: store.queryDecisions(filters) },
    );
    json(res, 200, { filters, ...body });
  } catch (error) {
    json(res, 500, { error: error.message });
  }
}

/** GET /jobs — the recent jobs, newest first. */
function handleJobList(req, res, url, queue) {
  if (!queue) {
    json(res, 404, { error: 'This process does not run crawls.' });
    return;
  }

  const limit = integer(url.searchParams.get('limit')) ?? 20;

  json(res, 200, {
    running: queue.current?.id ?? null,
    queued: queue.waiting.length,
    jobs: queue.list(limit),
  });
}

/** GET /jobs/<id> — one job: where it is, and what it found. */
function handleJob(req, res, url, queue) {
  if (!queue) {
    json(res, 404, { error: 'This process does not run crawls.' });
    return;
  }

  const job = queue.find(url.pathname.slice('/jobs/'.length));

  if (!job) {
    // Also what an id from before a restart looks like: job history is in
    // memory, while the items a crawl delivered are in SQLite.
    json(res, 404, { error: 'No such job. It may have finished before a restart.' });
    return;
  }

  json(res, 200, job);
}

/**
 * A crawl posts to a Telegram channel, so the endpoint is not open.
 *
 * With API_TOKEN set, the token is required. Without one, only loopback may
 * trigger a crawl — so an exposed PORT is not an open trigger by default.
 *
 * @returns {string|null} the reason it was refused, or null when allowed.
 */
function authorize(req, url) {
  const expected = (process.env.API_TOKEN ?? '').trim();

  if (expected !== '') {
    const header = req.headers.authorization ?? '';
    const bearer = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
    const provided = bearer || (url.searchParams.get('token') ?? '').trim();

    return provided === expected ? null : 'Invalid or missing API token.';
  }

  const address = req.socket.remoteAddress ?? '';
  const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(address);

  return local
    ? null
    : 'Remote calls require API_TOKEN to be set. Without it only localhost may start a crawl.';
}

function status(state, queue) {
  return {
    status: 'ok',
    service: 'linkedin-jobs-telegram',
    startedAt: state.startedAt,
    uptimeSeconds: Math.floor(process.uptime()),
    schedule: state.schedule,
    // Per-source, for the sources that do not run on the schedule above.
    schedules: state.schedules?.() ?? null,
    timezone: state.timezone,
    running: state.running,
    lastRunAt: state.lastRunAt,
    lastRunError: state.lastRunError,
    lastRun: state.lastRun,
    jobs: queue ? queue.snapshot() : null,
    totals: state.totals(),
    serper: state.serper?.() ?? null,
    // A PaaS without a persistent volume resets the database on every deploy,
    // which makes the crawler re-send jobs it has already delivered.
    databasePath: state.databasePath,
    databaseLooksFresh: state.databaseLooksFresh(),
  };
}

function json(res, statusCode, body) {
  res.writeHead(statusCode, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body, null, 2));
}

function csv(value) {
  if (!value) return undefined;

  const entries = value
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean);

  return entries.length > 0 ? entries : undefined;
}

function integer(value) {
  const parsed = Number.parseInt(value ?? '', 10);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function flag(value) {
  if (value === null || value === undefined) return false;
  return value === '' || ['1', 'true', 'yes', 'on'].includes(value.trim().toLowerCase());
}

export default startHealthServer;
