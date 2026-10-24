#!/usr/bin/env bash
# #718: a consumer of the published release resolves it from Central alone, into its own
# empty repository, while this job's repository holds the development build of the same
# version. The job repository is not read, and nothing outside the consumer's repository is
# written. The read-only tail of MAVEN_ARGS stays in effect, so the plugin comes from the
# runner's repositories; the tail view has no org/unlaxer, so org.unlaxer:* comes from Central
# (the checks below fail if it came from anywhere else).
# Usage: check-published-consumer.sh <parser-root> <version>
set -euo pipefail
root=${1:?parser root}
version=${2:?version}
job_repository=${UNLAXER_MAVEN_REPOSITORY:?run .github/actions/maven-job-repository first}
# The host Maven itself uses for Central.
central=https://repo.maven.apache.org/maven2
# Central limits requests per IP ("Your ip has exceeded rate limits", HTTP 429) for minutes, and
# the self-hosted runners share one IP. Only a failure to reach Central is retried, with growing
# delays and a bounded number of attempts; an answer (200, 404, another 4xx, a checksum
# mismatch, a missing artifact) is final.
retry_delays=(20 40 80 160)

# An HTTP status, or "curl exit N", that says Central was not reached or refused for now.
unreachable() {
  case "$1" in
    000|429|500|502|503|504) return 0;;
    # resolve, connect, timeout, TLS connect, empty reply, send and receive failures
    "curl exit 6"|"curl exit 7"|"curl exit 28"|"curl exit 35"|"curl exit 52"|"curl exit 55"|"curl exit 56") return 0;;
    *) return 1;;
  esac
}

# central_request <output file> <url> [curl options...]: prints the final HTTP status.
central_request() {
  local output=$1 url=$2 attempt=0 status
  shift 2
  while :; do
    status=$(curl --silent --show-error --max-time 30 "$@" -o "$output" -w '%{http_code}' "$url") \
      || status="curl exit $?"
    if ! unreachable "$status" || [ "$attempt" -ge "${#retry_delays[@]}" ]; then
      printf '%s' "$status"
      return 0
    fi
    echo "Central did not answer $url ($status); retry $((attempt + 1))/${#retry_delays[@]} in ${retry_delays[$attempt]}s" >&2
    sleep "${retry_delays[$attempt]}"
    attempt=$((attempt + 1))
  done
}

# Maven output that says a remote was not reached, as opposed to an answer from it.
maven_unreachable() {
  ! grep -q 'Checksum validation failed' "$1" &&
    grep -qE 'status code: (429|50[0234])|Too Many Requests|Connect(ion)? timed out|Read timed out|Connection (reset|refused)|UnknownHostException|No route to host|Network is unreachable|Remote host terminated the handshake' "$1"
}

# Only a 404 means "not published"; a network failure or any other status must not pass as one.
status=$(central_request /dev/null "$central/org/unlaxer/unlaxer-common/$version/unlaxer-common-$version.pom" --head)
case "$status" in
  200) ;;
  404)
    echo "org.unlaxer:unlaxer-common:$version is not on Central; no published release to keep apart."
    exit 0;;
  *)
    echo "cannot tell whether org.unlaxer:unlaxer-common:$version is on Central ($central): HTTP $status" >&2
    exit 1;;
esac
consumer=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/published-consumer.XXXXXX")
trap 'rm -rf "$consumer"' EXIT
mkdir "$consumer/repository" "$consumer/project"
for module in unlaxer-common unlaxer-dsl; do
  # No project directory, no project repositories: only the remote given here.
  # -C fails on a checksum mismatch; the explicit -Dmaven.repo.local wins over MAVEN_ARGS.
  attempt=0
  until (cd "$consumer/project" && mvn -B -q -C -Dmaven.repo.local="$consumer/repository" \
      org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get \
      -Dartifact="org.unlaxer:$module:$version" -Dtransitive=false \
      -DremoteRepositories="central::default::$central") > "$consumer/maven.log" 2>&1; do
    cat "$consumer/maven.log" >&2
    if ! maven_unreachable "$consumer/maven.log" || [ "$attempt" -ge "${#retry_delays[@]}" ]; then
      exit 1
    fi
    echo "Maven did not reach Central for $module; retry $((attempt + 1))/${#retry_delays[@]} in ${retry_delays[$attempt]}s" >&2
    sleep "${retry_delays[$attempt]}"
    attempt=$((attempt + 1))
    # Maven caches the failed transfers in the repository; start the attempt from an empty one.
    rm -rf "$consumer/repository"
    mkdir "$consumer/repository"
  done
  cat "$consumer/maven.log"
  relative="org/unlaxer/$module/$version"
  fetched="$consumer/repository/$relative/$module-$version.jar"
  status=$(central_request "$consumer/published.sha1" "$central/$relative/$module-$version.jar.sha1")
  if [ "$status" != 200 ]; then
    echo "$module $version: cannot read the published sha1 from Central: HTTP $status" >&2
    exit 1
  fi
  expected=$(cut -c1-40 "$consumer/published.sha1")
  actual=$(sha1sum "$fetched" | cut -d' ' -f1)
  if [ "$actual" != "$expected" ]; then
    echo "$module $version: consumer resolved $actual, Central publishes $expected" >&2
    exit 1
  fi
  if ! grep -q "^$module-$version.jar>central=" "$consumer/repository/$relative/_remote.repositories"; then
    echo "$module $version: consumer copy was not recorded as resolved from central" >&2
    exit 1
  fi
  if cmp -s "$fetched" "$job_repository/$relative/$module-$version.jar"; then
    relation="identical to"
  else
    relation="differs from"
  fi
  echo "$module $version: published sha1 $actual from Central only; $relation the development jar in $job_repository"
  # The development build in the job repository is still this checkout's jar.
  cmp "$root/$module/target/$module-$version.jar" "$job_repository/$relative/$module-$version.jar"
done
