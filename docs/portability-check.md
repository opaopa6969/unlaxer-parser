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
- format 2 のファイル入力ではローカルの [字句 module](declarative-tokens.md#字句-grammar-の部品化と名前空間)
  も読む。文字列 API と format 1 の inventory は import 先を開かない。外部 parser class の探索・初期化、
  生成、コンパイル、対象コードの実行、subprocess 起動は行わない。
  Java は class 探索を除いた共通 validator を使う。native CLI は空の `PATH` でも動く。
- module 解決エラーは `P-MODULE`（終了 3）。解決後も、診断対象の node が root ソース由来なら
  元の Unicode CP 範囲を保持する（#776）。import を持つ grammar の作り直された node と、
  import と無関係な grammar の node の両方が対象。import 由来の合成 token や出典不明の
  node は `span: null` とし、import 先の offset や呼び出し側の参照位置を借用しない。
  `P-STRUCTURE` は診断対象が root の grammar 宣言なので、その宣言の範囲を返す。
  schemaVersion 1 と既存フィールドを維持し、source 識別子は追加しない。root と位置不明の
  出典区分は内部で管理する。import 先のファイル名・位置の出力は引き続き未対応。

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
取り除く。同じ内容が別位置・別 grammar に出現した場合は両方残す。位置付きの root 診断と
`span: null` の診断も別件とする。import 由来・出典不明でともに null の同じ code/subject は
従来どおり一件にまとめる。出典保持の変更（#776）は import のない単一ファイルに影響しない。
複数の構造エラーがある場合の優先順位は下記 #825 の契約に従う。
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

[複数 grammar](multiple-grammars.md) は独立に構造検証し、後続 grammar の失敗も収集する。
**各 grammar 内の構造検証は依然 fail-first**。`P-STRUCTURE` は grammar 全体の範囲であり、すべての
構造エラーやその最小位置を列挙するものではない。例外文言の正規表現解析で code や位置を
推測しない。UBNF 構文失敗 `P-SYNTAX` は Java frontend の精密な位置 API がないため
両 host とも `span: null`。部分 AST からの recovery は行わない。

## 診断コード（schemaVersion 1）

| code | 対象 / subject |
|---|---|
| `P-GRAMMAR-COUNT` | grammar が0件の AST / `expected one grammar`（ファイル全体）。複数 grammar は #714 で対応 |
| `P-GRAMMAR-NAME` | programmatic AST の不正な grammar 名 / 名前（通常のソース入力は frontend が構文検証） |
| `P-OUTPUT-COLLISION` | ASCII 大小文字を無視した grammar 名の衝突 / `grammar_` + 小文字名（後出 grammar 全体） |
| `P-IMPORT` | import / path |
| `P-SETTING` | 未対応 key または block setting / key（`@comment` は下の 2 code で検査） |
| `E-COMMENT-SETTING` | `@comment: { line: '//' }` 以外の形・重複 / `comment`（setting 全体の範囲）。全体の `@whitespace: javaStyle` と併記した場合は報告せず portable のまま（3.3.0 互換。警告 `W-COMMENT-IGNORED` は validator・`global_comment::warnings`・生成の警告（`GenerationWarnings` / `warnings::collect`、#777）が出す） |
| `E-COMMENT-WHITESPACE` | 名前付き global `@whitespace` と `@comment` の併記 / whitespace 名（`@comment` の範囲） |
| `P-WHITESPACE` | `none` / `javaStyle` 以外 / style |
| `P-EXTERNAL-TOKEN` | 実際の lowerer の組み込み対応表にない Simple token / class 名 |
| `E-REGEX-RETIRED` | 廃止された `REGEX(...)` / `RegexTokenParser`。宣言的字句式へ移行（公開済み3.3.0は変更なし） |
| `P-ADAPTER-DEFINITION` | adapter schema・ID・version・binding path 不正 / ID（不明なら `tokenAdapter`） |
| `P-ADAPTER-DUPLICATE` | 同じ ID/version の重複登録 / ID（後出 setting の範囲） |
| `P-ADAPTER-UNKNOWN` | 未登録 adapter 参照 / ID（token 全体） |
| `P-ADAPTER-VERSION` | 既知 ID の未登録 version / ID（token 全体） |
| `P-ANNOTATION` | 旧版の Simple annotation 未対応診断。#715 以降は Java と同じく受理して生成器は名前を使わず（警告 `W-ANNOTATION-IGNORED`、[契約](simple-annotations.md)）、発行しない（別用途へ転用しない） |
| `P-MAPPING-TYPE` | 対応しない mapping 型名 / class 名（投影しない skipped mapping を除く） |
| `P-FIELD-NAME` | 対応しない field 名 / param 名（投影しない skipped mapping を除く） |
| `P-INTERLEAVE` | 未対応 profile / profile 名 |
| `P-SCOPE-MODE` | 未対応 scope mode / mode 名 |
| `P-TYPEOF` | `@typeof` / capture 名（prefix 自体の範囲） |
| `P-QUALIFIED-REFERENCE` | 名前空間付き参照 / `namespace.name` |
| `P-ERROR-ELEMENT` | 旧版の`ERROR(...)`未対応診断。#329以降は生成対応により発行しない（別用途へ転用しない） |
| `P-EMPTY-LITERAL` | 旧版の空 literal 未対応診断。#713 以降は [Java 互換生成](empty-literal.md)により発行しない（別用途へ転用しない） |
| `P-NESTED-SHAPE` | nested capture schema の失敗 / `nested capture schema`（rule の範囲） |
| `P-COMMONFIELD` | commonField schema の失敗 / `commonField schema`（rule の範囲） |
| `P-STRUCTURE` | その他の構造検証失敗 / `Rust structural constraints`（grammar の範囲） |
| `P-SYNTAX` | 構文失敗 / `UBNF syntax` |

mapping 型・field 名は `[A-Za-z][A-Za-z0-9_]*` で、`Self/self/super/crate` を除く。
field はさらに `span/semantics` を予約する。その他の衝突・型不整合は構造段階で検出する。
機能が追加されれば、その構文は inventory の対象から外れる。code 名を別の意味に転用しない。

## 複数の構造エラーの優先順位（#825）

Java-host の検証順を参照実装とし、native も同じ公開診断を返す。
エラーの文字列から常に commonField を優先するのではなく、次の段階順で判定する。

1. frontend の失敗は `structure: unavailable`（通常は `P-SYNTAX`）。REGEX 廃止・
   parameterized rule の位置付き診断は既存の専用 code を保つ。構造検証は行わない。
2. ファイル API の v2 import 解決が失敗すれば `P-MODULE` / `blocked`。
   次に全 grammar の名前・出力衝突と機能 inventory を収集する。一件でもあれば
   `blocked` としてそれらだけを返し、別 grammar にある構造エラーも調べない。
3. inventory を通過したら、各 grammar を独立に検証して最大一件を返す。
   bounded repeat の範囲・overflow、precedence overflow、REGEX 廃止、nested capture、
   raising operator の順に検証し、その後に commonField schema を検証する。
   前の段階で失敗したら後の schema エラーは選ばない。nested capture の失敗は
   `P-NESTED-SHAPE` / `nested capture schema` と問題の rule 全体の CP span、
   bounded / precedence の失敗は `P-STRUCTURE` と grammar 全体の span になる。
   raising operator など inventory でも検出するものは通常 2 の段階で止まる。
4. commonField schema の失敗は `P-COMMONFIELD` / `commonField schema` と問題の
   rule 全体（annotation を含む）の CP span。後段の enum・typeof・eval・embedding・
   通常の lowering、さらに Java の共通 validator / native の `validate_ir` の失敗より優先する。
   これは `@eval` の有無や規則の前後によらない。schema はまず mapping 名の正規化衝突、
   次に sum family の循環、最後に commonField 宣言を調べ、それぞれ宣言順で最初の失敗を選ぶ。
   二規則の commonField がともに不正なら、同じ検証段階で先に宣言された規則を返す。
   一つの規則の中では、宣言した field の順に、その field の重複・予約名、続けて family の
   leaf ごとに mapping の引数・capture の欠落と型を調べる。field の型・cardinality・view 名も
   IR 生成前に確認する。型の解析で同じ mapping を共有する規則どうしの引数・cardinality の
   不一致が先に見つかれば、それは schema の失敗ではないので 5 の `P-STRUCTURE` になり、
   後の field・leaf・規則の schema エラーは選ばない。
5. 残る lowering / validator エラーは `P-STRUCTURE` / `Rust structural constraints` と
   grammar 全体の span にまとめる。複数の通常エラーがあっても一件で、内部で最初に失敗した
   検証の説明文は JSON に公開しない。

構造エラーがあれば `structure: failed`、終了コードは 3。
複数 grammar の診断は既存どおり span の start、end、code、subject の順で並べ、
位置不明は最後に置く。同じ code・subject でも位置が違えば別件。
単一エラーの出力、JSON schemaVersion 1、公開済み 3.3.0 は変更しない。

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
18 件の未対応機能を持つ入力、同値の複数出現、Unicode、複数 grammar、構文/構造失敗、
同一 precedence の左右混在を検査する。13 fixture、5 種類の alias chain、2 種類の括弧の入れ子を
LF/CRLF で比較し、`target/rust-portability.tsv` に 40 ケースの JSON・終了コード一致を残す。
`imports.json` は Imported + One + Two、複数 import・同じ module の再利用・ネストした import、
同じ code/subject の root 出現と null 出現、作り直された rule/element/@lexicalContext、解決失敗と成功を固定する。
非 BMP 文字を含む9ケースを LF/CRLF で読み、両 host の JSON 全体を固定期待値（配列順・件数・CP 範囲を含む）と照合し、
`target/rust-portability-imports.tsv` に18ケースの証拠を残す。native の workspace test も同じ fixture を使う。
`structure-priority.json` は再現例と既存の `annotation-parity.ubnf` を含む71ケースを
LF/CRLF で検証する。enum・eval・embedding・typeof・未定義参照・IR 検証のエラー、
前段の syntax/module/inventory、bounded/precedence/nested、二規則の schema エラー、
複数 grammar の順序、既存 commonField corpus の型・cardinality、共有 mapping の不一致が
後の field・leaf・規則の schema エラーより先に `P-STRUCTURE` になることも固定する。
annotation・capture・再帰を持つ正常な enum alias も受理し、別の eval エラーを隠さないことを確認する。
両 host の JSON 全体と終了コードを比較し、`target/rust-portability-structure-priority.tsv`
に142ケースの証拠を残す。native workspace test も同じ固定期待値を読む。
既存の生成可能な 122 文法でも両 host の check 成功と 610 生成ファイルの byte 一致を検査する。
native CLI は `PATH=''` で実行し、入力・手書きファイルが変更されないことも確認する。

実 TinyExpression P4 は、revision `42bd00d208815ffb9f3f75143fce814d082d5678` の
`tools/tinyexpression-p4-lsp-vscode/grammar/tinyexpression-p4.ubnf` を使用する。
SHA-256 は `b3ebead02cf9c4ff2c8d8b56767ca51f3665331d7b84cf1f1fce79d600774347`。
CI の既存 downstream pin `7d7bd1c` も同じ grammar hash である。

この実文法はすでに **診断 0 件**。過去の未対応状態を装わず、元文法の成功に加え、コピーへ
`example.UnportedParser` と未対応の `@portabilityProbe` を明示的に注入した負例で 2 件の全件収集を検査する。
3.3.0 から `@doc` は両 Rust emitter が metadata として保持するため、未対応診断には含めない。
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
