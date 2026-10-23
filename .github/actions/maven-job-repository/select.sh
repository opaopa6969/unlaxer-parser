#!/usr/bin/env bash
# #718: create this job's empty Maven repository and route every later Maven process to it.
# The runner's existing repositories are chained behind it as a read-only tail, seen through a
# job-local view without org/unlaxer. Run by main.js; post.js removes both when the job ends.
set -euo pipefail
# Shell variables, not expressions: github.job is not available everywhere.
for variable in RUNNER_TEMP GITHUB_RUN_ID GITHUB_RUN_ATTEMPT GITHUB_JOB GITHUB_ENV GITHUB_OUTPUT GITHUB_STATE; do
  if [ -z "${!variable:-}" ]; then
    echo "::error::$variable is not set; cannot create a job-local Maven repository" >&2
    exit 1
  fi
done
if [ -n "${UNLAXER_MAVEN_REPOSITORY:-}" ]; then
  echo "::error::this job already selected a Maven repository: $UNLAXER_MAVEN_REPOSITORY" >&2
  exit 1
fi
# A runner service may select its own persistent repository (runners 2 and 3 set
# MAVEN_ARGS=-Dmaven.repo.local=<runner>/m2/repository). That one outlives the job, so it is
# no longer written; the last one (the one Maven would use) becomes the first tail instead.
# The mvn launcher word-splits MAVEN_ARGS, so splitting here loses nothing.
kept=()
runner_repository=
read -ra words <<< "${MAVEN_ARGS:-}"
for argument in "${words[@]}"; do
  case "$argument" in
    -Dmaven.repo.local=*)
      runner_repository=${argument#-Dmaven.repo.local=}
      echo "::notice::the runner's Maven repository $runner_repository is read-only for this job";;
    -Dmaven.repo.local.tail=*|-Dmaven.repo.local.tail.ignoreAvailability=*)
      echo "::notice::replacing the runner's $argument for this job";;
    *) kept+=("$argument");;
  esac
done
MAVEN_ARGS="${kept[*]}"
base="$RUNNER_TEMP/maven-repository/$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT-$GITHUB_JOB"
repository=$base
# '.' cannot occur in a job id, so no other job's repository has this name.
tail_view=$base.tail
# The mvn launcher word-splits MAVEN_ARGS, and Maven splits the tail at commas.
if [[ "$base" =~ [[:space:],] ]]; then
  echo "::error::Maven repository path contains whitespace or a comma: $base" >&2
  exit 1
fi
mkdir -p "$(dirname "$repository")"
# Fails if it exists: the repository must start empty in this job.
mkdir "$repository"
# From here on the post step owns the directory, even if the check below fails.
echo "repository=$repository" >> "$GITHUB_STATE"

# Read-only tail: the runner's repository, then the shared ~/.m2 (existing directories only).
# Maven writes only to the head (ChainedLocalRepositoryManager directs all writes there), so
# installs and downloads of this job stay in $repository.
sources=()
for candidate in "$runner_repository" "$HOME/.m2/repository"; do
  [ -n "$candidate" ] && [ -d "$candidate" ] || continue
  candidate=$(cd "$candidate" && pwd -P)
  for seen in "${sources[@]}"; do [ "$seen" = "$candidate" ] && continue 2; done
  sources+=("$candidate")
done
maven_args="${MAVEN_ARGS:+$MAVEN_ARGS }-Dmaven.repo.local=$repository"
tails=()
if [ ${#sources[@]} -gt 0 ]; then
  mkdir "$tail_view"
  echo "tail=$tail_view" >> "$GITHUB_STATE"
  shopt -s nullglob dotglob
  index=0
  for source in "${sources[@]}"; do
    index=$((index + 1))
    view="$tail_view/$index"
    mkdir "$view"
    # Links to everything except org/unlaxer: those directories hold development installs of
    # the release version (org.unlaxer:*:3.3.0, tinyExpression) next to Central signatures.
    # This job's org.unlaxer artifacts come from its own build (head) or from Central.
    for entry in "$source"/*; do
      name=${entry##*/}
      if [ "$name" = org ] && [ -d "$entry" ]; then
        mkdir "$view/org"
        for child in "$entry"/*; do
          [ "${child##*/}" = unlaxer ] || ln -s "$child" "$view/org/${child##*/}"
        done
      else
        ln -s "$entry" "$view/$name"
      fi
    done
    tails+=("$view")
    echo "Read-only tail $view: $source without org/unlaxer"
  done
  shopt -u nullglob dotglob
  tail_list=$(IFS=,; echo "${tails[*]}")
  maven_args="$maven_args -Dmaven.repo.local.tail=$tail_list"
  failure="artifacts missing from the read-only tail ($tail_list) are fetched from Central (repo.maven.apache.org), which must be reachable"
else
  echo "::notice::no runner Maven repository exists; this job starts from an empty repository without a tail"
  failure="there is no tail, so everything comes from Central (repo.maven.apache.org), which must be reachable, and offline mode (-o) cannot work"
fi
# Evaluate what Maven itself resolves, not only the variable we set. Offline first: with the
# plugin in the tail, that needs no request at all (an online run would still ask Central for
# the plugin prefix and version metadata, which the new head has never checked).
# -q prints Maven's [ERROR] lines on stdout, so show them when the evaluation fails.
evaluate() {
  MAVEN_ARGS="$maven_args" mvn -B -q -N "$@" org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate \
    -Dexpression=settings.localRepository -DforceStdout
}
if ! resolved="$(evaluate -o)" && ! resolved="$(evaluate)"; then
  printf '%s\n' "$resolved" >&2
  echo "::error::Maven could not resolve maven-help-plugin for the job repository $repository; $failure" >&2
  exit 1
fi
if [ "$resolved" != "$repository" ]; then
  echo "::error::Maven resolves local repository '$resolved', expected '$repository'" >&2
  exit 1
fi
# The tail is in effect: a plugin jar the tail has is not downloaded into the head again.
help_jar=org/apache/maven/plugins/maven-help-plugin/3.5.1/maven-help-plugin-3.5.1.jar
for view in "${tails[@]}"; do
  if [ -f "$view/$help_jar" ]; then
    if [ -e "$repository/$help_jar" ]; then
      echo "::error::Maven downloaded $help_jar into $repository although the tail $view has it; the tail is not in effect" >&2
      exit 1
    fi
    break
  fi
done
{
  echo "MAVEN_ARGS=$maven_args"
  echo "UNLAXER_MAVEN_REPOSITORY=$repository"
} >> "$GITHUB_ENV"
echo "path=$repository" >> "$GITHUB_OUTPUT"
echo "Maven local repository for this job: $repository"
