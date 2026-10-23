#!/usr/bin/env bash
# #718: create this job's empty Maven repository and route every later Maven process to it.
# Run by main.js; post.js removes the repository when the job ends.
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
# MAVEN_ARGS=-Dmaven.repo.local=<runner>/m2/repository). That one outlives the job, so
# replace it. The mvn launcher word-splits MAVEN_ARGS, so splitting here loses nothing.
kept=()
read -ra words <<< "${MAVEN_ARGS:-}"
for argument in "${words[@]}"; do
  case "$argument" in
    -Dmaven.repo.local=*) echo "::notice::replacing the runner's Maven repository ${argument#-Dmaven.repo.local=} for this job";;
    *) kept+=("$argument");;
  esac
done
MAVEN_ARGS="${kept[*]}"
repository="$RUNNER_TEMP/maven-repository/$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT-$GITHUB_JOB"
# The mvn launcher word-splits MAVEN_ARGS.
if [[ "$repository" =~ [[:space:]] ]]; then
  echo "::error::Maven repository path contains whitespace: $repository" >&2
  exit 1
fi
mkdir -p "$(dirname "$repository")"
# Fails if it exists: the repository must start empty in this job.
mkdir "$repository"
# From here on the post step owns the directory, even if the check below fails.
echo "repository=$repository" >> "$GITHUB_STATE"
maven_args="${MAVEN_ARGS:+$MAVEN_ARGS }-Dmaven.repo.local=$repository"
# Evaluate what Maven itself resolves, not only the variable we set.
# -q prints Maven's [ERROR] lines on stdout, so show them when the evaluation fails.
if ! resolved="$(MAVEN_ARGS="$maven_args" mvn -B -q -N \
    org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate \
    -Dexpression=settings.localRepository -DforceStdout)"; then
  printf '%s\n' "$resolved" >&2
  echo "::error::Maven could not resolve maven-help-plugin into the empty job repository $repository; Central (repo.maven.apache.org) must be reachable, and offline mode (-o) cannot work from an empty repository" >&2
  exit 1
fi
if [ "$resolved" != "$repository" ]; then
  echo "::error::Maven resolves local repository '$resolved', expected '$repository'" >&2
  exit 1
fi
{
  echo "MAVEN_ARGS=$maven_args"
  echo "UNLAXER_MAVEN_REPOSITORY=$repository"
} >> "$GITHUB_ENV"
echo "path=$repository" >> "$GITHUB_OUTPUT"
echo "Maven local repository for this job: $repository"
