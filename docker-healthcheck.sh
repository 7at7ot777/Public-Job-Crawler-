#!/bin/sh
# Healthy means "crawling on schedule", which looks different in each mode.
#
# CRAWL_MODE=watch keeps a process listening on PORT, so the app's own
# /healthz answers. The sequential mode deliberately has nothing resident —
# that is the point of it — so the loop's heartbeat file stands in: it is
# touched before and after every source, and goes stale only if the loop is
# wedged or dead.

set -u

HEARTBEAT_FILE="${HEARTBEAT_FILE:-/tmp/crawler-heartbeat}"
CRAWL_MODE="${CRAWL_MODE:-sequential}"
CRAWL_INTERVAL_MINUTES="${CRAWL_INTERVAL_MINUTES:-60}"

if [ "$CRAWL_MODE" = "watch" ]; then
  wget -qO- "http://127.0.0.1:${PORT:-3000}/healthz" >/dev/null 2>&1
  exit $?
fi

if [ ! -f "$HEARTBEAT_FILE" ]; then
  echo "no heartbeat yet at ${HEARTBEAT_FILE}" >&2
  exit 1
fi

last="$(cat "$HEARTBEAT_FILE" 2>/dev/null || echo 0)"
age="$(( $(date '+%s') - last ))"

# Two intervals plus a margin: one slow pass must not fail the check, but a
# loop that has stopped touching the file will.
# Never under an hour: with a 15-minute interval, two intervals is shorter than
# one slow periodic source, and the heartbeat only moves between sources.
limit="$(( CRAWL_INTERVAL_MINUTES * 60 * 2 + 600 ))"
[ "$limit" -lt 3600 ] && limit=3600

if [ "$age" -gt "$limit" ]; then
  echo "heartbeat is ${age}s old, limit ${limit}s" >&2
  exit 1
fi

exit 0
