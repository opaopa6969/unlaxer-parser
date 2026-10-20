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
- The repository is `$RUNNER_TEMP/maven-repository/<run id>-<run attempt>-<job id>[-<name>]`.
  The action fails if it already exists, so each job starts with an empty repository.
- It appends `-Dmaven.repo.local=<path>` to `MAVEN_ARGS` (read by the `mvn` launcher since
  Maven 3.9) and writes `MAVEN_ARGS` and `UNLAXER_MAVEN_REPOSITORY` to `GITHUB_ENV`.
  Later steps, Python/shell scripts and nested `mvn` processes inherit it.
  A step-level `MAVEN_OPTS` (for example the tinyexpression LSP smoke) does not replace it.
- The action asks Maven for `settings.localRepository` (`maven-help-plugin:3.5.1:evaluate`)
  and fails unless Maven reports the new path.
- In one job, the parser install, tinyexpression and its IDE module all resolve from the same
  repository. `scripts/check-job-maven-repository.sh` additionally requires that the
  repository holds byte-identical copies of the `unlaxer-common` / `unlaxer-dsl` jars the
  checkout just built (downstream smoke, production bridge).
- An explicit `-Dmaven.repo.local=...` on the command line takes precedence over `MAVEN_ARGS`.
  Use that for a check that must fetch only the published Central artifacts into its own
  empty repository; never reuse the job repository there, since it contains the development
  build under the release version.
- Expressions such as `${{ github.job }}` are not used. The action reads the runner's
  environment variables, which every step has.

## Cache, size and cleanup

- No workflow uses the `cache: maven` option of `actions/setup-java`, and none should:
  that option saves and restores `~/.m2/repository`, which jobs no longer use, and uploading
  a multi-GB repository is what the workflows already prohibit.
  `overwrite-settings: false` stays, so the runners' shared `~/.m2/settings.xml` is not rewritten.
  It may still be read, but it cannot choose the repository, because the command-line property wins.
- Each job therefore downloads its plugins and dependencies from Central. This adds network
  time per job but does not depend on whatever another job or person left in the shared cache.
- The runner empties `RUNNER_TEMP` at the beginning and end of every job, so the
  repository is removed with the job; a runner that crashed leaves it only until its next job.
  Each runner has its own `RUNNER_TEMP` and runs one job at a time, and the path includes the
  run, attempt and job. Parallel jobs therefore never share or overwrite a repository.
- Nothing in the shared `~/.m2` is read for resolution, written, repaired or deleted. Copies that
  earlier runs installed there remain until someone removes them deliberately.

`run-tiny-classic-benchmark.py --maven-repo` and `check-tiny-production.py --maven-repo` receive
the same path in CI. Local reproduction keeps using its own `mktemp -d` repository
(`docs/production-quality.md`). The local `scripts/release-central.sh` is not a CI job and is unchanged.
