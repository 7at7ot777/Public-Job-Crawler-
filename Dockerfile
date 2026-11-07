# Node 22.5+ is required for the built-in node:sqlite module.
FROM node:24-alpine

# tini reaps zombies and forwards SIGTERM, so a stop triggers the app's own
# shutdown instead of a kill after the grace period; su-exec drops root in the
# entrypoint.
#
# tzdata is not optional here. Alpine ships no zone database, and without one
# TZ is ignored and everything runs in UTC — schedules, log lines, and the
# "Posted" time on every Telegram message.
RUN apk add --no-cache tini su-exec tzdata

# PORT is what the platform routes to; most default to 3000. The health server
# binds whatever PORT says, so a host that injects its own value still works.
ENV NODE_ENV=production \
    PORT=3000 \
    TZ=Africa/Cairo

# How the container crawls.
#
# 'sequential' runs each source as its own short-lived process, so whatever a
# crawl peaks at is handed back to the host before the next source starts.
# That is what makes this fit the 256 MB host: the peak is the largest single
# source rather than the sum of them.
#
# 'watch' is the older single resident process with the built-in scheduler and
# an HTTP endpoint. It needs roughly twice the headroom, because one process
# keeps the high-water mark of every source for the life of the container.
ENV CRAWL_MODE=sequential

# Run order. Drop a key to stop crawling that site. Set it empty to run
# nothing hourly, leaving only the daily lane below.
#
# linkedin_posts is absent on purpose: it is billed per query, so it runs in
# the periodic lane below.
#
# wuzzuf, tanqeeb and naukrigulf are left out: all three block datacenter IPs
# (Wuzzuf answers 403, Naukrigulf never answers, Tanqeeb serves a page with no
# jobs), so from a hosted container they cost minutes a pass and find nothing.
# They still work from a residential connection; add them back there.
ENV CRAWL_SOURCES="linkedin_jobs"

# Minutes between passes. A pass that overruns starts the next immediately
# rather than stacking crawls against a rate-limited endpoint.
#
# 10: with the search Egypt only, a LinkedIn pass is ~21 searches, ~6 minutes,
# so 10 still leaves a pause between passes. It was 30 when a pass ran ~20
# minutes; at 15 then, the loop never paused and crawled LinkedIn continuously,
# which is what drew the 429s. Raise it again if the markets grow.
ENV CRAWL_INTERVAL_MINUTES=10

# The slower lane, checked every pass but run only when the clock reaches
# CRAWL_DAILY_AT: linkedin_posts at 07:00, once a day. It belongs here rather
# than in the periodic lane below because it is billed per query (33 Serper
# queries a run), and a fixed time of day is what "once a day" should mean. The
# day it last ran is recorded under DATA_DIR, so a redeploy does not crawl it
# again.
#
# A time of day, not an interval: "every 1440 minutes" drifts by however long
# each pass took and slides through the clock. Read in TZ above, which is why
# tzdata is installed.
ENV CRAWL_DAILY_SOURCES="linkedin_posts x_posts glassdoor"
ENV CRAWL_DAILY_AT=07:00

# V8 old-space cap for each crawl process. Measured peak RSS at this setting:
# Wuzzuf alone ~79 MB, Tanqeeb alone ~87 MB, every source in one process ~90 MB.
# 128 MB of heap on a 256 MB host leaves room for the heartbeat process, SQLite
# and the kernel. RSS runs above the cap, since the cap covers the heap and not
# the code, stacks and buffers around it — raise this only with the host.
ENV NODE_HEAP_MB=128

# Every N hours rather than at a time of day. recruitera:6 is Recruitera's
# Egypt and Gulf tech jobs every six hours, and CRAWL_RUN_AT_START makes it also run
# as soon as the container starts — a deploy delivers straight away instead of
# up to six hours later. A run the network cuts short is retried on the next
# pass, with a Telegram alert after CRAWL_NOTIFY_AFTER_FAILURES in a row. Dedup makes that extra run send nothing twice.
#
# linkedin_posts is not here: it sits in the daily lane above so it runs at
# 07:00 every day rather than every 24 hours from whenever the last run
# happened. Staying out of CRAWL_RUN_AT_START is deliberate too — at 33 Serper
# queries a run, a restart loop would bill 33 queries a boot. SERPER_DAILY_CAP
# (default 100) is the backstop if that happens anyway.
# ats:12 is the named companies' career boards (ATS_BOARDS), twice a day.
# The boards change slowly, and with ~45 boards a pass is a few minutes of
# downloads (a Zoho careers page is ~2 MB) for what is mostly nothing new; the
# boards keep their jobs open for days, so twice a day still finds them fresh.
# It is also the lane that works from this host — wuzzuf, tanqeeb and
# naukrigulf answer a browser and not this IP.
#
# x_session goes here too, as plain x_session (every CRAWL_PERIODIC_HOURS),
# once its cookies are set — but never in CRAWL_RUN_AT_START.
# The sources ported from the Android app, at the app's own rhythm: Dr.Job and
# jobright hourly, the remote feeds, Jobgether, the visa-sponsor boards and
# relocate.me every 3 hours, the job APIs twice a day (they skip themselves
# until a key is in .env), Hacker News daily. Each is its own short process,
# like every other source. mostaql and khamsat are left out: their boards
# answer this host with a bot challenge (they work from the phone app).
ENV CRAWL_PERIODIC_SOURCES="workable:1 recruitera:6 ats:12 ai_data:3 drjob:1 linkedin_worldwide:1 jobright:1 remote_feeds:3 jobgether:3 sponsor_boards:3 relocateme:3 job_apis:12 hn_hiring:24 x_session:6"
ENV CRAWL_RUN_AT_START="recruitera"

# What and where to look, left to the defaults in src/config.js (Egypt only,
# on-site and remote alike; Laravel, Full Stack, Backend, Node.js and
# Software Engineer hiring posts in Egypt and the Greater Cairo areas). Set
# empty rather than unset on purpose: dotenv never overwrites a variable that
# already exists, so an empty one here keeps an older .env baked into the
# image — which still says remote-only, Laravel only — from deciding instead.
# The platform's own settings override these as usual.
#
# JOB_REMOTE_LOCATIONS is a single space, not empty: empty means "use the code
# default" (Worldwide, EMEA), while a space trims away to no markets at all.
# The f_WT=2 searches are dropped to cut the pass's request count; the local
# markets already return remote postings, since they carry no work-type filter.
ENV FILTER_REMOTE_ONLY="" \
    JOB_LOCATIONS="" \
    JOB_REMOTE_LOCATIONS=" " \
    FILTER_LOCATIONS="" \
    LINKEDIN_POSTS_KEYWORDS="" \
    LINKEDIN_POSTS_LOCATIONS=""

WORKDIR /app

# Copy manifests first so `npm ci` is cached until dependencies actually change.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

# Everything .dockerignore allows — src/ plus .env, so the image is runnable
# without an --env-file. Platform environment variables still take precedence.
COPY . .

# data/ holds the SQLite dedup database and must be a mounted volume — without
# one, every container restart re-sends jobs that were already delivered.
# 0777 rather than an owner, because the UID this runs as is not ours to
# predict: some platforms hand the container an arbitrary high UID that owns
# nothing and cannot change user, which makes a chown to node:node useless and
# leaves the database falling back to /tmp — where it is lost on every deploy,
# and every job gets sent again.
RUN mkdir -p /app/data /app/logs \
 && chown -R node:node /app/data /app/logs \
 && chmod -R 0777 /app/data /app/logs \
 && chmod +x /app/docker-entrypoint.sh /app/docker-crawl-loop.sh /app/docker-healthcheck.sh

# Deliberately NOT `USER node`: the entrypoint starts as root only long enough
# to make a mounted volume writable, then drops to node via su-exec.

# Declared so the runtime mounts them writable even when the application
# directory itself is read-only, which some platforms enforce. Without a
# persistent volume behind them the app still runs — it falls back to a
# writable path and says so — but dedup is then lost on every restart.
VOLUME ["/app/data", "/app/logs"]

# Both modes listen here when PORT is set: watch through the app's own health
# server, sequential through the small heartbeat server the crawl loop starts
# beside it. A host that routes HTTP kills a container with no open port.
EXPOSE 3000

# What "healthy" means depends on CRAWL_MODE: an HTTP probe when a process is
# resident, the crawl loop's heartbeat when it is not. The script picks.
# start-period covers the first pass, which crawls every source.
HEALTHCHECK --interval=60s --timeout=10s --start-period=300s --retries=3 \
  CMD /app/docker-healthcheck.sh

ENTRYPOINT ["/sbin/tini", "--", "/app/docker-entrypoint.sh"]

# Crawls every source immediately, then every CRAWL_INTERVAL_MINUTES.
CMD ["/app/docker-crawl-loop.sh"]
