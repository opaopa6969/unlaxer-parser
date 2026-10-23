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
case " ${MAVEN_ARGS:-} " in
  *" -Dmaven.repo.local="*)
    echo "::error::MAVEN_ARGS already selects a Maven repository: $MAVEN_ARGS" >&2
    exit 1;;
esac
repository="$RUNNER_TEMP/maven-repository/$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT-$GITHUB_JOB${REPOSITORY_SUFFIX:+-$REPOSITORY_SUFFIX}"
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
resolved="$(MAVEN_ARGS="$maven_args" mvn -B -q -N \
  org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate \
  -Dexpression=settings.localRepository -DforceStdout)"
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
