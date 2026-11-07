#!/bin/sh
set -e

# Prepare the data and log directories, then run the app as an unprivileged
# user when that is possible.
#
# Everything here is best effort on purpose. Hosts vary in what they allow, and
# none of these steps is worth failing to start over:
#
#   * the application directory may be mounted read-only, so mkdir and chown
#     fail — the app falls back to a writable path and logs that it did;
#   * the container may run without CAP_SETGID, so su-exec cannot drop
#     privileges — we then simply stay as the current user.
# Best effort for every user, not only root: a mounted volume arrives with the
# host's ownership, and the container may be running as a UID that owns
# nothing. Widening the mode is the only move available to it, and a failure
# here is not worth refusing to start over — the app falls back to a writable
# path and says so.
mkdir -p /app/data /app/logs 2>/dev/null || true
chmod -R 0777 /app/data /app/logs 2>/dev/null || true

if [ "$(id -u)" = "0" ]; then
  chown -R node:node /app/data /app/logs 2>/dev/null || true

  # Probe before committing: if su-exec cannot switch user here, calling it for
  # real would kill the container instead of starting the app.
  if su-exec node true 2>/dev/null; then
    exec su-exec node "$@"
  fi

  echo "entrypoint: cannot drop privileges (no CAP_SETGID); continuing as root" >&2
fi

exec "$@"
