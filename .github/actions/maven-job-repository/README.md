# Job-local Maven repository (#718)

The self-hosted runners of this repository share one `HOME`, so a plain `mvn install`
of the development build used to land in the shared `~/.m2/repository`. The development
revision has the same coordinates as the published release (`org.unlaxer:*:3.3.0`), so
that copy sat next to the signature files of the Central release and was resolved by any
later build on the machine as if it were the release. Central itself was never changed.

## Contract

- Every job that starts Maven, directly or through a script, uses this action after
  `actions/setup-java` and before its first Maven process.
  `scripts/tests/test_maven_job_repository.py` checks this for all workflows.
- The repository (the *head*) is `$RUNNER_TEMP/maven-repository/<run id>-<run attempt>-<job id>`;
  a job selects exactly one. `select.sh` fails if it already exists, so each job starts with an
  empty head. Every install and download of the job goes there.
- Behind the head, the runner's existing repositories are chained as a read-only *tail*
  (`-Dmaven.repo.local.tail`, Maven 3.9), seen through a job-local view without `org/unlaxer`.
  See [Read-only tail](#read-only-tail).
- It appends `-Dmaven.repo.local=<head> -Dmaven.repo.local.tail=<view>[,<view>]` to `MAVEN_ARGS`
  (read by the `mvn` launcher since Maven 3.9) and writes `MAVEN_ARGS` and
  `UNLAXER_MAVEN_REPOSITORY` (the head) to `GITHUB_ENV`. Later steps, Python/shell scripts and
  nested `mvn` processes inherit it. A step-level `MAVEN_OPTS` (for example the tinyexpression
  LSP smoke) does not replace it.
- Runners 2 and 3 start from a systemd unit that sets
  `MAVEN_ARGS=-Dmaven.repo.local=<runner>/m2/repository`, a per-runner repository that outlives
  the job and also holds development `org.unlaxer:*:3.3.0` installs. The action removes that
  selection from `MAVEN_ARGS` (with a notice) and puts the job head in its place; the runner's
  repository becomes the first tail (read only, without `org/unlaxer`). A
  `-Dmaven.repo.local.tail` already in `MAVEN_ARGS` is replaced as well. Selecting a second
  repository in the same job (`UNLAXER_MAVEN_REPOSITORY` already set) fails.
- The action asks Maven for `settings.localRepository` (`maven-help-plugin:3.5.1:evaluate`, first
  offline and then online) and fails unless Maven reports the head. When a tail holds the plugin,
  it also fails if the plugin jar was downloaded into the head, i.e. if the tail is not in effect.
  If the plugin can be resolved neither way, the action prints Maven's output and fails with the
  reason (Central unreachable for what the tail lacks, or no tail at all).
- In one job, the parser install, tinyexpression and its IDE module all resolve from the same
  repository. `scripts/check-job-maven-repository.sh` additionally requires that the
  repository holds byte-identical copies of the `unlaxer-common` / `unlaxer-dsl` jars the
  checkout just built (downstream smoke, production bridge).
- An explicit `-Dmaven.repo.local=...` on the command line takes precedence over `MAVEN_ARGS`.
  `scripts/check-published-consumer.sh` (downstream smoke) uses that for a consumer of the
  published release: it resolves `unlaxer-common` / `unlaxer-dsl` of the same version into its
  own empty repository from Central only (`-C`, strict checksums), requires Central's `.sha1`
  and a `>central=` record, and reports whether the development jar of the job repository
  differs. The tail of `MAVEN_ARGS` still applies there (the plugin comes from it), but the tail
  view has no `org/unlaxer`, so the release can only come from Central. It skips only when
  `repo.maven.apache.org` answers 404 for that version; any other status fails. Never reuse the job
  repository for such a check, since it contains the development build under the release version.
- Expressions such as `${{ github.job }}` are not used. The action reads the runner's
  environment variables, which every step has.
- The action is a JavaScript action only so that it has a post step (`post.js`, `post-if: always()`):
  composite actions cannot have one.

## settings.xml

The 76 self-hosted runners on the machine share `~/.m2/settings.xml`, and `actions/setup-java`
in other repositories' jobs rewrites it (default `overwrite-settings: true`). Our jobs keep
`overwrite-settings: false`, so they do not rewrite it. They may still read it, but it cannot move
the repository: the `-Dmaven.repo.local` property wins over `<localRepository>`.
`MavenResolutionTest` checks this with real Maven and a settings file that names `~/.m2/repository`.
The release workflow's deploy job writes its credentials to `$RUNNER_TEMP/central-settings`, not to
the shared file.

## Read-only tail

Choice: every job starts from an empty head and reads what it lacks from a read-only tail made of
the runner's repositories. Only what the tail lacks (and `org.unlaxer:*`, see below) is fetched
from Central. Maven runs without `-o`.

Why: from #746 (96f815eb) until this change every job started from an empty repository without a
tail and downloaded all plugins and dependencies from Central. With many jobs in parallel from one
address, Central answered `429 Too Many Requests` and every PR run failed (for example
`select.sh` could not resolve `central-publishing-maven-plugin`, the build job could not fetch
`maven-resources-plugin` poms).

- Sources, in this order, existing directories only, duplicates removed:
  1. the runner's own repository: the last `-Dmaven.repo.local=<path>` in the runner's `MAVEN_ARGS`
     (runners 2 and 3), which `select.sh` takes out of `MAVEN_ARGS`;
  2. `$HOME/.m2/repository` (runner 1 uses only this one; for runners 2 and 3 it is the second
     tail, since their own repositories are much smaller).

  If none exists, there is no tail and the job starts from an empty repository as before.
- View: for each source the action creates `$RUNNER_TEMP/maven-repository/<run id>-<run attempt>-<job id>.tail/<n>`,
  with a symbolic link for every entry of the source except `org/unlaxer` (`org` itself becomes a
  directory of links). Those directories hold development installs of the release version
  (`org.unlaxer:*:3.3.0`, tinyExpression) next to Central signatures, which is why a plain tail of
  the shared repository was rejected before. Through the view, `org.unlaxer:*` comes only from this
  job's own build (the head) or from Central, exactly as with an empty repository.
  A `.` cannot occur in a job id, so the view never has the name of another job's head.
- Read only: Maven's `ChainedLocalRepositoryManager` "directs all the write operations to chain
  head, while uses tail for `find` ... methods only" (Maven Resolver 1.9 API documentation). Installs,
  downloads, `_remote.repositories`, `maven-metadata-local.xml` and `resolver-status.properties`
  all go to the head. `MavenResolutionTest` checks this with real Maven against a tail that is not
  writable.
- Order: the head is asked first; the tail only when the head has no available copy. A development
  build installed into the head therefore wins over any copy in the tail (`MavenResolutionTest`
  installs over an artifact of the tail and resolves the head's bytes).
- `maven.repo.local.tail.ignoreAvailability` keeps its default, `true` (Maven 3.9.9,
  `DefaultRepositorySystemSessionFactory`): a file in the tail is used whatever remote repository
  `_remote.repositories` names. With `false`, a tail file would be used only when its recorded
  repository id is one of the current request's repositories (or it was installed locally), and the
  misses would go to Central again. Release artifacts on Central are immutable, the locally installed
  `org.unlaxer` copies are outside the view, so `true` resolves the same bytes with fewer requests.
- Metadata: version ranges, plugin prefixes and plugin versions not fixed in a POM still make Maven
  check remote metadata once per head, since the head has no `resolver-status.properties` yet. A
  `429` there is a warning, and Maven falls back to the metadata in the tail.
- Not a copy of the shared `~/.m2` (multi-GB per job), not `cache: maven` of `actions/setup-java`
  nor `actions/cache` (they upload the repository, which the workflows prohibit).
- Cleanup: `post.js` prints the size and removes this job's head and its tail view at the end of the
  job, also after a failure or cancellation. It removes only directories directly below
  `$RUNNER_TEMP/maven-repository` whose name starts with this run id and attempt. The view holds
  symbolic links; recursive removal unlinks them and never enters their targets. The runner also
  empties `RUNNER_TEMP` between jobs, so a runner that crashed leaves them only until its next job.
- Parallel jobs: each runner has its own `RUNNER_TEMP` and runs one job at a time, and the path
  includes the run, attempt and job. Parallel jobs therefore never share or overwrite a head. They
  may read the same tail, which Maven never writes.
- Nothing in the runner's repository or the shared `~/.m2` is written, repaired or deleted. Copies
  that earlier runs installed there remain until someone removes them deliberately.

## Scripts

`run-tiny-classic-benchmark.py --maven-repo` and `check-tiny-production.py --maven-repo` receive
the same path in CI. `check-tiny-production.py` refuses to run unless `--maven-repo` or the last
`-Dmaven.repo.local` of `MAVEN_ARGS` selects a repository outside `~/.m2`. This holds for
`--skip-build` too: it still resolves dependency trees and classpaths, which must come from the
repository of the earlier build.
Local reproduction keeps using its own `mktemp -d` repository (`docs/production-quality.md`).
The local `scripts/release-central.sh` is not a CI job and is unchanged: its `deploy` installs the
bytes it publishes.
