#!/bin/sh
# Runs each source as its own short-lived process, on an interval.
#
# Why not one long-lived process with an internal scheduler: memory. A crawl
# peaks well above what it idles at — Wuzzuf pages are half a megabyte each —
# and a process that stays up keeps its high-water mark for the life of the
# container. Separate processes hand every byte back when they exit, so the
# host only ever holds one source's peak instead of the sum of all of them.
#
# That matters on the 256 MB host this is tuned for, where one process holding
# every source's high-water mark leaves too little for the next pass. The old
# behaviour is still available with CRAWL_MODE=watch.
#
# Three lanes, because not every source wants the same rhythm. CRAWL_SOURCES
# runs every pass, on an interval. CRAWL_DAILY_SOURCES runs once a calendar day,
# at a wall-clock time. The paid posts sources are there: they are billed per
# query and read a search index that updates daily, so crawling them hourly
# would pay 24 times over for the same posts. CRAWL_PERIODIC_SOURCES runs every
# N hours: x_session, which searches as a signed-in account and has to look
# like a person doing it, not a bot searching every hour. An entry may carry
# its own interval as source:hours — recruitera:6 is every six hours.
#
# A periodic source that fails — crashes, or exits 75 because the network cut
# it short — is not marked as run, so the next pass (CRAWL_INTERVAL_MINUTES
# later) tries it again. After CRAWL_NOTIFY_AFTER_FAILURES failures in a row
# the loop sends a Telegram alert, and another once it succeeds again. An alert
# that cannot be sent, because the network is still down, is tried again on
# the next pass rather than given up on.
#
# CRAWL_RUN_AT_START names periodic sources that also run on the first pass
# after every container start, whatever the recorded last run says, then keep
# their interval from there. recruitera is one: it is free, and dedup makes an
# extra run send nothing twice. x_session must not be: a restart loop would
# have the account searching on every boot. Nor must a paid posts source: a
# restart loop would bill a full run of Serper queries on every boot, which is
# why linkedin_posts is in the daily lane by default, as the Dockerfile has it.
#
# The daily lane is a time of day rather than a 1440-minute interval because
# those are not the same thing. An interval drifts by however long each pass
# took, so "every 24 hours" slides through the clock and lands wherever; a time
# of day does not.
#
# Tunables (all optional):
#   CRAWL_MODE               sequential (default) | watch
#   CRAWL_SOURCES            space-separated source keys, in run order
#   CRAWL_INTERVAL_MINUTES   how long between passes (default 15)
#   CRAWL_DAILY_SOURCES      sources that run once a day
#   CRAWL_DAILY_AT           the time of day they run, HH:MM (default 03:30),
#                            read in the container's TZ
#   CRAWL_PERIODIC_SOURCES   sources that run every CRAWL_PERIODIC_HOURS, or
#                            every N hours when written source:N
#                            (default recruitera:6)
#   CRAWL_PERIODIC_HOURS     hours between their runs (default 6)
#   CRAWL_NOTIFY_AFTER_FAILURES  failed runs in a row of a periodic source
#                            before a Telegram alert (default 3; 0 = never)
#   CRAWL_RUN_AT_START       periodic sources that also run right after the
#                            container starts (default recruitera)
#   NODE_HEAP_MB             V8 old-space cap per crawl process (default 128)
#   CRAWL_STARTUP_DELAY      seconds to wait before the first pass (default 0)
#   CRAWL_CONTROL_DIR        where Telegram command requests are queued
#                            (default /tmp/crawl-control)
#   CRAWL_REQUEST_POLL       seconds between checks for a request while the
#                            loop sleeps (default 15)

set -u

CRAWL_MODE="${CRAWL_MODE:-sequential}"
# Set but empty means "no sources in this lane", which is how a container runs
# only the daily one. Hence ${VAR-default} rather than ${VAR:-default}, which
# would read an empty value as unset and hand back the default instead.
CRAWL_SOURCES="${CRAWL_SOURCES-linkedin_jobs}"
CRAWL_INTERVAL_MINUTES="${CRAWL_INTERVAL_MINUTES:-15}"
CRAWL_DAILY_SOURCES="${CRAWL_DAILY_SOURCES-linkedin_posts x_posts}"
CRAWL_DAILY_AT="${CRAWL_DAILY_AT:-03:30}"
CRAWL_PERIODIC_SOURCES="${CRAWL_PERIODIC_SOURCES-workable:1 vertex:1 recruitera:6 ats:12 ai_data:3 drjob:1 linkedin_worldwide:1 jobright:1 remote_feeds:3 jobgether:3 sponsor_boards:3 relocateme:3 job_apis:12 hn_hiring:24}"
CRAWL_NOTIFY_AFTER_FAILURES="${CRAWL_NOTIFY_AFTER_FAILURES:-3}"
CRAWL_PERIODIC_HOURS="${CRAWL_PERIODIC_HOURS:-6}"
CRAWL_RUN_AT_START="${CRAWL_RUN_AT_START-recruitera}"
NODE_HEAP_MB="${NODE_HEAP_MB:-128}"
CRAWL_STARTUP_DELAY="${CRAWL_STARTUP_DELAY:-0}"
HEARTBEAT_FILE="${HEARTBEAT_FILE:-/tmp/crawler-heartbeat}"
CRAWL_CONTROL_DIR="${CRAWL_CONTROL_DIR:-/tmp/crawl-control}"
CRAWL_REQUEST_POLL="${CRAWL_REQUEST_POLL:-15}"

log() {
  echo "[crawl-loop] $(date '+%Y-%m-%dT%H:%M:%S%z') $*"
}

# The healthcheck reads this: a loop that is alive touches it every pass, so a
# wedged container fails its check even though its process is still running.
beat() {
  date '+%s' > "$HEARTBEAT_FILE" 2>/dev/null || true
}

# Whether a directory can actually be written to, as opposed to looking like
# it can. `[ -w ]` reads permission bits and says yes for a directory on a
# read-only mount — which is what a platform without a persistent disk gives
# you, and the failure only shows up at the first write.
writable_dir() {
  [ -d "$1" ] || return 1

  probe="$1/.write-probe.$$"
  if (: > "$probe") 2>/dev/null; then
    rm -f "$probe" 2>/dev/null
    return 0
  fi

  return 1
}

# The calendar day a daily source last ran on. A date rather than a timestamp,
# because the question is "has it run today" and that answer must not change
# just because the clock moved.
#
# Beside the database when that is writable, so it survives a redeploy and a
# host redeploying five times in a day does not run five paid crawls. Where
# the data directory is read-only it falls back to /tmp, which is enough to
# stop the loop re-running it every pass — the failure this guards against is
# far more expensive than losing the state on restart.
last_run_file() {
  echo "${STATE_DIR}/.last-run-$1"
}

# What has already run this process, whatever the filesystem did or did not
# manage to record. The last line of defence: on a wholly read-only container
# the file write fails silently and, without this, the source is due again on
# every single pass.
daily_done=""

mark_run() {
  today="$(date '+%Y-%m-%d')"

  daily_done="${daily_done} $1=${today}"

  # In a subshell: a redirection that cannot be opened fails in the shell
  # before the command runs, so a `2>/dev/null` on the command itself never
  # sees it and the error reaches the log anyway.
  ( echo "$today" > "$(last_run_file "$1")" ) 2>/dev/null || true
}

ran_today() {
  today="$(date '+%Y-%m-%d')"

  case " ${daily_done} " in
    *" $1=${today} "*) return 0 ;;
  esac

  [ "$(cat "$(last_run_file "$1")" 2>/dev/null)" = "$today" ]
}

# "HH:MM" as minutes past midnight. The leading zero is stripped by hand
# because POSIX arithmetic reads 08 and 09 as invalid octal.
to_minutes() {
  hours="${1%%:*}"
  minutes="${1##*:}"
  hours="${hours#0}"
  minutes="${minutes#0}"
  [ -z "$hours" ] && hours=0
  [ -z "$minutes" ] && minutes=0

  echo "$(( hours * 60 + minutes ))"
}

# Whether a daily source is due: the configured time has passed and today is
# not the day it last ran.
#
# "At or after", not "at", because passes are spaced by an interval and one may
# simply not happen during the target minute — a pass starting at 03:25 and
# taking twenty minutes puts the next one past 03:30 entirely. The first pass
# after the time is therefore the one that runs it, a little late rather than
# not at all.
daily_due() {
  now="$(to_minutes "$(date '+%H:%M')")"
  target="$(to_minutes "$CRAWL_DAILY_AT")"

  [ "$now" -lt "$target" ] && return 1
  ran_today "$1" && return 1

  return 0
}

# The periodic lane keeps an epoch per source rather than a date: the question
# is "has it been N hours", and the same fallbacks as the daily lane apply —
# an in-process record first, a file beside the database second.
periodic_done=""

periodic_file() {
  echo "${STATE_DIR}/.last-periodic-$1"
}

mark_periodic() {
  now="$(date '+%s')"

  periodic_done="${periodic_done} $1=${now}"
  ( echo "$now" > "$(periodic_file "$1")" ) 2>/dev/null || true
}

last_periodic() {
  last=""

  for entry in $periodic_done; do
    case "$entry" in
      "$1="*) last="${entry#*=}" ;;
    esac
  done

  [ -z "$last" ] && last="$(cat "$(periodic_file "$1")" 2>/dev/null)"
  echo "${last:-0}"
}

# An entry's own interval when it is written source:hours, the lane-wide one
# otherwise — and the lane-wide one again for anything that is not a number,
# so a typo cannot turn into "every 0 hours".
periodic_hours() {
  case "$1" in
    *:*) hours="${1##*:}" ;;
    *) hours="$CRAWL_PERIODIC_HOURS" ;;
  esac

  case "$hours" in
    ''|*[!0-9]*|0) hours="$CRAWL_PERIODIC_HOURS" ;;
  esac

  echo "$hours"
}

# Whether this container has run the source yet. The in-process record only:
# the file beside the database outlives the container, which is the point of
# it everywhere except here.
ran_this_container() {
  case " ${periodic_done} " in
    *" $1="*) return 0 ;;
  esac

  return 1
}

# Due once its interval has passed since the last clean run. A few minutes of
# slack, so a pass that starts just short of the mark because the previous one
# ran quick does not push the source a whole interval later.
#
# A CRAWL_RUN_AT_START source is also due when this container has not run it
# yet, which is what makes it start on deploy rather than up to a day later.
periodic_due() {
  case " ${CRAWL_RUN_AT_START} " in
    *" $1 "*) ran_this_container "$1" || return 0 ;;
  esac

  last="$(last_periodic "$1")"
  case "$last" in
    ''|*[!0-9]*) last=0 ;;
  esac

  [ "$(( $(date '+%s') - last ))" -ge "$(( $2 * 3600 - 300 ))" ]
}

# Each source in its own process: whatever it allocates is returned to the host
# when it exits, before the next one starts.
#
# A non-zero exit is logged and the pass continues. One site being down is not
# a reason to skip the others, and the app already keeps whatever it parsed
# before a failure.
#
# Arguments after the source go to the app as they are (a request's --hours).
run_source() {
  source_key="$1"
  shift
  log "running ${source_key}${*:+ $*}"
  ( echo "$source_key" > "${CRAWL_CONTROL_DIR}/current" ) 2>/dev/null || true

  # Captured into a variable rather than read as $? further down: the TERM
  # handler runs its own commands between the two, and would otherwise be the
  # exit status being reported.
  # CRAWL_TRIGGER labels the run in the app's log: who asked for it.
  CRAWL_TRIGGER="${CRAWL_TRIGGER:-schedule}" \
    node "--max-old-space-size=${NODE_HEAP_MB}" src/index.js --once --source "$source_key" "$@"
  status=$?
  rm -f "${CRAWL_CONTROL_DIR}/current" 2>/dev/null

  if [ "$status" -eq 0 ]; then
    log "${source_key} finished"
    return 0
  fi

  if [ "$status" -eq 75 ]; then
    log "${source_key} was cut short by the network — it will be retried on the next pass"
  else
    log "${source_key} exited with ${status} — continuing with the next source"
  fi

  return 1
}

# Failed runs in a row per periodic source, and whether an alert about the
# current streak has gone out — "key=value" lists, like periodic_done. In
# process only: a restart runs CRAWL_RUN_AT_START sources at once anyway, and
# a streak that survives one would alert about a container that no longer runs.
failure_counts=""
alerted=""

list_get() {
  value=""
  for entry in $1; do
    case "$entry" in
      "$2="*) value="${entry#*=}" ;;
    esac
  done
  echo "$value"
}

list_set() {
  kept=""
  for entry in $1; do
    case "$entry" in
      "$2="*) ;;
      *) kept="${kept} ${entry}" ;;
    esac
  done
  echo "${kept} $2=$3"
}

# Telegram through the app's own client. Its exit status says whether the
# message went through, which is what decides whether to try again.
notify() {
  node --max-old-space-size=32 src/notify.js "$1"
}

# After a periodic source's run: count the failure, alert once the streak is
# long enough, and say so when it recovers. An alert that fails to send stays
# owed and is tried after the next failure — or becomes the recovery message,
# which then carries the news of the outage.
track_periodic() {
  source_key="$1"
  run_status="$2"
  failures="$(list_get "$failure_counts" "$source_key")"
  failures="${failures:-0}"
  was_alerted="$(list_get "$alerted" "$source_key")"

  if [ "$run_status" -eq 0 ]; then
    if [ "$was_alerted" = "yes" ]; then
      notify "${source_key} is running again: it succeeded after ${failures} failed attempts." &&
        log "${source_key} recovery alert sent"
    elif [ "$was_alerted" = "owed" ]; then
      notify "${source_key} failed ${failures} times in a row (network or crash) and has now recovered; the missed runs were redone." &&
        log "${source_key} outage alert sent after recovery"
    fi

    failure_counts="$(list_set "$failure_counts" "$source_key" 0)"
    alerted="$(list_set "$alerted" "$source_key" no)"
    return 0
  fi

  failures="$(( failures + 1 ))"
  failure_counts="$(list_set "$failure_counts" "$source_key" "$failures")"

  [ "$CRAWL_NOTIFY_AFTER_FAILURES" -gt 0 ] 2>/dev/null || return 0
  [ "$failures" -lt "$CRAWL_NOTIFY_AFTER_FAILURES" ] && return 0
  [ "$was_alerted" = "yes" ] && return 0

  reason="it crashed (exit ${run_status})"
  [ "$run_status" -eq 75 ] && reason="the network cut it short"

  if notify "${source_key} failed ${failures} times in a row: ${reason}. Retrying every ${CRAWL_INTERVAL_MINUTES} minutes until it succeeds."; then
    alerted="$(list_set "$alerted" "$source_key" yes)"
    log "${source_key} failure alert sent"
  else
    alerted="$(list_set "$alerted" "$source_key" owed)"
    log "${source_key} failure alert could not be sent; will try again"
  fi

  return 0
}

# A source run on request still counts as its lane's run: a paid source asked
# for at noon is not due again at the next pass just because its clock said so.
mark_requested() {
  case " ${CRAWL_DAILY_SOURCES} " in
    *" $1 "*) mark_run "$1" ;;
  esac

  for lane_entry in $CRAWL_PERIODIC_SOURCES; do
    [ "${lane_entry%%:*}" = "$1" ] && mark_periodic "$1"
  done

  return 0
}

# Sources asked for through the Telegram bot (src/bot.js), which only queues
# them: queue/<source> holds the chat ids that asked. Run here, in the loop,
# so a requested crawl never overlaps a scheduled one.
#
# The request is claimed by renaming it, so a chat asking again mid-run queues
# a fresh request rather than vanishing into this one. The result is written
# under a dot name and renamed, so the bot never reads it half-written.
#
# hours/<source>, when the bot wrote one, is how far back to crawl this time
# (/jobs 10); it is claimed the same way and passed on as --hours. Anything
# but a whole number from 1 to 168 is ignored and the configured window used.
run_requests() {
  for request in "${CRAWL_CONTROL_DIR}"/queue/*; do
    [ "$stopping" -eq 1 ] && return 0
    [ -f "$request" ] || continue

    requested="${request##*/}"
    claimed="${CRAWL_CONTROL_DIR}/claimed-${requested}"
    mv "$request" "$claimed" 2>/dev/null || continue

    request_hours=""
    hours_claimed="${CRAWL_CONTROL_DIR}/claimed-hours-${requested}"
    if mv "${CRAWL_CONTROL_DIR}/hours/${requested}" "$hours_claimed" 2>/dev/null; then
      request_hours="$(tr -d '[:space:]' < "$hours_claimed" 2>/dev/null)"
      rm -f "$hours_claimed"
      case "$request_hours" in
        ''|*[!0-9]*) request_hours="" ;;
        *) [ "$request_hours" -ge 1 ] && [ "$request_hours" -le 168 ] || request_hours="" ;;
      esac
    fi

    log "${requested} requested from Telegram${request_hours:+ (last ${request_hours}h)}"
    request_started="$(date '+%s')"

    if [ -n "$request_hours" ]; then
      CRAWL_TRIGGER=telegram run_source "$requested" --hours "$request_hours"
    else
      CRAWL_TRIGGER=telegram run_source "$requested"
    fi
    request_status="$status"
    [ "$request_status" -eq 0 ] && mark_requested "$requested"

    result="${CRAWL_CONTROL_DIR}/done/${requested}-${request_started}"
    {
      echo "${request_status} $(( $(date '+%s') - request_started ))${request_hours:+ ${request_hours}}"
      echo "$requested"
      cat "$claimed"
    } > "${CRAWL_CONTROL_DIR}/done/.${requested}" 2>/dev/null       && mv "${CRAWL_CONTROL_DIR}/done/.${requested}" "$result" 2>/dev/null

    rm -f "$claimed"
    beat
  done

  return 0
}

# Stop between sources rather than mid-crawl, so a shutdown never interrupts a
# fetch that has already been paced and paid for.
stopping=0
trap 'stopping=1; log "signal received; stopping after the current source"' TERM INT

if [ "$CRAWL_MODE" = "watch" ]; then
  log "CRAWL_MODE=watch: one resident process with the built-in scheduler"
  log "Telegram run commands are not available in watch mode"
  exec node src/index.js --watch
fi

# A database on a volume survives a redeploy; one in /tmp does not, and the
# symptom is every job arriving again after each deploy. Worth saying once at
# startup rather than leaving it to be discovered.
#
# Probed by writing, not by asking: a read-only mount still reports writable
# permission bits, so the old check said yes and every write then failed.
DATA_DIR="${DATA_DIR:-/app/data}"

if writable_dir "$DATA_DIR"; then
  STATE_DIR="$DATA_DIR"
  log "data directory ${DATA_DIR} is writable"
else
  STATE_DIR="/tmp/linkedin-jobs-telegram"
  mkdir -p "$STATE_DIR" 2>/dev/null || true

  log "WARNING: ${DATA_DIR} is read-only — the database will fall back to a"
  log "WARNING: temporary path and dedup will be lost on the next deploy."
  log "WARNING: Daily-lane state falls back to ${STATE_DIR}, so a paid source"
  log "WARNING: still runs once a day, but once more after every restart."
  log "WARNING: Mount a writable volume there, or set DATABASE_PATH to one."
fi

mkdir -p "${CRAWL_CONTROL_DIR}/queue" "${CRAWL_CONTROL_DIR}/done" 2>/dev/null || true

# Hosts that route HTTP — Render, Railway and Fly web services — kill a
# container that opens no port, however well the crawl is going. This mode
# deliberately keeps nothing resident, so a tiny separate process holds the
# port and answers /healthz from the heartbeat file below. The same process
# polls Telegram for run commands. It costs a few MB, and exits at once when
# there is neither a PORT nor a bot token to serve — which is decided in node,
# because the token may live in .env, which this shell never reads.
node --max-old-space-size=32 src/heartbeat-server.js &
port_pid=$!
log "heartbeat server and bot (pid ${port_pid})"

# Whatever ends the loop must also end that child, or tini is left waiting on
# a process nobody is going to stop.
cleanup() {
  [ -n "$port_pid" ] && kill "$port_pid" 2>/dev/null
  return 0
}
trap cleanup EXIT

log "timezone: ${TZ:-unset} (now $(date '+%Y-%m-%d %H:%M %z'))"
log "sources: ${CRAWL_SOURCES} every ${CRAWL_INTERVAL_MINUTES}m"
log "daily sources: ${CRAWL_DAILY_SOURCES:-none} at ${CRAWL_DAILY_AT} ${TZ:-local time}"
log "periodic sources: ${CRAWL_PERIODIC_SOURCES:-none} (default every ${CRAWL_PERIODIC_HOURS}h)"
log "run at start: ${CRAWL_RUN_AT_START:-none}"
log "periodic failures before a Telegram alert: ${CRAWL_NOTIFY_AFTER_FAILURES}"
log "heap cap: ${NODE_HEAP_MB}MB per process"

beat

if [ "$CRAWL_STARTUP_DELAY" -gt 0 ] 2>/dev/null; then
  log "waiting ${CRAWL_STARTUP_DELAY}s before the first pass"
  sleep "$CRAWL_STARTUP_DELAY" &
  wait $! || true
fi

while [ "$stopping" -eq 0 ]; do
  started_at="$(date '+%s')"
  beat

  for source in $CRAWL_SOURCES; do
    [ "$stopping" -eq 1 ] && break

    run_source "$source"
    beat
    run_requests
  done

  # The daily lane, checked every pass and run when the clock says so. Marked
  # on a clean exit only, so a crash is retried on the next pass — a source
  # that merely found nothing, or was blocked, still exits 0 and so still waits
  # until tomorrow.
  for source in $CRAWL_DAILY_SOURCES; do
    [ "$stopping" -eq 1 ] && break

    if ! daily_due "$source"; then
      log "${source} not due (${CRAWL_DAILY_AT} daily; now $(date '+%H:%M'), last ran $(cat "$(last_run_file "$source")" 2>/dev/null || echo never))"
      continue
    fi

    run_source "$source" && mark_run "$source"
    beat
    run_requests
  done

  # The periodic lane, marked on a clean exit only, like the daily one.
  for entry in $CRAWL_PERIODIC_SOURCES; do
    [ "$stopping" -eq 1 ] && break

    source="${entry%%:*}"
    hours="$(periodic_hours "$entry")"

    if ! periodic_due "$source" "$hours"; then
      log "${source} not due (every ${hours}h)"
      continue
    fi

    run_source "$source" && mark_periodic "$source"
    track_periodic "$source" "$status"
    beat
    run_requests
  done

  [ "$stopping" -eq 1 ] && break

  elapsed="$(( $(date '+%s') - started_at ))"
  remaining="$(( CRAWL_INTERVAL_MINUTES * 60 - elapsed ))"

  # A pass that overran its own interval starts the next one immediately
  # rather than trying to catch up, which would stack crawls against a
  # rate-limited endpoint.
  if [ "$remaining" -lt 1 ]; then
    log "pass took ${elapsed}s, at or past the interval; starting the next one"
    continue
  fi

  log "pass took ${elapsed}s; sleeping ${remaining}s"

  # In short naps, checking for requests between them, so a command waits
  # seconds rather than the rest of the interval. The wake time is fixed up
  # front: a requested run during the sleep does not push the next pass back.
  # Backgrounded so a signal interrupts the nap instead of waiting it out.
  wake_at="$(( $(date '+%s') + remaining ))"
  while [ "$stopping" -eq 0 ]; do
    run_requests

    left="$(( wake_at - $(date '+%s') ))"
    [ "$left" -lt 1 ] && break
    [ "$left" -gt "$CRAWL_REQUEST_POLL" ] && left="$CRAWL_REQUEST_POLL"

    sleep "$left" &
    wait $! || true
  done
done

log "stopped"
