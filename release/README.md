# org.unlaxer Central release train

This directory is the canonical release queue for Maven Central publications
owned by the `unlaxer` organization. It is deliberately separate from VSIX and
documentation releases.

## Policy

- Publish frequency is not a constraint (owner decision 2026-09-24). What gates a
  publish is readiness: green CI, changelog, version bump, and the release checklist issue.
- Collect changes in GitHub/CI and select one reactor at a time from
  `central-release-queue.yml`; use its configured monthly cap.
- Publish dependency reactors before downstream consumers. Each publication
  must independently meet the readiness checklist and configured budget.
- GitHub Actions artifacts and GitHub Release VSIX files do not consume this
  slot. Do not publish a Maven version for VSIX- or documentation-only changes.
- Emergency publication is allowed only for security, data-loss, or critical
  compatibility fixes. It requires an explicit reason and confirmation string.
- Do not publish snapshots to Central; use local Maven install and CI artifacts.

## Human and agent workflow

1. Add or update the candidate in `central-release-queue.yml`.
2. Finish the version bump and changelog in the candidate repository.
3. Commit, push, merge, and wait for green CI.
4. From a clean `master` exactly matching `origin/master`, run
   `scripts/release-central.sh` without arguments. This is a read-only report.
5. If the slot is available, execute the confirmation command printed by the
   script. Never call `mvn deploy -DskipPublishing=false` directly.
6. Update `lastObserved` and clear/select the next candidate in the queue.

The guard derives organization-wide operations from public Maven Central
directory timestamps and fails closed when it cannot read them. Reactor modules
published in the same UTC minute are treated as one publish operation. The
Central Usage Center remains authoritative if its signed-in value differs.

Direct `mvn deploy` is safe by default: both participating POMs set
`skipPublishing=true`. Only the guarded release script opts into upload.

## Publishing from GitHub Actions

Use the guarded local script when a dedicated Maven Central settings file and
signing key are available. The optional `.github/workflows/release-central.yml`
(`workflow_dispatch`) provides a credentialed Actions alternative:

- **Required repo secrets** (environment `release`): `MAVEN_CENTRAL_USERNAME`,
  `MAVEN_CENTRAL_PASSWORD`, `MAVEN_GPG_PRIVATE_KEY`, `MAVEN_GPG_PASSPHRASE`.
  The `guard` job needs none of them; the `publish` job fails fast with
  `test -n` if any is missing.
- **Trigger a dry run** (guard report only, no upload):
  ```
  gh workflow run release-central.yml -f version=3.1.0 -f confirm=org.unlaxer/2026-09 -f dry_run=true
  ```
- **Trigger a real publish** once the guard has capacity:
  ```
  gh workflow run release-central.yml -f version=3.1.0 -f confirm=org.unlaxer/2026-09 -f dry_run=false
  ```
  `version` must equal the pom `revision` exactly and `confirm` must equal
  `org.unlaxer/YYYY-MM` for the current UTC month, same as the local script.
- The `publish` job skips the deploy when the version is already present on
  Central (tagging always runs, and is skipped only if the tag itself already
  exists), and always skips tests during `deploy` (`-DskipTests`) because
  merges to `master` are already gated by `.github/workflows/maven.yml`.
- `scripts/release-central.sh` remains the local alternative for anyone who
  does have Central credentials configured.

## Dedicated self-hosted runner

CI and release workflows target `[unlaxer-parser-ci, Linux, X64]` on this
repository's self-hosted runner. Register it with `--no-default-labels` and
`--labels unlaxer-parser-ci,Linux,X64`; the generic `self-hosted` label is
intentionally absent so historical queued workflows are not picked up.
This does not change or reuse TinyExpression or ubnfc runners.

Use a separate installation/work directory and service. Keep registration
tokens out of logs and source control. Follow the [GitHub runner setup guide](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/add-runners).
Only trusted workflow code should run on a machine with access to local data.
CI does not overwrite existing Maven settings or the user's default Rust
toolchain. Central credentials use `settings-central.xml` (or `MAVEN_SETTINGS`),
not the shared default Maven settings file.
Maven dependencies persist in the runner's local repository. Do not enable the
remote `setup-java` Maven cache here: restoring the multi-GB archive on every
job duplicates that local cache and delays verification without adding coverage.
