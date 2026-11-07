import { randomUUID } from 'node:crypto';
import logger from './logger.js';

export const JOB_STATUS = {
  QUEUED: 'queued',
  RUNNING: 'running',
  DONE: 'done',
  FAILED: 'failed',
};

const DEFAULT_HISTORY = 50;
const DEFAULT_MAX_QUEUED = 10;

/**
 * A one-at-a-time job queue for crawls requested over HTTP.
 *
 * Crawls cannot overlap: they share the pacing budget of one rate-limited
 * endpoint, so two at once is the fastest route to being blocked. Refusing the
 * second request would push that problem onto the caller, who then has to poll
 * and retry — so a request becomes a job instead: accepted, queued, and run in
 * turn. The caller gets an id immediately and can ask how it went.
 *
 * Finished jobs stay in memory only: history is for "what happened to the run
 * I asked for", not an audit log. Delivered items are in SQLite, which is what
 * survives a restart.
 */
export class JobQueue {
  /**
   * @param {(options: object) => Promise<object[]>} runner  performs one crawl
   * @param {{history?: number, maxQueued?: number}} options
   */
  constructor(runner, { history = DEFAULT_HISTORY, maxQueued = DEFAULT_MAX_QUEUED } = {}) {
    this.runner = runner;
    this.history = history;
    this.maxQueued = maxQueued;

    this.jobs = new Map();
    this.waiting = [];
    this.current = null;
    this.draining = false;
  }

  /**
   * Accept a crawl request.
   *
   * @param {object} params  run options, including `hours`
   * @param {{trigger?: string, from?: string}} meta  who asked, for the log
   * @returns {{job: object, done: Promise<object>}} `done` settles with the
   *          finished job — for callers that asked to wait — and never rejects,
   *          because the failure is recorded on the job itself.
   * @throws {Error} when the queue is full, which means something is calling
   *         far faster than a crawl can run.
   */
  submit(params = {}, { trigger = 'unknown', from = null } = {}) {
    if (this.waiting.length >= this.maxQueued) {
      throw new Error(
        `Too many queued crawls (${this.waiting.length}). Wait for the queue to drain.`,
      );
    }

    const job = {
      id: randomUUID(),
      status: JOB_STATUS.QUEUED,
      params,
      trigger,
      queuePosition: this.waiting.length + (this.current ? 1 : 0),
      createdAt: new Date().toISOString(),
      startedAt: null,
      finishedAt: null,
      durationMs: null,
      results: null,
      error: null,
    };

    this.#remember(job);

    const done = new Promise((resolve) => {
      this.waiting.push({ job, resolve });
    });

    logger.info('Crawl requested', {
      jobId: job.id,
      trigger,
      ...(from ? { from } : {}),
      position: job.queuePosition,
      ...params,
    });

    // Deliberately not awaited: submit() returns as soon as the job is on the
    // queue, which is the whole point of queueing it.
    this.#drain();

    return { job, done };
  }

  /** @returns {object|null} */
  find(id) {
    return this.jobs.get(id) ?? null;
  }

  /** Newest first. */
  list(limit = 20) {
    return [...this.jobs.values()].reverse().slice(0, limit);
  }

  snapshot() {
    return {
      running: this.current?.id ?? null,
      queued: this.waiting.length,
      recent: this.list(5).map((job) => summarize(job)),
    };
  }

  /**
   * Run queued jobs in turn until none are left.
   *
   * Guarded by `draining` rather than by a lock: a second call while a job is
   * in flight returns immediately, and the loop already running picks up the
   * job it just queued.
   */
  async #drain() {
    if (this.draining) return;
    this.draining = true;

    try {
      while (this.waiting.length > 0) {
        const { job, resolve } = this.waiting.shift();

        this.current = job;
        job.status = JOB_STATUS.RUNNING;
        job.queuePosition = 0;
        job.startedAt = new Date().toISOString();

        const startedAt = Date.now();

        try {
          job.results = summarizeResults(await this.runner(job.params));
          job.status = JOB_STATUS.DONE;

          logger.info('Crawl job finished', { jobId: job.id, results: job.results });
        } catch (error) {
          job.status = JOB_STATUS.FAILED;
          job.error = error.message;

          logger.error('Crawl job failed', { jobId: job.id, error: error.message });
        } finally {
          job.finishedAt = new Date().toISOString();
          job.durationMs = Date.now() - startedAt;
          this.current = null;

          // Positions shift by one now this job is out of the queue.
          this.waiting.forEach((entry, index) => {
            entry.job.queuePosition = index + 1;
          });

          resolve(job);
        }
      }
    } finally {
      this.draining = false;
    }
  }

  /** Keep the most recent jobs only; this is a status board, not a log. */
  #remember(job) {
    this.jobs.set(job.id, job);

    while (this.jobs.size > this.history) {
      const oldest = this.jobs.keys().next().value;
      this.jobs.delete(oldest);
    }
  }
}

/** The per-source stats, without the item objects a caller cannot use. */
function summarizeResults(results) {
  return (results ?? []).map(({ items, ...rest }) => rest);
}

/** The short form used in listings, where full params are noise. */
function summarize(job) {
  return {
    id: job.id,
    status: job.status,
    hours: job.params?.hours ?? null,
    createdAt: job.createdAt,
    finishedAt: job.finishedAt,
    durationMs: job.durationMs,
    error: job.error,
  };
}

export default JobQueue;
