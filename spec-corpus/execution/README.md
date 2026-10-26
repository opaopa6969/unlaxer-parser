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
全 18 件が Java validator と同じ独立診断コードに一致する（#833/#834/#835）。
量指定子 overflow は元入力の CP 範囲も比較する。コードだけの仕様に新しい CP 契約は推測で追加しない。
16 件の旧診断は manifest から削除した。位置付きでない元 corpus には独立した有効な root を
追加する別検査も行い、元入力の実行記録とは別に同じコードを確認する。将来差を登録する場合も `parity-ledger.json` の未修正項目・
OPEN issue を必須とし、`observation` の backtick で列挙する corpus ID と既知差の集合を完全一致させる。
解消後に未修正項目だけ残すことも検査で落ちる。

`diagnostic-priority.json` は別途、複数エラーを持つ 19 入力を root なし／独立した有効な root 付きで
実行する。Java validator の最初の ERROR と独立期待値を照合し、両 Rust host の lowering / generator が
同じコードを選ぶことを確認する。対象コードの順序は global whitespace（重複→style）、規則の宣言順に
whitespace（style→重複）→mapping（重複param→欠落→未列挙）→左右の結合性競合→precedence→
interleave→scopeTree、その後に全規則の precedence 欠如→未定義参照。
Java の root 欠如は `W-GENERAL-NO-ROOT` なのでこれらを遮らない。Rust の root 必須エラーは
対象診断が無い場合の従来の説明を保つ。

これは #833–#835 の診断群の順序であり、Java validator 全体を Rust lowering に移したものではない。
例えば元の `annotation/assoc-without-precedence` では Java は mapping 欠如も列挙するが、
corpus が指定する `E-ASSOC-NO-PRECEDENCE` を引き続き独立期待値とする。
生成前の bounded/precedence overflow・nested・commonField 等の先行検証は #825 のまま。
Java generator API は全 validator の代替ではなく、通常 frontend が共通 validator を先に呼ぶ。
Java-host の既存 `Rust subset: ` は説明内へ残し、対象コードを先頭に置く。
既存 choice profile の失敗は precedence より先の従来経路へ委ね、説明文を維持する。
名前述語の capture 検証が mapping 診断に先行する契約も保持する。native はその検証に必要な
body を構築する間だけ mapping 診断を保留し、検証後は root 欠如より先に返す。保留中に起きた
名前述語以外の lowering エラー（未定義参照など）より保留した診断を優先し、Java-host と同じ順にする。
修飾参照（`ns.Name`）は両 host とも従来の Rust の拒否（qualified rule reference）を保つ。
check JSON は説明文を公開せず、従来の inventory code / `P-STRUCTURE` と CP span・終了コードを維持する。

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
