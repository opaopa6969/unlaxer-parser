#!/usr/bin/env bash
# #718: a consumer of the published release resolves it from Central alone, into its own
# empty repository, while this job's repository holds the development build of the same
# version. Neither the job repository nor the shared ~/.m2 is read or written here.
# Usage: check-published-consumer.sh <parser-root> <version>
set -euo pipefail
root=${1:?parser root}
version=${2:?version}
job_repository=${UNLAXER_MAVEN_REPOSITORY:?run .github/actions/maven-job-repository first}
# The host Maven itself uses for Central.
central=https://repo.maven.apache.org/maven2
# Only a 404 means "not published"; a network failure or any other status must not pass as one.
status=$(curl --silent --show-error --head --retry 3 --max-time 30 -o /dev/null -w '%{http_code}' \
  "$central/org/unlaxer/unlaxer-common/$version/unlaxer-common-$version.pom") || status="curl exit $?"
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
  (cd "$consumer/project" && mvn -B -q -C -Dmaven.repo.local="$consumer/repository" \
    org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get \
    -Dartifact="org.unlaxer:$module:$version" -Dtransitive=false \
    -DremoteRepositories="central::default::$central")
  relative="org/unlaxer/$module/$version"
  fetched="$consumer/repository/$relative/$module-$version.jar"
  expected=$(curl --fail --silent --show-error --retry 3 --max-time 30 "$central/$relative/$module-$version.jar.sha1" | cut -c1-40)
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
