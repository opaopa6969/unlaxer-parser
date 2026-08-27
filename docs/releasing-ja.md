# unlaxer-parser のリリース手順

[English](./releasing.md)

リリースチャネルは3つ: Maven Central(ライブラリ)、GitHub Actions artifact(ビルド毎の VSIX)、GitHub Release(タグ付き VSIX)。

## 1. Maven Central (unlaxer-common / unlaxer-dsl)

`org.unlaxer` 全体で **UTC月1回** のrelease trainを使う。正本の台帳と方針は
[`release/central-release-queue.yml`](../release/central-release-queue.yml) と
[`release/README.md`](../release/README.md)。VSIX・文書だけの変更はCentralへ出さない。

```bash
# 1. バージョンを上げる — 変更箇所は1つだけ
#    pom.xml: <revision>X.Y.Z</revision>

# 2. CHANGELOG の "Unreleased" を "[X.Y.Z] - YYYY-MM-DD" に確定

# 3. commit & push して CI green を確認 (downstream smoke ジョブ含む)

# 4. 当月のorganization全体の利用量を確認（read-only）
scripts/release-central.sh

# 5. 空きがある場合だけ、表示された当月の確認文字列で公開
scripts/release-central.sh --execute --confirm org.unlaxer/YYYY-MM
```

注意:

- **`-pl .` を絶対に省かない** — 子 POM は親 POM を参照しており、3.0.0〜3.0.2 期の publish は親を含めなかったため利用側で親が解決不能だった。
- POMは`skipPublishing=true`が既定。直接の`mvn deploy`はuploadせず、必ず月次スクリプトを使う。
- スクリプトはcleanな`master`、`origin/master`との一致、未公開version、月次枠を検証する。
- 緊急時は`--emergency --reason "..."`と、`EMERGENCY:`付き確認文字列が必要。
- `central-publishing-maven-plugin` が validate 後に自動公開。`repo1.maven.org` への同期は validated から **15〜60分**。
- CHANGELOG を書いたら **同じ作業内で publish まで行う**。3.0.2 は「記載のみで未公開」となり downstream の CI を数週間壊した (#27, #35)。
- 確認: `curl -s https://repo1.maven.org/maven2/org/unlaxer/unlaxer-common/maven-metadata.xml | grep latest`

## 2. UBNF VSIX — ビルド毎の artifact

`master` への push ごとに `UBNF VSIX (ubnf-vscode)` ジョブが拡張をビルドし、
Actions artifact として添付する:

**Actions タブ → 最新 run → Artifacts → `ubnf-lsp-vsix`**

artifact は保持期限 (既定90日) で消える。残したい/配布したい場合はタグ付き
Release を使うこと。

ローカルビルド: `mvn -pl unlaxer-dsl/ubnf-vscode verify` → `unlaxer-dsl/ubnf-vscode/target/*.vsix`
(Node.js 必須。`package.json` の `repository` フィールドを消すと `vsce` が fail する)

## 3. UBNF VSIX — GitHub Release (恒久)

VSIX releaseはCentralの月次枠とは独立しており、必要なときに公開できる。
VSIX・walkthrough・文書だけの変更ではMavenの`revision`を上げたりCentralへdeployしない。

`v*` タグを push すると `.github/workflows/release-vsix.yml` が発動し、VSIX を
ビルドして Release を自動作成 (リリースノート自動生成) し、恒久添付する:

```bash
git tag -a vsix-vX.Y.Z -m "UBNF VSIX X.Y.Z"
git push origin vsix-vX.Y.Z
# → https://github.com/opaopa6969/unlaxer-parser/releases/tag/vsix-vX.Y.Z
```

既存タグで再実行すると asset は上書きされる (`--clobber`) ので、ワークフローが
失敗したら re-run すればよい。

利用者のインストール: Release ページから `.vsix` をダウンロード →
VS Code の **Extensions: Install from VSIX...**。

## リリースチェックリスト

- [ ] `pom.xml` の `<revision>` を bump
- [ ] CHANGELOG のセクションを日付付きで確定 ("Unreleased" のまま暗黙に出荷しない)
- [ ] `master` の CI green (build + 静的解析 + downstream smoke + VSIX)
- [ ] release queueで今月の対象に選択済み
- [ ] `scripts/release-central.sh` で当月枠を確認
- [ ] guarded scriptの`--execute --confirm ...`で公開
- [ ] repo1 同期確認
- [ ] 必要なら`git tag vsix-vX.Y.Z && git push origin vsix-vX.Y.Z` (VSIXのみ)
- [ ] downstream 関連 issue のクローズ、breaking なら downstream へ周知
