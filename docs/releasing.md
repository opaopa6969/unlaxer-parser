# Releasing unlaxer-parser

[日本語版](./releasing-ja.md)

This page covers the three release channels: Maven Central (libraries), GitHub Actions artifacts (per-build VSIX), and GitHub Releases (tagged VSIX).

## 1. Maven Central (unlaxer-common / unlaxer-dsl)

Central publications use one organization-wide release train slot per UTC
calendar month. The canonical policy and queue are
[`release/README.md`](../release/README.md) and
[`release/central-release-queue.yml`](../release/central-release-queue.yml).
VSIX- or documentation-only changes do not use Central.

```bash
# 1. Bump the version — one place only
#    pom.xml: <revision>X.Y.Z</revision>

# 2. Move the CHANGELOG "Unreleased" section to "[X.Y.Z] - YYYY-MM-DD"

# 3. Commit, push, and confirm CI is green (including the downstream smoke job)

# 4. Read-only organization usage report
scripts/release-central.sh

# 5. Publish only when the monthly slot is available
scripts/release-central.sh --execute --confirm org.unlaxer/YYYY-MM
```

Notes:

- **Never skip `-pl .`** — the child POMs reference the parent POM, and 3.0.0–3.0.2 era publishes omitted it, leaving consumers with unresolvable parents.
- The POM defaults to `skipPublishing=true`. A direct `mvn deploy` does not upload; use the guarded script.
- The script requires a clean `master` matching `origin/master`, an unpublished release version, and available organization capacity.
- `central-publishing-maven-plugin` validates and auto-publishes; sync to `repo1.maven.org` takes **15–60 minutes** after "validated".
- Do this **in the same sitting** as writing the CHANGELOG entry. 3.0.2 was documented but never published, which broke downstream CI for weeks (#27, #35).
- Verify: `curl -s https://repo1.maven.org/maven2/org/unlaxer/unlaxer-common/maven-metadata.xml | grep latest`

## 2. UBNF VSIX — per-build artifact

Every push to `master` runs the `UBNF VSIX (ubnf-vscode)` job, which builds the
extension and attaches it as an Actions artifact:

**Actions tab → latest run → Artifacts → `ubnf-lsp-vsix`**

Artifacts expire with the repository retention policy (default 90 days). Use a
tagged release for anything you want to keep or share.

Local build: `mvn -pl unlaxer-dsl/ubnf-vscode verify` → `unlaxer-dsl/ubnf-vscode/target/*.vsix`
(requires Node.js; `package.json` must keep its `repository` field or `vsce` fails).

## 3. UBNF VSIX — GitHub Release (permanent)

VSIX releases are independent of the Central monthly slot and may ship when
needed. VSIX-, walkthrough-, or documentation-only changes do not bump the
Maven `revision` and do not trigger a Central publication.

Pushing a `v*` tag triggers `.github/workflows/release-vsix.yml`, which builds
the VSIX, creates a GitHub Release with auto-generated notes, and attaches the
file permanently:

```bash
git tag -a vsix-vX.Y.Z -m "UBNF VSIX X.Y.Z"
git push origin vsix-vX.Y.Z
# → https://github.com/opaopa6969/unlaxer-parser/releases/tag/vsix-vX.Y.Z
```

Re-running on an existing tag overwrites the asset (`--clobber`), so a failed
workflow can simply be re-run.

Installation for users: download the `.vsix` from the Release page, then in
VS Code run **Extensions: Install from VSIX...**.

## Release checklist

- [ ] `pom.xml` `<revision>` bumped
- [ ] CHANGELOG section dated (no stale "Unreleased" content shipping silently)
- [ ] CI green on `master` (build + static analysis + downstream smoke + VSIX)
- [ ] selected in the canonical Central release queue
- [ ] monthly slot checked with `scripts/release-central.sh`
- [ ] published through the guarded `--execute --confirm ...` path
- [ ] repo1 sync confirmed
- [ ] if needed, `git tag vsix-vX.Y.Z && git push origin vsix-vX.Y.Z` (VSIX only)
- [ ] Close downstream-facing issues and notify downstream projects if breaking
