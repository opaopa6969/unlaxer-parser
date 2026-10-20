# property-based 差分テストの固定 corpus（#705）

`PropertyBasedParityTest` がランダム文法の前に毎回再生する。仕組みと再現手順は
[docs/property-based-testing.md](../../docs/property-based-testing.md)。

- `regressions.json`: 縮小した再現例と、memo の実行経路を必ず通すための anchor。
  各 entry は `id`、`grammar`（1 行の UBNF。文法名と `@package` は実行時に置き換える）、
  `inputs`、任意の `note` / `issue` / `expectedRejection`。失敗したテストが
  `unlaxer-dsl/target/property-based-parity.json` に出した entry は、`id` / `grammar` /
  `inputs` をそのまま写せば追加できる。
- `known-differences.json`: 既知差。各 entry は追跡 issue と理由を必須とし、
  finding の種別（`kind`）と、任意で文法・詳細の正規表現で対象を限定する。除外件数は
  テストの `[pbt] ... excludedKnown=` に毎回出る。修正したら entry を削除する。

期待値は持たない。比べるのは Java と native Rust の観測値どうし、設定どうし、および
docs に書いた不変条件である。
