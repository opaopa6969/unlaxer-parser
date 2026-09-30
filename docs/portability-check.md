# UBNF の移植可能性チェック — Java / Rust

Issue: [#157](https://github.com/opaopa6969/unlaxer-parser/issues/157)

## 何を確認するか

UBNF を Rust backend へ渡す前に、未対応の token・annotation・型指定などを
ソース位置付きで列挙する。Java host と native Rust host に同じ CLI / JSON 契約を提供する。
**host が Java/Rust の二つであり、生成 target は現在 `rust` のみ**。
Java 向け生成の可否を調べる `--target java` は未実装で、引数エラーになる。

`portable: true` は、現在の Rust backend の対応範囲・構造検証を通ったという意味。
生成コードのコンパイル、手書き semantics の実装、言語間の評価結果、LSP/DAP、全機能の
移植完了まで保証しない。対応範囲は [FULL-SPEC 対応表](../rust/FULL-SPEC.md) を参照する。
`ADAPTER` / `@tokenAdapter` は [token adapter 契約](token-adapters.md) に従って検査する。
custom binding の class / function の探索・コンパイルはせず、`portable: true` でも
外部 provider の実在・署名・意味的同値性を保証しない。

## CLI

repository root で実行する。native バイナリは実行時に Java / Maven / Cargo を必要としない。

```sh
cargo build --release --locked --manifest-path rust/Cargo.toml -p unlaxer-generator
rust/target/release/unlaxer check --target rust \
  --grammar unlaxer-dsl/src/test/resources/evolution/3/Evolution.ubnf --format json
```

Java host は [既存 generator の準備手順](../rust/README.md#java-frontendを使う既存経路) で
classpath を用意し、`generate` の代わりに `check` を指定する。

```sh
java -cp "unlaxer-dsl/target/classes:$(tr -d '\n' < unlaxer-dsl/target/rust-classpath.txt)" \
  org.unlaxer.dsl.CodegenMain check --target rust \
  --grammar unlaxer-dsl/src/test/resources/evolution/3/Evolution.ubnf --format json
```

- `--target rust` と `--grammar` は必須。`--format json` は省略可能。
- `check --help` はヘルプを表示する。未知・重複・不完全な option は拒否する。
- `--output` は受け付けない。既存の **`generate ... --check` は生成物の差分検査**であり、
  この移植可能性検査とは異なる。
- UTF-8 の指定ファイルだけを読む。import 先は開かず、外部 parser class の探索・初期化、
  生成、コンパイル、対象コードの実行、subprocess 起動は行わない。
  Java は class 探索を除いた共通 validator を使う。native CLI は空の `PATH` でも動く。

| 終了コード | stdout | stderr |
|---|---|---|
| `0` | 検査成功の JSON 1 個（help の場合はヘルプ） | 空 |
| `3` | 構文不正・未対応・構造不正の JSON 1 個 | 空 |
| `2` | 空 | 引数エラー |
| `4` | 空 | 読み込み・UTF-8・path 等の I/O エラー |

`2` / `4` の説明文そのものは両 host の一致契約に含めない。

## API と JSON

Java: `PortabilityCheck.check(String)` → `Result(portable, structure, diagnostics)`。
Rust: `unlaxer_generator::portability::check(&str)` → `Report`（`to_json()` 付き）。
各診断は `code`、`subject`、optional な `span` と `severity()` を持つ。
診断の severity は現在すべて `error`。

```json
{"schemaVersion":1,"target":"rust","portable":true,"structure":"passed","diagnostics":[]}
```

失敗時の各診断の形:

```json
{"code":"P-ANNOTATION","severity":"error","span":{"start":12,"end":25},"subject":"doc"}
```

位置は元 UBNF の **Unicode code point の半開区間**。UTF-8 byte / Java UTF-16 / LSP 列ではない。
LF/CRLF は元入力のまま数え、escape 復号後の長さから逆算しない。
名前や引数の String が独立した source node を持たない場合は、その annotation / token 全体の
範囲を使う。元位置は [source snapshot](ubnf-source-snapshot.md) から取得する。

順序は `(start, end, code, subject)`、位置不明は末尾。同じ code・範囲・subject の重複だけを
取り除く。同じ内容が別位置に出現した場合は両方残す。
JSON 値と配列順を両 host で比較する。空白や `\n` / `\u000a` 等の escape 表現の byte 一致は
要求しない。同じ host・同じ入力の出力は決定的。

## 二段階の検査

1. 構文 AST 全体を走査し、独立に判定できる未対応機能を**全出現**収集する。
   入れ子の body、後続の rule、複数 grammar も途中で打ち切らない。
2. 未対応機能がなければ、既存の lowering / 構造 validator を使って root、capture 型、
   再帰、量指定子、結合性、名前衝突等を検査する。

| `structure` | 意味 |
|---|---|
| `passed` | 両段階を通過、`portable: true` |
| `blocked` | 未対応機能があり、依存する構造検証を実行できない |
| `failed` | 機能 inventory は通過したが、構造検証で拒否された |
| `unavailable` | UBNF の構文 AST を取得できない |

**構造検証は依然 fail-first**。`P-STRUCTURE` は grammar 全体の範囲であり、すべての
構造エラーやその最小位置を列挙するものではない。例外文言の正規表現解析で code や位置を
推測しない。UBNF 構文失敗 `P-SYNTAX` は Java frontend の精密な位置 API がないため
両 host とも `span: null`。部分 AST からの recovery は行わない。

## 診断コード（schemaVersion 1）

| code | 対象 / subject |
|---|---|
| `P-GRAMMAR-COUNT` | 単一 grammar 以外 / `expected one grammar`（ファイル全体） |
| `P-IMPORT` | import / path |
| `P-SETTING` | 未対応 key または block setting / key |
| `P-WHITESPACE` | `none` / `javaStyle` 以外 / style |
| `P-EXTERNAL-TOKEN` | 実際の lowerer の組み込み対応表にない Simple token / class 名 |
| `P-TOKEN-KIND` | 未対応の `REGEX` / `CI` |
| `P-ADAPTER-DEFINITION` | adapter schema・ID・version・binding path 不正 / ID（不明なら `tokenAdapter`） |
| `P-ADAPTER-DUPLICATE` | 同じ ID/version の重複登録 / ID（後出 setting の範囲） |
| `P-ADAPTER-UNKNOWN` | 未登録 adapter 参照 / ID（token 全体） |
| `P-ADAPTER-VERSION` | 既知 ID の未登録 version / ID（token 全体） |
| `P-ANNOTATION` | `eval` / `doc` / `recovery` / `commonField` / `enum` / Simple annotation 名（`@skip` は対応済み） |
| `P-MAPPING-TYPE` | 対応しない mapping 型名 / class 名（投影しない skipped mapping を除く） |
| `P-FIELD-NAME` | 対応しない field 名 / param 名（投影しない skipped mapping を除く） |
| `P-INTERLEAVE` | 未対応 profile / profile 名 |
| `P-SCOPE-MODE` | 未対応 scope mode / mode 名 |
| `P-TYPEOF` | `@typeof` / capture 名（prefix 自体の範囲） |
| `P-QUALIFIED-REFERENCE` | 名前空間付き参照 / `namespace.name` |
| `P-ERROR-ELEMENT` | 旧版の`ERROR(...)`未対応診断。#329以降は生成対応により発行しない（別用途へ転用しない） |
| `P-EMPTY-LITERAL` | 空 literal / `empty literal` |
| `P-STRUCTURE` | 構造検証失敗 / `Rust structural constraints` |
| `P-SYNTAX` | 構文失敗 / `UBNF syntax` |

mapping 型・field 名は `[A-Za-z][A-Za-z0-9_]*` で、`Self/self/super/crate` を除く。
field はさらに `span/semantics` を予約する。その他の衝突・型不整合は構造段階で検出する。
機能が追加されれば、その構文は inventory の対象から外れる。code 名を別の意味に転用しない。

## 境界と回帰修正

- 両 host の Rust lowering で構造分析の深度上限を 256 に揃える。これは単なる rule 数ではなく、
  式・rule の入れ子を数える。長い alias chain は安定した `P-STRUCTURE` とし、Java の
  `StackOverflowError` による非 JSON 終了を防ぐ。
- 同じ precedence level に `leftAssoc` / `rightAssoc` が混在する文法は、Java 共通 validator
  と同様に native lowering も拒否する。check だけでなく native generate にも適用する。
- Java / Rust frontend の既知の構文・mapper 差をこの checker が解消するわけではない。
  frontend の受理範囲外の入力まで完全同値とは主張しない。共通 corpus と別の frontend
  conformance test で差を追跡する。敵対的な入力全般の資源量保証・sandbox でもない。

## 検証

```sh
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml
cargo +1.85.0 clippy --locked --manifest-path rust/Cargo.toml --workspace --all-targets -- -D warnings
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=PortabilityCheckTest,RustPortabilityConformanceTest,RustNativeGeneratorTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

共有 fixture は [`tests/fixtures/portability`](../rust/unlaxer-generator/tests/fixtures/portability/)。
21 件の未対応機能を持つ入力、同値の複数出現、Unicode、複数 grammar、構文/構造失敗、
同一 precedence の左右混在を検査する。13 fixture、5 種類の alias chain、2 種類の括弧の入れ子を
LF/CRLF で比較し、`target/rust-portability.tsv` に 40 ケースの JSON・終了コード一致を残す。
既存の生成可能な 122 文法でも両 host の check 成功と 610 生成ファイルの byte 一致を検査する。
native CLI は `PATH=''` で実行し、入力・手書きファイルが変更されないことも確認する。

実 TinyExpression P4 は、revision `42bd00d208815ffb9f3f75143fce814d082d5678` の
`tools/tinyexpression-p4-lsp-vscode/grammar/tinyexpression-p4.ubnf` を使用する。
SHA-256 は `b3ebead02cf9c4ff2c8d8b56767ca51f3665331d7b84cf1f1fce79d600774347`。
CI の既存 downstream pin `7d7bd1c` も同じ grammar hash である。

この実文法はすでに **診断 0 件**。過去の未対応状態を装わず、元文法の成功に加え、コピーへ
`example.UnportedParser` と `@doc` を明示的に注入した負例で 2 件の全件収集を検査する。
注入負例を実 TinyExpression の未対応件数として扱わない。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=RustPortabilityConformanceTest -DrustConformance=true \
  -Dtinyexpression.grammar=/absolute/path/to/pinned/tinyexpression-p4.ubnf \
  -Dsurefire.failIfNoSpecifiedTests=false
```

この追加テストは LF/CRLF の baseline / 注入負例 4 ケースを
`target/rust-portability-tiny.tsv` に記録する。CI artifact はそれぞれ
`rust-conformance` と `tiny-portability-conformance`。素の `mvn test` は Rust 比較を skip し、
実 P4 テストには上記 property も必要。skip を一致の証拠にしない。
