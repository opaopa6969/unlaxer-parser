#!/usr/bin/env bash
# Dependency downloads only. Three total attempts, with 5s/10s waits by default.
# CI_NETWORK_RETRY_DELAY_SECONDS=0 disables waiting for the regression tests.
set -euo pipefail

if (( $# == 0 )); then
  echo 'Usage: retry-network.sh command [argument ...]' >&2
  exit 2
fi
delay=${CI_NETWORK_RETRY_DELAY_SECONDS:-5}
if [[ ! $delay =~ ^[0-9]{1,3}$ ]]; then
  echo 'CI_NETWORK_RETRY_DELAY_SECONDS must be an integer from 0 to 999' >&2
  exit 2
fi
delay=$((10#$delay))
log=$(mktemp "${TMPDIR:-/tmp}/retry-network.XXXXXX")
trap 'rm -f -- "$log"' EXIT

# Match transport errors and explicit HTTP status diagnostics, not generic words
# such as "download failed", "timeout" or bare numbers in versions/checksums.
# Formats: rustup/reqwest/hyper, Cargo/libcurl ([7] [16] [18] [52] [55] [56]),
# Cargo HttpNotSuccessful, npm-registry-fetch, and Playwright
# oopDownloadBrowserMain/network (see test_ci_network_retry.py).
transport='(^|[^[:alnum:]_])(ECONNRESET|ECONNREFUSED|ECONNABORTED|ETIMEDOUT|ESOCKETTIMEDOUT|ERR_SOCKET_TIMEOUT|EAI_AGAIN|ENOTFOUND|ENETUNREACH|EHOSTUNREACH)([^[:alnum:]_]|$)|Could not resolve (host|hostname)|Temporary failure in name resolution|failed to lookup address information|Connection reset|Connection refused|Connection timed out|connection closed before message completed|socket hang up|TLS handshake timeout|operation timed out|Timeout was reached|Couldn.t connect to server|Failure when receiving data from the peer|Failed sending data to the peer|Server returned nothing|Transferred a partial file|Error in the HTTP2 framing layer|Download failed: server closed connection|Request to https?://[^ ]+ timed out after'
# Only the end of the current attempt decides: the final error is printed last,
# while earlier lines may be retries the tool already recovered from (Cargo's
# "spurious network error" warnings are dropped for the same reason).
recent_lines=50
status='(429|5[0-9]{2})([^[:alnum:]_]|$)'
http="HTTP(/[0-9.]+)?[[:space:]]+$status|http request returned an unsuccessful status code:[[:space:]]*$status|failed to get successful HTTP response from .*got $status|server returned code $status|(^|[^[:alnum:]_])E$status"

for attempt in 1 2 3; do
  printf '[retry-network] attempt %s/3: %s\n' "$attempt" "$1" >&2
  # The conditional suppresses errexit only for this pipeline. Capture both
  # statuses immediately: $? alone would report tee's result under pipefail.
  if "$@" 2>&1 | tee "$log"; then
    statuses=("${PIPESTATUS[@]}")
  else
    statuses=("${PIPESTATUS[@]}")
  fi
  code=${statuses[0]}
  if (( code == 0 )); then
    exit "${statuses[1]}"
  fi
  if (( statuses[1] != 0 || code >= 128 || attempt == 3 )); then
    exit "$code"
  fi
  # Captured first (no grep -q in a pipeline: an early exit would SIGPIPE the
  # writer and pipefail would turn a match into a miss).
  recent=$(tail -n "$recent_lines" -- "$log" | LC_ALL=C grep -Eiv -- 'spurious network error' || true)
  if ! LC_ALL=C grep -Eiq -- "$transport|$http" <<<"$recent"; then
    exit "$code"
  fi
  wait_seconds=$((delay * attempt))
  printf '[retry-network] connection failure (exit %s); retrying in %ss\n' "$code" "$wait_seconds" >&2
  sleep "$wait_seconds"
done
