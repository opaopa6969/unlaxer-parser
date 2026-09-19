# 実験的Rust生成バックエンド

Java版を置き換える移植ではなく、既存のJava UBNF frontendからRustのparser・AST・mapper・evaluator dispatchを生成する最小構成。UBNFの解釈と検証を共有しやすいため、独立リリース前のruntimeと生成器は同一repoに置く。crateは`publish = false`で、crates.ioへ公開しない。

最終的なparserバイナリ・`tinyexpression-rs`・`rustcodeblock`への[段階的な設計方針](ROADMAP.md)を参照。tinyexpression本体とRustコードブロック実行はまだ未実装。

full-specの進捗は[対応表と受け入れ条件](FULL-SPEC.md)で追跡する。runtimeの機能とUBNFから生成できる機能を区別する。

## すぐ動かす

生成済みのexampleにはJVMも外部crateも不要。repoルートで実行する。Rust 1.85以上、Cargoを使用する。

```sh
cargo test --locked --manifest-path rust/Cargo.toml
printf '%s\n' 'if(0,neg(2*3),4+5)' | cargo run --quiet \
  --manifest-path rust/Cargo.toml -p unlaxer-evolution-example
```

1行1入力を読み、AST・コードポイントspan・評価値を含むJSONを返す。この例の値は`9`。解析失敗は`ok:false`と従来の`offset`/`expected`に加えて構造化`diagnostic`、評価失敗や非有限値は`ok:true`と`evaluationError:true`。改行を含む入力のためのJSON入力プロトコルではない。

### ネイティブ実行ファイル

```sh
cargo build --release --locked --manifest-path rust/Cargo.toml -p unlaxer-evolution-example
printf '%s\n' 'if(0,neg(2*3),4+5)' | rust/target/release/unlaxer-evolution-example
```

実行先にはJVM/Cargo/rustcが不要。CIもrelease buildを直接実行し、値`9`を確認してから`unlaxer-evolution-linux-x86_64`というartifactにtar.gzを添付する。これはUbuntu runnerのLinux x86_64環境向けであり、全OS共通・完全staticな配布ではない。バイナリの中身は上記の縮小文法で、tinyexpression全体ではない。任意のUBNFを実行時に読み込むバイナリでもない。

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

RustのLSP/DAP、回復・incremental cache、PropagationStopper、Java MatchedTokenParserとの完全互換、string/variable/function-call構文、proc macro、Rust製UBNF frontendは未実装。性能最適化・Java比の速度優位も未評価。文法構造はparseごとに構築し、runtimeのrule呼出深さには256の上限がある。大規模・敵対的入力の資源量保証はない。

### 公開ParseContextと手書きcombinator

生成された`GeneratedParser`と手書きparserは同じ`Parser` traitを実装する。

```rust
use unlaxer_runtime::{ParseContext, ParseResult, Parser};

struct MyParser;
impl Parser for MyParser {
    fn parse(&self, context: &mut ParseContext<'_>) -> ParseResult {
        // contextの入力・位置・capture・利用者状態を読んで解析する。
        context.parse(&unlaxer_runtime::Expr::literal("hello"))
    }
}
let mut context = ParseContext::new("hello");
context.set_state("mode", String::from("example"));
let matched = context.parse(&MyParser)?;
```

呼出側は`context.parse(&parser)`を使う。これにより`Err`時にはカーソル・追加CST node・capture履歴・利用者状態を戻す。独自実装の`parser.parse(&mut context)`を直接呼ぶ場合、この外側のtransactionは付かない。最遠失敗の位置・候補はrollback後も保持する。先読みは成功時も状態を戻し、否定先読みの内部失敗候補は外へ漏らさない。

`source`/`remaining`/`position`/`advance`/`text`で入力を扱い、`set_state`/`state::<T>`/`state_mut::<T>`で`Any + Clone`の値を共有する。位置・移動量はコードポイント単位。利用者状態の`Clone`は変更可能な内容を独立コピーする必要がある。`Rc<RefCell<_>>`等の共有内部状態、ファイル操作等の副作用、panic unwindはrollback対象ではない。panicを捕捉した後はcontextを再利用しない。

`Expr`には`then`/`or`/`optional`/`repeat`/`zero_or_more`/`one_or_more`/`separated_by`/`capture`/`ahead`/`not_ahead`を用意する。`separated_by`は1個以上。無限反復の子が入力を消費せず成功した場合はエラーとする。`Custom(fn)`でcontextを取る関数を組み込める。`Any`/`CharRange`/`Except`は1コードポイント、`Until`は終端の直前まで（終端がなければ失敗）、`Eof`/`Empty`/`Error`も利用できる。これらの追加機能は**runtime APIのみ**で、UBNF loweringにはまだ接続していない。

`captured(name)`と`Backreference(name)`はcontext全体の同名captureの最新成功分を使用する。`capture_spans`は成功履歴を返す。字句スコープやJavaのcapture伝播規則を再現したものではない。

生成器は`rules()`・`parse_context(&mut ParseContext)`も出力する。context入口は現在位置からの**prefix解析**であり、全入力検証は従来の`parse_tree[_detailed]`、または後続の`Expr::Eof`を使う。文法とtrivia設定は呼出中だけ切り替わり、入力と利用者状態は共通。`matched.root_node()`から`context.tree(root)`で所有されたsnapshotを取り、その文法のmapperへ渡す。異なる文法のrule IDはローカルなので、複数文法のnodeを一つのmapperに混ぜない。node IDはcontext内だけで有効で、rollbackされた結果は再利用しない。

公開APIの使用例とrollback契約は[`context_combinators.rs`](unlaxer-runtime/tests/context_combinators.rs)、生成parserとの混在とAST評価は[`context.rs`](examples/evolution/tests/context.rs)で検証する。現在はtransactionごとに利用者状態・capture履歴をコピーする単純実装で、性能評価・最適化は未実施。

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

## 構造化診断の追加実験

Java生成mapperの`diagnose(String)`は`Optional<ParseDiagnostic>`を返す。空ならparserが全入力を受理したという意味で、AST mappingや評価の成功は保証しない。診断はmapperのsource-mapを消さず、保持したsnapshotや従来の最新lookupにも干渉しない。`parse`等の従来APIと例外は変更しない。

Rust生成parserの`parse_tree_detailed(&str)`は`Result<Tree, ParseDiagnostic>`を返す。従来の`parse_tree`/runtimeの`parse`は`ParseError`のままで、以前の最遠失敗位置・期待候補を維持する。Rust診断の`farthest`には従来の`ParseError`が入る。Java recordは`farthestOffset`/`farthestExpected`として保持する。JSONでは両方とも次の形を使う。

```json
{"kind":"trailing_input","offset":1,"expected":["end of input"],"farthestOffset":2,"farthestExpected":["number"]}
```

これはRustで`1+`を解析した例。加算の試行は位置2まで進むが、rollback後に数値`1`だけが成功するため、主要診断は未消費の`+`が始まる位置1。最遠失敗を捨てず、呼出側が両方の情報を使えるようにする。

| kind | offset | expected |
|---|---|---|
| `trailing_input` | 成功したprefixの直後、コードポイント単位 | `end of input`のみ |
| `syntax` | backendが記録した最遠失敗位置、コードポイント単位 | backend固有の候補をソートしたもの |

`syntax`の候補は、Javaでは組合せparser・数値内部・triviaのhintや引用符付きterminalを含み、Rustでは`number`やterminal本体になる。最小期待集合や次の入力候補の正確な集合ではない。型と位置だけ同じように扱えても、診断意味論全体が等価とは限らない。

既存37入力×4段階のうち失敗は66件（`syntax`41件、`trailing_input`25件）。全66件でkindと主要offsetが一致し、末尾未消費25件ではexpectedも一致する。syntaxのexpectedは41件すべて異なる。これらの両側の生診断を`target/rust-diagnostics.tsv`へ保存する。

さらに[`diagnostics.json`](../unlaxer-dsl/src/test/resources/evolution/diagnostics.json)の8境界例をstage 3で検査し、`target/rust-diagnostics-edge.tsv`へ保存する。7件は位置一致、`neg(1e+)`はJavaが位置7、Rustが位置5を返すことを明示的な差として固定する。Javaは失敗した指数部の内部位置を記録し、Rustは指数部をrollbackした後の終端不一致を記録するためである。比較のためにどちらかの位置を捏造・置換しない。

TSVは`unlaxer-dsl/target/`配下で、CI artifact `rust-conformance`にも添付する。66件・8件はJUnit内のケース数で、独立したテストメソッド数ではない。Unicode位置、rollback、source-map非干渉、Java診断listの防御的コピー/不変性、Rust旧APIの情報維持もテストする。有限corpusの一致は全UBNF・全入力の等価性証明でも、性能・生産性の比較でもない。
