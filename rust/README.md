# 実験的Rust生成バックエンド

Java版を置き換える移植ではなく、既存のJava UBNF frontendからRustのparser・AST・mapper・evaluator dispatchを生成する最小構成。UBNFの解釈と検証を共有しやすいため、独立リリース前のruntimeと生成器は同一repoに置く。crateは`publish = false`で、crates.ioへ公開しない。

## すぐ動かす

生成済みのexampleにはJVMも外部crateも不要。repoルートで実行する。Rust 1.85以上、Cargoを使用する。

```sh
cargo test --locked --manifest-path rust/Cargo.toml
printf '%s\n' 'if(0,neg(2*3),4+5)' | cargo run --quiet \
  --manifest-path rust/Cargo.toml -p unlaxer-evolution-example
```

1行1入力を読み、AST・コードポイントspan・評価値を含むJSONを返す。この例の値は`9`。解析失敗は`ok:false`と`offset`/`expected`、評価失敗や非有限値は`ok:true`と`evaluationError:true`。改行を含む入力のためのJSON入力プロトコルではない。

## 再生成

Java 21、Maven 3.9系も必要。Java版の既存CLIは変更せず、`generate`サブコマンドを追加した。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am install -DskipTests -Dgpg.skip=true
mvn -q -pl unlaxer-dsl dependency:build-classpath -Dmdep.outputFile=target/rust-classpath.txt
java --enable-preview \
  -cp "unlaxer-dsl/target/classes:$(tr -d '\n' < unlaxer-dsl/target/rust-classpath.txt)" \
  org.unlaxer.dsl.CodegenMain generate --target rust \
  --grammar unlaxer-dsl/src/test/resources/evolution/3/Evolution.ubnf \
  --output rust/examples/evolution/src/generated
```

同じコマンドに`--check`を付けると書き込まずに生成物の完全一致を検査する。未対応文法は出力前に拒否する。出力先の5ファイルを事前検査し、生成マーカーのない既存ファイルやsymlinkは上書きしない。生成済みファイルは再生成で置き換わるので編集しない。終了コードは成功`0`、CLI誤り`2`、文法・サブセット検証失敗`3`、I/O・衝突・差分検出`4`。

出力は`mod.rs`、`parser.rs`、`ast.rs`、`mapper.rs`、`evaluator.rs`。呼出側crateにruntimeのpath dependencyと`pub mod generated;`を追加する。手書きのsemanticsやCargo manifestは生成器の管理外。exampleのように`#[rustfmt::skip]`をmodule宣言に付け、生成テキストをformatterで改変しない。

## 構成と保証範囲

```text
UBNF → 既存Java frontend → RustGrammarLowering → GrammarIR → RustBackend
                                                           ↓
                                   parser / enum AST / mapper / Semantics trait
                                                           ↓
                                             手書きimpl + 小さなRust runtime
```

`codegen.rust.GrammarIR`は規則・連接・順序付き選択・captureを保持する構造IRであり、既存のmetadata用Parser IRとは別物。既存Java生成器はまだこのIRを使わない。全backend共通IRへの全面移行や、言語非依存性の一般的な証明を済ませたものではない。

runtimeは規則IDで参照する文法を実行し、入力とCST node arenaを解析結果が所有する。ASTは`enum`、子は`Box<Ast>`、scalar captureは`String`。各variantは半開区間`Span { start, end }`を持つ。内部カーソルはUTF-8 byte、公開位置はUnicodeコードポイントで、UTF-16/LSP座標とは異なる。mapper後のASTは文字列と位置を所有し、CSTを破棄しても評価できる。静的なソースマップは使わない。

`Semantics`の各メソッドは既定実装を持たず、`evaluate`はwildcardなしの`match`。新しいvariantと古いdispatchを組み合わせると`E0004`、新しいtraitと古いimplでは`E0046`になる。ただし再コンパイルされるソース間の構造チェックであり、意味処理の正しさは保証しない。文字列として保持する演算子の追加は型を変えないため、この保護を受けない。

### 対応するUBNF

- 1ファイル・1文法、ちょうど1つの`@root`。
- 非空の文字列terminal、rule参照、sequence、ordered choice、group。
- `NumberParser`またはその完全修飾名へのtoken binding。符号・小数・指数を含む。
- `@mapping`とterminal/ruleへのscalar capture。各選択肢でcapture名と型が一致し、paramsと一対一で対応すること。
- `@whitespace: javaStyle`（ASCII空白、行/ブロックコメント）または`none`。未指定は`none`。`@package`はRustでは使用しない。

imports、外部token parser、繰り返し・optional、group全体のcapture、`@typeof`、`@eval`等の他のannotation、左再帰、混合text/node choiceなどは明示的に拒否する。mapping名・field名はASCII識別子に制限し、Rustのraw identifierで出力する。`self`/`Self`/`super`/`crate`、fieldの`span`/`semantics`、重複mapping名・生成method名は拒否する。rootはAST nodeへ解決される必要がある。

RustのLSP/DAP、回復・incremental cache、PropagationStopper、MatchedTokenParser、string/variable/function-call構文、proc macro、Rust製UBNF frontendは未実装。性能最適化・Java比の速度優位も未評価。文法構造はparseごとに構築し、runtimeのrule呼出深さには256の上限がある。大規模・敵対的入力の資源量保証はない。

## 再現実験と結果

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=RustBackendTest,RustConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
cargo fmt --all --manifest-path rust/Cargo.toml --check
cargo clippy --locked --manifest-path rust/Cargo.toml --workspace --all-targets -- -D warnings
```

通常のMavenテストではRustを必須にしないため、cross-language実験は明示指定時に実行する。CIのRust jobはこのフラグを必ず指定し、rustcがない場合は失敗する。生成済みexampleの差分も同時に検査する。

v6と同じ[`evolution/{0,1,2,3}`](../unlaxer-dsl/src/test/resources/evolution/)の文法をJava/Rustの両方で実際に生成・コンパイルする。[共有corpus](../unlaxer-dsl/src/test/resources/evolution/conformance.json)は37入力。成功/失敗、成功時の全AST field・全nodeのspan、評価成功/失敗、有限評価値を比較する。数値比較の許容差は`1e-12`。Unicodeコメント、不完全指数、余分な文字、空入力、未閉鎖コメントも含む。

| 段階 | 入力数 | 受理数 | 有限値評価成功 | 新variant＋旧dispatch | 新trait＋旧impl |
|---|---:|---:|---:|---|---|
| 0: 加算 | 37 | 17 | 12 | 対象外 | 対象外 |
| 1: 乗算追加 | 37 | 18 | 13 | 型は変わらない | 旧implはコンパイル成功・評価失敗 |
| 2: Negation追加 | 37 | 22 | 17 | E0004 | E0046 |
| 3: Conditional追加 | 37 | 25 | 20 | E0004 | E0046 |

この148ケースは1つのJUnitメソッド内の入力比較であり、148個の独立テストメソッドではない。2026-09-19、Java 21.0.9 / Rust 1.98.1で確認した結果。実行時のTSVは`unlaxer-dsl/target/rust-conformance.tsv`に出る。別途Rustのunit testがrollback・数値・コメント・Unicode診断位置・深さ制限・CST破棄後のASTを検査する。

受理後に失敗する5入力も意図的に残す。4入力はコメントを含む数値captureの数値変換失敗で、1入力は`1e309`の非有限値。現在のJava mapperが数値captureの末尾コメントを除去しない挙動をRust側も再現している。AST fieldはtriviaを含む場合があり、spanも必ずしも字句本体だけではない。

Javaの生成mapperには統一的な構造化診断APIがないため、失敗時の両言語のerror位置・expected集合の一致は**未検証**。Rustの位置の範囲・expectedの存在とunit testの既知位置は検査する。有限corpusの一致は全UBNF・全入力の等価性証明でも、性能・生産性の比較でもない。
