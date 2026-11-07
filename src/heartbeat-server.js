import fs from 'node:fs';
import http from 'node:http';
import { CommandBot, commandsEnabled } from './bot.js';

/**
 * The port a PaaS insists on, for the mode that runs nothing resident.
 *
 * CRAWL_MODE=sequential is a shell loop around short-lived node processes, so
 * nothing listens — and a host that routes HTTP (Render, Fly, Railway on a web
 * service) treats a container with no open port as a failed deploy and kills
 * it, however well the crawl itself is going. This binds $PORT with the
 * smallest process that can answer honestly: it owns no crawler state, it only
 * reads the heartbeat file the loop touches around every source.
 *
 * It also runs the Telegram command bot, since it is the one process that
 * stays up: the bot only queues requests, and the loop runs them.
 *
 * Run it as a background child of docker-crawl-loop.sh. With neither PORT nor
 * bot commands configured it exits at once.
 */
const port = Number.parseInt(process.env.PORT ?? '', 10);
const heartbeatFile = process.env.HEARTBEAT_FILE ?? '/tmp/crawler-heartbeat';
const intervalMinutes = Number.parseInt(process.env.CRAWL_INTERVAL_MINUTES ?? '60', 10) || 60;

// Two intervals plus a margin, matching docker-healthcheck.sh: one slow pass
// must not report unhealthy, a loop that has stopped touching the file must.
// Never under an hour: the heartbeat only moves between sources, and with a
// 15-minute interval one slow periodic source would otherwise read as dead.
const staleAfterSeconds = Math.max(intervalMinutes * 60 * 2 + 600, 3600);

/** @returns {{ok: boolean, ageSeconds: number|null}} */
function heartbeat() {
  try {
    const last = Number.parseInt(fs.readFileSync(heartbeatFile, 'utf8').trim(), 10);
    if (!Number.isFinite(last)) return { ok: false, ageSeconds: null };

    const ageSeconds = Math.round(Date.now() / 1000) - last;
    return { ok: ageSeconds <= staleAfterSeconds, ageSeconds };
  } catch {
    // No file yet is the first few seconds of a fresh container, not a fault.
    return { ok: false, ageSeconds: null };
  }
}

const bot = commandsEnabled() ? new CommandBot() : null;

if (!Number.isFinite(port) && !bot) {
  console.log('[heartbeat-server] No PORT set and no bot commands; not starting');
  process.exit(0);
}

bot?.start();

const server = http.createServer((req, res) => {
  const { ok, ageSeconds } = heartbeat();
  const path = (req.url ?? '/').split('?')[0];

  if (path === '/healthz' || path === '/health') {
    // 503 while the first pass has not touched the file yet: a platform that
    // waits for a healthy answer should wait, not route traffic to a loop that
    // has not started.
    res.writeHead(ok ? 200 : 503, { 'Content-Type': 'text/plain' });
    res.end(ok ? 'ok\n' : 'stale\n');
    return;
  }

  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(
    `${JSON.stringify(
      {
        mode: process.env.CRAWL_MODE ?? 'sequential',
        sources: (process.env.CRAWL_SOURCES ?? '').split(' ').filter(Boolean),
        intervalMinutes,
        heartbeatAgeSeconds: ageSeconds,
        staleAfterSeconds,
        healthy: ok,
      },
      null,
      2,
    )}\n`,
  );
});

if (Number.isFinite(port)) {
  server.listen(port, '0.0.0.0', () => {
    console.log(`[heartbeat-server] listening on ${port}`);
  });
}

server.on('error', (error) => {
  // A port this process cannot bind is worth saying loudly, but it must not
  // take the crawl loop down with it — crawling is the job, the port is rent.
  console.error(`[heartbeat-server] failed: ${error.message}`);
  process.exit(1);
});

const stop = () => {
  bot?.stop();
  server.close();
  process.exit(0);
};

process.on('SIGTERM', stop);
process.on('SIGINT', stop);
