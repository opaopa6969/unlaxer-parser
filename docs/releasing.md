# Releasing unlaxer-parser

[日本語版](./releasing-ja.md)

This page covers the three release channels: Maven Central (libraries), GitHub Actions artifacts (per-build VSIX), and GitHub Releases (tagged VSIX).

## 1. Maven Central (unlaxer-common / unlaxer-dsl)

```bash
# 1. Bump the version — one place only
#    pom.xml: <revision>X.Y.Z</revision>

# 2. Move the CHANGELOG "Unreleased" section to "[X.Y.Z] - YYYY-MM-DD"

# 3. Commit, push, and confirm CI is green (including the downstream smoke job)

# 4. Deploy — ALWAYS include the parent POM (-pl .):
mvn -B -pl .,unlaxer-common,unlaxer-dsl clean deploy
```

Notes:

- **Never skip `-pl .`** — the child POMs reference the parent POM, and 3.0.0–3.0.2 era publishes omitted it, leaving consumers with unresolvable parents.
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

Pushing a `v*` tag triggers `.github/workflows/release-vsix.yml`, which builds
the VSIX, creates a GitHub Release with auto-generated notes, and attaches the
file permanently:

```bash
git tag -a vX.Y.Z -m "unlaxer-parser X.Y.Z"
git push origin vX.Y.Z
# → https://github.com/opaopa6969/unlaxer-parser/releases/tag/vX.Y.Z
```

Re-running on an existing tag overwrites the asset (`--clobber`), so a failed
workflow can simply be re-run.

Installation for users: download the `.vsix` from the Release page, then in
VS Code run **Extensions: Install from VSIX...**.

## Release checklist

- [ ] `pom.xml` `<revision>` bumped
- [ ] CHANGELOG section dated (no stale "Unreleased" content shipping silently)
- [ ] CI green on `master` (build + static analysis + downstream smoke + VSIX)
- [ ] `mvn -B -pl .,unlaxer-common,unlaxer-dsl clean deploy`
- [ ] repo1 sync confirmed
- [ ] `git tag vX.Y.Z && git push origin vX.Y.Z` (Release + VSIX)
- [ ] Close downstream-facing issues and notify downstream projects if breaking
