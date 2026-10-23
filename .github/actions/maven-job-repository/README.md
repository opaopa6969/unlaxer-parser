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
  `select.sh` fails if it already exists, so each job starts with an empty repository.
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
  `scripts/check-published-consumer.sh` (downstream smoke) uses that for a consumer of the
  published release: it resolves `unlaxer-common` / `unlaxer-dsl` of the same version into its
  own empty repository from Central only (`-C`, strict checksums), requires Central's `.sha1`,
  and reports whether the development jar of the job repository differs. Never reuse the job
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

## Empty repository, cache, size and cleanup

Choice: every job starts from an empty repository and resolves online from Central. Maven runs
without `-o`.

- Not a copy or a read-only tail (`maven.repo.local.tail`) of the shared `~/.m2`: it already holds
  development copies of `org.unlaxer:*:3.3.0` (and tinyexpression 2.0.1) next to Central signatures,
  and a seed or tail would resolve exactly those. Copying a multi-GB directory per job would also
  take longer than the download.
- Not `cache: maven` of `actions/setup-java` nor `actions/cache`: they save and restore a remote copy
  of the repository, and uploading a multi-GB repository is what the workflows already prohibit.
- Not `-o`: an empty repository cannot resolve offline.
- Cost: each job downloads the plugins and dependencies it uses. Measured locally from an empty
  repository, the downstream install (`-pl .,unlaxer-common,unlaxer-dsl install -DskipTests`) took
  54 s and left 81 MB. The CI run of #718 shows the per-job figures.
- Cleanup: `post.js` prints the size and removes this job's repository at the end of the job, also
  after a failure or cancellation. It removes only a directory directly below
  `$RUNNER_TEMP/maven-repository` whose name starts with this run id and attempt. The runner also
  empties `RUNNER_TEMP` between jobs, so a runner that crashed leaves it only until its next job.
- Parallel jobs: each runner has its own `RUNNER_TEMP` and runs one job at a time, and the path
  includes the run, attempt and job. Parallel jobs therefore never share or overwrite a repository.
- Nothing in the shared `~/.m2` is read for resolution, written, repaired or deleted. Copies that
  earlier runs installed there remain until someone removes them deliberately.

## Scripts

`run-tiny-classic-benchmark.py --maven-repo` and `check-tiny-production.py --maven-repo` receive
the same path in CI. `check-tiny-production.py` refuses to install unless `--maven-repo` or
`MAVEN_ARGS` selects a repository outside `~/.m2` (`--skip-build` installs nothing).
Local reproduction keeps using its own `mktemp -d` repository (`docs/production-quality.md`).
The local `scripts/release-central.sh` is not a CI job and is unchanged: its `deploy` installs the
bytes it publishes.
