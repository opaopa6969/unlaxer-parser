#!/usr/bin/env bash
set -euo pipefail

usage() {
  sed -n '2,30p' "$0" | sed -n 's/^# //p'
}

# Publish unlaxer-parser through the guarded org.unlaxer monthly release train.
#
# Dry-run/report (default):
#   scripts/release-central.sh
#
# Monthly release:
#   scripts/release-central.sh --execute --confirm org.unlaxer/YYYY-MM
#
# Emergency release after the monthly slot is consumed:
#   scripts/release-central.sh --execute --emergency \
#     --reason "critical compatibility fix" \
#     --confirm EMERGENCY:org.unlaxer/YYYY-MM

execute=false
emergency=false
confirmation=
reason=

while (($# > 0)); do
  case "$1" in
    --execute) execute=true ;;
    --emergency) emergency=true ;;
    --confirm)
      shift
      confirmation=${1:-}
      ;;
    --reason)
      shift
      reason=${1:-}
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"
month=$(date -u +%Y-%m)
guard=(python3 scripts/central_release_guard.py --month "$month")

"${guard[@]}"

if ! $execute; then
  echo
  echo "Dry-run only. No artifact was uploaded."
  echo "Queue: release/central-release-queue.yml"
  echo "Monthly confirmation: org.unlaxer/$month"
  exit 0
fi

expected_confirmation="org.unlaxer/$month"
if $emergency; then
  expected_confirmation="EMERGENCY:$expected_confirmation"
  if [[ -z "$reason" ]]; then
    echo "Emergency releases require --reason." >&2
    exit 2
  fi
fi
if [[ "$confirmation" != "$expected_confirmation" ]]; then
  echo "Refusing publish: pass --confirm $expected_confirmation" >&2
  exit 2
fi

if [[ -n $(git status --porcelain) ]]; then
  echo "Refusing publish: worktree is not clean." >&2
  exit 2
fi
branch=$(git symbolic-ref --quiet --short HEAD || true)
if [[ "$branch" != "master" ]]; then
  echo "Refusing publish: release must run from master, not ${branch:-detached HEAD}." >&2
  exit 2
fi
git fetch --quiet origin master
if [[ $(git rev-parse HEAD) != $(git rev-parse origin/master) ]]; then
  echo "Refusing publish: local master and origin/master differ." >&2
  exit 2
fi

version=$(mvn -q help:evaluate -Dexpression=revision -DforceStdout)
if [[ -z "$version" || "$version" == *SNAPSHOT* ]]; then
  echo "Refusing publish: revision must be a non-SNAPSHOT version." >&2
  exit 2
fi
published_url="https://repo1.maven.org/maven2/org/unlaxer/unlaxer-parser/$version/"
if curl -fsI "$published_url" >/dev/null; then
  echo "Refusing publish: org.unlaxer:unlaxer-parser:$version already exists." >&2
  exit 2
fi

if ! $emergency; then
  "${guard[@]}" --max-releases 1
else
  echo "Emergency override: $reason"
fi

echo "Publishing org.unlaxer unlaxer-parser reactor version $version"
mvn -B -pl .,unlaxer-common,unlaxer-dsl clean deploy -DskipPublishing=false
