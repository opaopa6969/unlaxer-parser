# 仕様 corpus の実行先 (#784)

`manifest.json` は `unlaxer-dsl/src/test/resources/spec-corpus/ubnf/*.json` の
`validate` / `runtime` 全件について、frontend の除外と実行テストを結ぶ。
入力と仕様の診断期待値は複製せず、各 driver が元のファイルの ID を解決する。
新しい scope 対象 case に実行先が無い場合、Java / Rust / Python の集合比較が失敗する。

| scope | Java | Java-host Rust | Rust native | 独立 oracle |
|---|---|---|---|---|
| validate (18 件) | `validationCorpusRunsOnEveryHost` の `GrammarValidator` | 同メソッドの `RustGrammarLowering` / `RustBackend` | `validate_corpus_on_native` の lowering / generate | 元 corpus の `expectation.code`、overflow の元 CP `18..33` |
| runtime (3 件) | `runtimeCorpusRunsOnEveryHost` の生成 Java parser | 同メソッドの生成 Rust parser | `runtime_corpus_on_native` の生成 Rust parser | manifest の仕様由来入力・prefix 成否・consumed/matched CP・ERROR hint/CP |

各 ID のファイルと driver は manifest に記録する。Java frontend の validator 実行、
既存の `bounded_overflow::original_spec_corpus_overflow_reaches_validation` も残すが、
それだけを全 corpus の実行証拠には使わない。

runtime の元 corpus の `undetermined` は frontend で観測できないことを示すので変更しない。
`CHAR_RANGE` は両端・直前直後・空・補助文字・後続入力を、separator は 1 個/複数・
異なる ANY separator・補助文字 separator・末尾の未完ペアを、ERROR は先行選択肢の成功と
失敗時の hint `m` を検証する。全 21 入力を 3 経路で実行する。

Java は元文法を生成し、`RParser` / `ExprListParser` を直接実行する。
Rust の generator は root AST node を必須とするため、指定 entry に空の
`@mapping(SpecResult)` を付け、root の無い 2 件には `@root` も付ける。
元の rule body・token・入力は変更しない。実行記録には補う前の原文を記録する。

validate は元入力のまま、syntax 受理後に validator / lowering / generator の拒否を検査する。
2 件の量指定子は同じ診断コード（overflow は CP も）に一致する。残る 16 件は
診断優先順位・root 前提・診断コードの差があるため、#833（参照/mapping 4 件）、
#834（whitespace 5 件）、#835（その他 annotation 7 件）で追跡する。
Java の仕様期待値は残し、各 Rust host の旧診断を完全一致で検査する。
未知の変化・解消済みの差は失敗する。コードだけの仕様に新しい CP 契約は推測で追加しない。
既知差は `parity-ledger.json` の対応する未修正項目・OPEN issue への参照も必須。
未修正項目の `observation` には許可する corpus ID を backtick で列挙し、manifest の既知差の集合と
完全一致させる。manifest だけで既知差を増やすこと、解消後に未修正項目だけ残すことは検査で落ちる。

Rust は照合後に `SPEC-CORPUS` 実行記録を出力し、Java は native テストを `--exact` で
呼んで記録を照合する。cargo は存在しない test 名でも exit 0 になるので、終了コードに加えて
ID・原文・照合数を検査する。各記録には driver が観測した値（validate は診断、runtime は
入力ごとの prefix と ERROR 診断）も残し、Python 検査が独立期待値で判定し直す。driver 内の照合を
常に成功にする変更でも、観測値が期待値と違えば CI が落ちる。
Java 自身の照合後の記録と合わせ、次の 2 ファイルを出力する。

- `unlaxer-dsl/target/spec-corpus-validate.json`（54 件）
- `unlaxer-dsl/target/spec-corpus-runtime.json`（9 件）

CI は実行前に古い記録を削除し、実行後に
`python3 scripts/check-spec-corpus-execution.py --receipts unlaxer-dsl/target/spec-corpus-validate.json unlaxer-dsl/target/spec-corpus-runtime.json`
で全 63 組を検査する。引数なしの静的検査だけを実行の証明にしない。
`python3 -m pytest -q scripts/tests/test_spec_corpus_execution.py` は新規 case、除外追加、
driver ファイル/テスト削除、記録の欠落・重複・入力変更・照合数不足・観測値の不一致、
台帳に列挙しない既知差の追加の mutation を検査する。
