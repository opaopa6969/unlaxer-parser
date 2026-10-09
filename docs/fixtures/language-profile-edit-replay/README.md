# 同一文書の持続的な編集系列

追跡 #486、親 #383 / #384。Java 21、TypeScript 5.9.3、Rust 1.85.0 の限定
profile に、それぞれ seed `383384` の48回の編集を与える。文法レジストリは言語ごとに
一度だけ作り、同一 URI の文書、version、解析結果、project、query binding と全履歴を
保持する。前の snapshot に実 `LanguageRegions.apply` / Rust `LanguageRegions::apply` で
編集を適用し、得た snapshot を同じレジストリで再解析して次の binding を作る。

これは**共通 runtime の持続 session と実 edit/query API の検証**である。
生成 LSP に48回の `didChange` を送る通信系列、incremental parser の性能、外部 compiler
provider の長期状態を検証したとは主張しない。旧 `language-profiles/mutations.tsv` の
204件は独立した入力の解析であり、この持続 session の根拠とは別である。

## 独立した期待値

`edits.tsv` は各言語48行、計144遷移。`scripts/language-profile-edit-replay.py` は
parser を呼ばず、原著の正常テンプレートと明示した未閉鎖状態から編集・期待 source・
受理可否を作る。各8ステップを6回繰り返す。

1. 初期文書を挿入、以後は関数名を変更する。
2. 開き括弧の後に CRLF を挿入する。
3. 最終閉じ括弧を削除して EOF で失敗させる。
4. 未閉鎖のまま日本語と補助面文字を置換する。
5. 閉じ括弧を復元する。
6. 固定 seed で数値を置換する。
7. CRLF を LF に置換する。
8. 末尾の空白を追加する。

source と replacement は UTF-8 hex（空文字は `-`）、編集範囲は CP 半開区間。
`cp_length`、`utf16_length`、EOF の0始まり LSP line/character、期待拒否履歴数、
query 状態も fixture に固定する。失敗時の parser farthest offset は期待 source の
CP 長、成功時の root AST/CST span は `[0, cp_length)` と比較する。

## 状態と非対応能力

毎ステップで全過去 version の query binding と新 snapshot/project の組合せ、
新 query binding と過去 snapshot/project の組合せ、過去の edit binding と新 snapshot
の組合せを拒否する。既存の履歴 object の snapshot と query host は変更されず、
同一 version に異なる source を与えた query と非増加 version の edit も拒否する。
過去 binding が自身の過去 snapshot を解析できる immutable 契約は維持する。

全3 profile の `CODE_ACTION=UNSUPPORTED` を確認し、query 状態も独立期待値の
`UNSUPPORTED` と一致させる。登録した空能力 provider が実行されるとテストは失敗する。
文書への明示的な edit API と、言語 provider が提案する code action の対応能力は別である。
`VALIDATE=EXTERNAL` を変更せず、未登録外部 provider を局所対応として広告しない。

## Java / Rust と再現

実生成 Java parser/mapper と実生成 Rust parser/mapper が各々同じ fixture と比較する。
Java RustBackend と native Rust generator の全5生成ファイルの byte 一致も確認する。
Rust は単一 probe process 内で各48ステップの registry/history を保持する。
Java/Rust の観測行同士の比較は、独立期待値による検証に追加する対称性の検証である。

```sh
python3 scripts/language-profile-edit-replay.py --check
RUSTUP_TOOLCHAIN=1.85.0 CARGO_INCREMENTAL=0 mvn -pl unlaxer-common,unlaxer-dsl -am test -Dtest=LanguageProfileEditReplayConformanceTest -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

`-DrustConformance=true` を省略するとテストは skip される。成功時にだけ
`unlaxer-dsl/target/language-profile-edit-replay.tsv` を書く。header + Java144行 + Rust144行
の計289行を `language-profiles` CI が必須成果物として保存する。新しい言語機能・
provider・通信機能は加えず、親 issue の他の受入条件の完了根拠には広げない。
