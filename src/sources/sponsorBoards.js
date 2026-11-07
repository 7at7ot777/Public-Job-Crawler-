import logger from '../logger.js';
import BaseSource from './baseSource.js';
import { parseBoard, parseBoardResponse, PLATFORMS } from './atsParser.js';
import { isOlderThan, judgeFeedJob, newCounts, summarize, tally } from './abroad.js';

export const KEY = 'sponsor_boards';

/** What the card says about a company that sponsors without the posting saying so. */
export const KNOWN_SPONSOR =
  'Known visa sponsor: this company sponsors work visas or relocates engineers; the posting may not say so.';

/**
 * The job boards of companies in Europe and the UK known to sponsor work
 * visas or relocate engineers, ported from the Android app's SponsorBoards.kt.
 * Their Greenhouse, Lever and Ashby boards are public; the same readers as the
 * company boards (atsParser).
 *
 * A posting from one of them often does not repeat "visa sponsorship", so it
 * is marked as from a known sponsor, and abroadFit still requires it to be in
 * Europe, the UK, Canada or Australia (or remote and open to Egypt), in
 * English, at the CV's stack and level.
 */
export class SponsorBoardsSource extends BaseSource {
  constructor(client = null) {
    super(KEY, { label: 'Sponsor boards', headers: { Accept: 'application/json' }, client });
  }

  async fetch() {
    const checked = this.checked();
    const counts = newCounts();
    const boards = (this.settings.boards ?? []).map(parseBoard).filter(Boolean);

    try {
      await this.walk(boards, `${KEY}.rotation`, async (board) => {
        const body = await this.get(PLATFORMS[board.platform].url(board.slug), {}, counts.kept);
        if (body == null) return true;

        const jobs = parseBoardResponse(body, board);
        if (jobs == null) {
          logger.warn('Sponsor boards: board answered no feed', {
            board: `${board.platform}:${board.slug}`,
            hint: body ? `${body.length} chars` : 'HTTP error; board renamed, moved to another ATS or closed',
          });
          return true;
        }

        counts.listed += jobs.length;

        for (const job of jobs) {
          const id = String(job.externalId);
          if (checked.has(id)) {
            counts.seen += 1;
            continue;
          }
          if (isOlderThan(job.postedAt, this.settings.maxAgeDays)) {
            counts.old += 1;
            continue;
          }
          this.markChecked(id);
          checked.add(id);
          counts.judged += 1;
          tally(judgeFeedJob(asJob(job, board.name), KEY), counts);
        }
        return true;
      });
    } finally {
      this.addListed(counts.listed);
      logger.info('Sponsor boards', summarize(counts));
    }

    return counts.kept;
  }
}

/** A board's job as the feeds' shape, marked as from a known sponsor. */
export function asJob(item, company = null) {
  const remote =
    item.remote === true ||
    String(item.workplace ?? '').toLowerCase() === 'remote' ||
    /remote/i.test(item.location ?? '');

  return {
    feed: 'sponsor',
    id: String(item.externalId),
    title: item.title,
    company: item.company ?? company,
    url: item.url,
    location: item.location ?? null,
    restriction: null,
    description: [item.excerpt, KNOWN_SPONSOR].filter(Boolean).join('\n'),
    postedAt: item.postedAt ?? null,
    remote,
  };
}

/*
 * Checked on 2026-11-02 (Android app): each board answered with open roles.
 * Remote-first ones (GitLab, Elastic, Supabase, Linear) are here for their
 * remote roles open to EMEA. Doctolib moved from Greenhouse to Ashby.
 */
export const BOARDS = [
  'greenhouse:monzo:Monzo',
  'greenhouse:deliveroo:Deliveroo',
  'greenhouse:tide:Tide',
  'greenhouse:gocardless:GoCardless',
  'greenhouse:wise:Wise',
  'greenhouse:intercom:Intercom',
  'greenhouse:adyen:Adyen',
  'greenhouse:catawiki:Catawiki',
  'greenhouse:celonis:Celonis',
  'greenhouse:n26:N26',
  'greenhouse:hellofresh:HelloFresh',
  'greenhouse:getyourguide:GetYourGuide',
  'greenhouse:contentful:Contentful',
  'greenhouse:sumup:SumUp',
  'greenhouse:trivago:trivago',
  'greenhouse:datadog:Datadog',
  'greenhouse:cloudflare:Cloudflare',
  'greenhouse:gitlab:GitLab',
  'greenhouse:elastic:Elastic',
  'lever:spotify:Spotify',
  'lever:swile:Swile',
  'lever:veepee:Veepee',
  'ashby:paddle:Paddle',
  'ashby:pennylane:Pennylane',
  'ashby:synthesia:Synthesia',
  'ashby:supabase:Supabase',
  'ashby:linear:Linear',
  'ashby:doctolib:Doctolib',
];

export default SponsorBoardsSource;
