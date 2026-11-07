import { escapeHtml, htmlLink } from './text.js';
import { SHOWN } from './region.js';
import { STATUS } from './store.js';

/*
 * Friday's look back at the week, ported from the Android app's Weekly.kt:
 * what was found and where, the best of it, what the paid search cost, why
 * jobs were dropped, and any source gone quiet. Sent once, after 10:00 in the
 * schedule's time zone (Africa/Cairo).
 */

export const STATE = 'weekly.lastSent';
export const HOUR = 10;
export const BUDGET = 3900;
/** Later than this after Friday 10:00, the week is skipped rather than sent late. */
const LATE_MS = 2 * 86_400_000;

/** The wall-clock date, weekday and hour of an instant in a time zone. */
function local(now, timeZone) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      hourCycle: 'h23',
      weekday: 'short',
    })
      .formatToParts(now)
      .map((part) => [part.type, part.value]),
  );
  return { date: `${parts.year}-${parts.month}-${parts.day}`, weekday: parts.weekday, hour: Number(parts.hour) };
}

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

function addDays(isoDate, days) {
  const date = new Date(`${isoDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/** The most recent Friday (YYYY-MM-DD) whose 10:00 has passed, in timeZone. */
export function slot(now, timeZone) {
  const { date, weekday, hour } = local(now, timeZone);
  let back = (WEEKDAYS.indexOf(weekday) - 5 + 7) % 7;
  if (back === 0 && hour < HOUR) back = 7;
  return addDays(date, -back);
}

/** The instant of a local date's hour in timeZone. */
export function instantOf(isoDate, hour, timeZone) {
  const guess = Date.parse(`${isoDate}T${String(hour).padStart(2, '0')}:00:00Z`);
  // The zone's offset at that moment, read back from the formatter.
  const seen = local(new Date(guess), timeZone);
  const seenMs = Date.parse(`${seen.date}T${String(seen.hour).padStart(2, '0')}:00:00Z`);
  return new Date(guess - (seenMs - guess));
}

const sinceSlot = (now, timeZone) => now.getTime() - instantOf(slot(now, timeZone), HOUR, timeZone).getTime();

export const weeklyDue = (now, lastSent, timeZone) =>
  slot(now, timeZone) !== lastSent && sinceSlot(now, timeZone) < LATE_MS;

/** Not sent, and too late now: the caller marks the week done without sending. */
export const weeklyMissed = (now, lastSent, timeZone) =>
  slot(now, timeZone) !== lastSent && sinceSlot(now, timeZone) >= LATE_MS;

const short = (isoDate) =>
  new Date(`${isoDate}T00:00:00Z`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' });

/**
 * @param {{from: string, to: string, facts: object[], top: object[], runs: object[],
 *          serperWeek: number, quiet: Record<string, number>}} data
 */
export function renderWeekly(data) {
  const { facts } = data;
  const sections = [];

  sections.push(`📊 <b>Your week</b> · ${short(data.from)}–${short(data.to)}`);

  const sent = facts.filter((fact) => fact.status === STATUS.SENT).length;
  const digest = facts.filter((fact) => fact.status === STATUS.DIGEST).length;
  sections.push(`<b>Jobs:</b> ${facts.length} new · ${sent} sent${digest > 0 ? ` · ${digest} in digest` : ''}`);

  if (facts.length > 0) {
    sections.push(
      SHOWN.map((region) => `${region.flag} ${escapeHtml(region.label)} ${facts.filter((f) => f.region === region).length}`).join(' · '),
    );

    const bySource = Object.entries(
      facts.reduce((acc, fact) => ({ ...acc, [fact.source]: (acc[fact.source] ?? 0) + 1 }), {}),
    ).sort((a, b) => b[1] - a[1]);

    sections.push(
      `<b>By source:</b> ${bySource.slice(0, 5).map(([key, count]) => `${escapeHtml(key)} ${count}`).join(' · ')}` +
        (bySource.length > 5 ? ` · +${bySource.length - 5} more` : ''),
    );
  }

  if (data.top.length > 0) {
    sections.push(
      `<b>Best matches</b>\n${data.top
        .map(
          (row) =>
            `⭐ ${row.score ?? '–'} ${htmlLink(row.url, String(row.title).slice(0, 80))}` +
            (row.company ? ` · ${escapeHtml(String(row.company).slice(0, 40))}` : ''),
        )
        .join('\n')}`,
    );
  }

  if (data.serperWeek > 0) sections.push(`<b>Serper:</b> ${data.serperWeek} searches`);

  const dropped = {};
  for (const run of data.runs) {
    for (const [reason, count] of Object.entries(run.rejected ?? {})) dropped[reason] = (dropped[reason] ?? 0) + count;
  }
  const worst = Object.entries(dropped).sort((a, b) => b[1] - a[1]);
  if (worst.length > 0) {
    sections.push(`<b>Dropped most for:</b> ${worst.slice(0, 3).map(([k, v]) => `${escapeHtml(k)} ${v}`).join(' · ')}`);
  }

  const quiet = Object.entries(data.quiet ?? {});
  if (quiet.length > 0) {
    sections.push(`⚠️ <b>Gone quiet:</b> ${quiet.map(([k, v]) => `${escapeHtml(k)} (${v} empty runs in a row)`).join(', ')}`);
  }

  // Within Telegram's limit, dropping whole sections from the bottom, never cutting a tag in half.
  let out = '';
  for (const section of sections) {
    if (out.length + section.length + 1 > BUDGET) break;
    out += (out ? '\n' : '') + section;
  }
  return out;
}

export { addDays };
