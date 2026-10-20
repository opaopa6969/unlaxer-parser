#!/usr/bin/env bash
# #718: require that the job-local Maven repository holds the jars this checkout just built,
# so downstream consumers in the same job resolve these bytes and not a shared-cache copy.
# Usage: check-job-maven-repository.sh <parser-root> <version>
set -euo pipefail
root=${1:?parser root}
version=${2:?version}
repository=${UNLAXER_MAVEN_REPOSITORY:?run .github/actions/maven-job-repository first}
case "$repository/" in
  "$HOME/.m2/"*) echo "job repository must not be the shared ~/.m2: $repository" >&2; exit 1;;
esac
resolved="$(cd "$root" && mvn -B -q -N org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate \
  -Dexpression=settings.localRepository -DforceStdout)"
if [ "$resolved" != "$repository" ]; then
  echo "Maven resolves '$resolved', expected the job repository '$repository'" >&2
  exit 1
fi
for module in unlaxer-common unlaxer-dsl; do
  built="$root/$module/target/$module-$version.jar"
  installed="$repository/org/unlaxer/$module/$version/$module-$version.jar"
  cmp "$built" "$installed"
  echo "$module $version: $(sha256sum "$installed" | cut -d' ' -f1) in $installed"
done
