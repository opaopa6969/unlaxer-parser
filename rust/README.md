# 実験的Rust生成バックエンド

Java版を維持しつつ、UBNFからRustのparser・AST・mapper・evaluator dispatchを生成する構成。Java frontend経路に加え、RustだけでUBNF読込・構造検証・5module生成を行うnative generatorも利用できる。独立リリース前のruntimeと生成器は同一repoに置く。crateは`publish = false`で、crates.ioへ公開しない。

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

### Rustだけで生成する

Rust 1.85以上とCargoだけでgeneratorをビルドできる。UBNF読込・意味検証・コード生成の通常経路でJava/Mavenを起動しない。

```sh
cargo build --release --locked --manifest-path rust/Cargo.toml -p unlaxer-generator
rust/target/release/unlaxer generate --target rust \
  --grammar unlaxer-dsl/src/test/resources/evolution/3/Evolution.ubnf \
  --output rust/examples/evolution/src/generated --check
```

`--check`を外すと生成する。`--target rust`は省略可。generatorの実行先にはJVM/Cargo/rustc不要（生成物をコンパイルする環境にはRustが必要）。CIの`unlaxer-generator-linux-x86_64` artifactにreleaseバイナリを添付する。Linux x86_64 runner用であり、完全static/全OS対応という意味ではない。

構成は`unlaxer-ubnf`（[全構文のsyntax ASTと既知差](unlaxer-ubnf/README.md)）、`unlaxer-generator`（対応範囲のlowering/検証とCLI）、`unlaxer-codegen`（[normalized IRから5module出力](unlaxer-codegen/README.md)）、`unlaxer-runtime`。文法が読めることと全backend機能を生成できることは別であり、下記の未対応機能はnativeでも明示拒否する。

119文法・595生成ファイルのJava/native byte一致を`RustNativeGeneratorTest`で検査し、生成と`--check`は空のPATHで実行する。Javaは比較用oracleで、native生成経路の依存ではない。構文解析は128、構造shape分析は256の再帰深度上限を持ち、超過は診断になる。Javaのprefix解析等との差はfrontend READMEへ明示する。

終了コードは0=成功、2=引数不正、3=構文/意味/未対応機能、4=I/O・drift・上書き保護。全artifactを事前検査し、手書きファイルやsymlink（出力先・祖先・各file）を上書きしない。各fileは一時ファイルから置換するが、ディレクトリ全体のtransactionや敵対的な同時ファイル差し替えへのsandboxではない。排他的に管理できる出力先を使う。

### Java frontendを使う既存経路

こちらの経路にはJava 21、Maven 3.9系が必要。Java版の既存CLIは変更せず、`generate`サブコマンドを追加した。

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

runtimeは規則IDで参照する文法を実行し、入力とCST node arenaを解析結果が所有する。ASTは`enum`、単一の子は`Box<Ast>`、単一text captureは`String`。optionalは`Option`、複数captureは`Vec`になる（下表）。各variantは半開区間`Span { start, end }`を持つ。内部カーソルはUTF-8 byte、公開位置はUnicodeコードポイントで、UTF-16/LSP座標とは異なる。mapper後のASTは文字列と位置を所有し、CSTを破棄しても評価できる。静的なソースマップは使わない。

`Semantics`の各メソッドは既定実装を持たず、`evaluate`はwildcardなしの`match`。新しいvariantと古いdispatchを組み合わせると`E0004`、新しいtraitと古いimplでは`E0046`になる。ただし再コンパイルされるソース間の構造チェックであり、意味処理の正しさは保証しない。文字列として保持する演算子の追加は型を変えないため、この保護を受けない。

### 対応するUBNF

- 1ファイル・1文法、ちょうど1つの`@root`。
- 非空の文字列terminal、rule参照、sequence、ordered choice、group、optional、0/1回以上のrepeat、bounded repeat、separated list。
- `NumberParser`またはその完全修飾名へのtoken binding。符号・小数・指数を含む。
- `IdentifierParser`、`SingleQuotedParser`、`DoubleQuotedParser`、`EndOfSourceParser`の短名または既定packageの完全修飾名。字句とmapperの契約は下記参照。
- `org.unlaxer.tinyexpression.parser.StringLiteralParser`の完全修飾名。DoubleQuoted→SingleQuotedの順で認識し、短名や別packageは拒否する。[実tinyexpressionクラスとの共通テストと字句契約](../docs/tiny-string-token.md)を参照。文字列の評価・escapeデコードは含まない。
- `org.unlaxer.tinyexpression.parser.javalang.CodeStartParser` / `CodeEndParser`の完全修飾名。内部triviaなしの原子的なcode fenceとして行頭・行末条件を保持する。[実クラスとの比較・字句契約](../docs/tiny-code-fence.md)を参照。javacodeblock/rustcodeblockの実行機能ではない。
- `ANY`・`EOF`・`EMPTY`・`CHAR_RANGE`・`NEGATION`・`UNTIL`・`LOOKAHEAD`・`NEGATIVE_LOOKAHEAD`。詳細は下記のprimitive互換契約を参照。
- `@mapping`とterminal/rule/group/quantifierへのcapture。全capture名の集合とparamsが一致すること。欠ける選択肢はoptional、繰り返しや同名の複数出現はlistとして推論する。text/node混在値は下記の`AstValue`で保持する。
- 同じfield名・順序・cardinalityを持つ複数ruleのshared mapping。text/node種別が異なるfieldは宣言順に依存せず`AstValue`へ統合する。AST variantとSemantics methodは一つに統合し、ruleごとのcaptureとspanは保持する。
- `@leftAssoc`の`Left @left { Op @op Right @right }`形と`params=[left, op, right]`、`@precedence(level=N)`。詳細は下記参照。
- `@rightAssoc`の`Base @left { Op @op Self @right }`形（`Self`は宣言rule自身への直接参照）。同じparamsとprecedenceを用い、右辺を再帰的に生成する。
- `@whitespace: javaStyle`（ASCII空白、行/ブロックコメント）または`none`。未指定は`none`。rule の `@whitespace` / `@whitespace(javaStyle)` / `@whitespace(none)` と `@interleave(profile=javaStyle|commentsAndSpaces)` による局所設定も生成する。[優先順位・Javaとの共通契約](../docs/rule-trivia.md)を参照。`@package`はRustでは使用しない。

imports、上記以外の外部token parser、`@typeof`、`@eval`等の他のannotation、左再帰などは明示的に拒否する。mapping名・field名はASCII識別子に制限し、Rustのraw identifierで出力する。`self`/`Self`/`super`/`crate`、fieldの`span`/`semantics`、shared mappingのschema不一致・異なるmappingからの生成method名衝突は拒否する。rootはちょうど1つのAST nodeへ解決される必要がある。optionalの先にある参照も左再帰検査に含め、空一致の可能性がある無限反復は生成前に拒否する。

### 演算子の列と優先順位

`@leftAssoc`はJavaと同様、単一leftと`Vec`のop/rightを生成する。自動の二分木化や評価ではなく、手書きSemanticsが左からfoldする契約である。左右のoperandは同じtext/node種別で各出現が単一値、opはtextでなければならない。同名variantを使う加減算・乗除算の規則でも、enumとtraitの重複定義は生じない。

`@rightAssoc`はcanonical形を`Base Op Self | Base`へ変換する。leftは単一text/node、op/rightは従来の`Vec`型を維持するが各ノードに0または1件だけ格納し、右側を再帰的なASTにする。`2^3^2`は`2^(3^2)`となり、評価器はこの構造をそのまま評価する。別ruleへの右参照・groupで包んだSelf・optionalなbase・入れ子captureなど非canonical形は明示拒否する。leftとrightの型を同一に強制せず、textのbaseとnodeの右辺も保持する。

`parser::OPERATORS`はrule名、`i32`のprecedence、`Associativity::{Left,Right,None}`を保持する生成metadataで、level→rule名の順に並ぶ。Rightは使用する文法だけに出力し、既存文法の生成物は変えない。構文の優先順位はruleの参照階層で定義する。CLIはassoc/precedenceの併記・非負level・operand側が高優先であることを要求し、不整合な文法を生成前に拒否する。Java CLIは共通検証のERRORを拒否し、Javaクラス解決のWARNINGはRust token対応の根拠にしない。token対応は両経路ともRust側の明示allowlistで検証する。

低水準の`RustBackend.generate`を直接呼ぶ場合は、Java各generatorと同様、共通validatorの呼出しは利用者側の責任である。この直接APIでは結合性だけのlevelは`-1`、precedenceだけの結合性は`None`として記録する。比較テストのmetadata逆転モードはvalidatorを意図的に迂回した性質検査で、数値自体が構文を組み替えないことを確認する。逆転した文法がCLIで受理されるという意味ではない。

[`associative/Operators.ubnf`](../unlaxer-dsl/src/test/resources/associative/Operators.ubnf)とcorpusは共有Binary variant・明示的な数値leaf・括弧・非可換演算・コメント中のUnicodeを検証する。これはtinyexpression全体ではなく、数値意味論もtinyexpressionのf32仕様ではない。text/node混在factorは生成可能だが、Java associative mapperの特殊な`Binary(null, [literal], [])`表現とRustの`AstValue::Text`は同一構造ではない。この特殊leafを含むtinyexpression全体の互換性は未完了である。

24入力×metadata 2設定の48ケースで受理・両cursorをJavaと比較し、受理28ケースの全AST field/spanを照合する。Rustの評価値は別途corpusの期待値と比較し、共有variantのSemantics未実装は実`rustc`の`E0046`で検出する。結果は`target/rust-associative.tsv`とCI artifactへ保存する。これにより見つかったJavaの未縮約CST上のassoc反復欠落は[issue #138](https://github.com/opaopa6969/unlaxer-parser/issues/138)で修正した。

右結合は[`right-associative/Power.ubnf`](../unlaxer-dsl/src/test/resources/right-associative/Power.ubnf)をtext・mapped leaf・非BMP marker・括弧の4形で検証する。28入力×4形の112ケースでJava/Rustの受理・両cursor、受理49ケースの全AST field/spanを比較し、独立した期待値で`2^3^2=512`と`(2^3)^2=64`を区別する。生成Semantics未実装の`E0046`とCST破棄後の評価も検証し、結果は`target/rust-right-associative.tsv`へ保存する。ここでの累乗`^`はfixture専用で、tinyexpressionの`^`（boolean XOR）を変更するものではない。fixtureの数値評価はf64であり、source captureに残るコメントを除く処理はテスト用Semanticsの責任である。Javaの括弧付き右再帰AST/mapper型不一致は[issue #145](https://github.com/opaopa6969/unlaxer-parser/issues/145)で修正した。

| captureの個数 | AST text / node | Semantics引数 text / node |
|---|---|---|
| 1個 | `String` / `Box<Ast>` | `&str` / `&Ast` |
| 0/1個 | `Option<String>` / `Option<Box<Ast>>` | `Option<&str>` / `Option<&Ast>` |
| 複数 | `Vec<String>` / `Vec<Ast>` | `&[String]` / `&[Ast]` |

### textとAST nodeの混在値

`Factor ::= 'a' | Leaf`のような選択肢は、文字列を失わず`AstValue::Text { text: String, span: Span }`または`AstValue::Node(Box<Ast>)`として保持する。scalar・optional・listはそれぞれ`AstValue`・`Option<AstValue>`・`Vec<AstValue>`、Semantics引数は`&AstValue`・`Option<&AstValue>`・`&[AstValue]`になる。Java側では同じ混在fieldを`Object`・`Optional<Object>`・`List<Object>`として生成し、mapped nodeを文字列化しない。混在しない既存Rust文法の出力は変更しない。

lowererはtext選択肢の境界を明示的な`Expr::TextValue`として生成する。runtimeは元の子node/captureを残して`TEXT_VALUE_RULE`（`usize::MAX`）のCST nodeで包み、mapperがその境界を読んで順序を保つ。scalar/optionalの混在captureには`Expr::ValueBoundary` / `VALUE_BOUNDARY_RULE`（`usize::MAX - 1`）も用い、textなら捕捉全体の括弧・triviaを保持し、nodeなら内側ASTを保持する。空の子からtextを捏造しない。いずれのmarkerも通常ruleの配列indexではない。`ParseContext`の共有状態・transaction・両cursorの契約は変更しない。

[`mixed-values`](../unlaxer-dsl/src/test/resources/mixed-values/)ではJava/Rustの受理・消費/照合cursor・AST field/node span、独立した評価期待値を比較する。Rustのtext spanは別に原文のUnicodeコードポイント位置で検証する。`AstValue::canonical_json()`はJavaのStringとの比較用にtextをJSON文字列へ投影するため、そのJSONにはtext spanを含めない。Rust AST自体はspanを所有し、CST/contextを破棄しても使える。結果は`target/rust-mixed-values.tsv`・`rust-delimited-mixed-values.tsv`・`rust-shared-mixed-values.tsv`に保存する。

`Pair ::= Leaf Leaf; Root ::= Pair @values;`のような**単一capture内の並列semantic値**も、JavaのList・RustのVecとして元の順序ですべて保持する（[#160](https://github.com/opaopa6969/unlaxer-parser/issues/160)）。helper内部のgroup/alias/optional/repeat/separatedを解析して、構文上のcapture回数だけでなくsemantic値の個数を型へ反映する。mapped nodeで収集を止め、そのnode内部を親のfieldへ平坦化しない。区切り文字は値に含めず、各scalar Text itemの括弧とtriviaは保持する。Many helper全体を一つのTextへ結合しない。

`Outer ::= '(' [Factor] ')'; Root ::= Outer @value;`でhelper内部のoptionalをcaptureした場合、入力`()`はJavaの`Optional.empty()`・Rustの`None`を表す。値がないことと空文字列の値を区別し、括弧だけからTextを捏造しない。従来Javaが返したscalar Objectや最後のnodeだけへの依存はAPI変更になるため、parser・AST・mapper・evaluatorを一緒に再生成し、利用側のList/Optional処理を更新する。Java生成parserの専用`__CaptureBinding` metadataでtext/item境界を保持し、共有Parser instanceを変更しない。

[`semantic-cardinality`](../unlaxer-dsl/src/test/resources/semantic-cardinality/)の共通corpusでは、Java単体・Rust単体・両言語比較を独立に実行する。型、値の順序、両cursor、全AST node span、Textの独立spanと評価結果を検証し、同値のleafが連続する場合も別々の位置を保持する。native/Java frontendのRust生成fileも照合する。結果は`target/semantic-cardinality-{java,rust,both}.tsv`に保存する。既存のassoc用型契約はこの一般化では変更しない。

純粋なmapped aliasもJava/Rustの双方でNodeを保持する（[#163](https://github.com/opaopa6969/unlaxer-parser/issues/163)）。Javaの旧`String` APIからの変更であり、transparent fieldは`Object`系となる。Rustの`Box<Ast>`と型名を揃えたものではなく、Node/Textの種別・cardinality・値・位置の契約を共通corpusで照合する。tinyexpressionの`SliceStartIndex ::= NumberExpression`のように字句文字列が必要な利用者は、Nodeを評価・文字列化せず所有source mapから取り出す。[移行手順と保証範囲](../docs/pure-mapped-alias-migration.md)を参照。

この移行に先立ちJavaには、preferred型で選んだTokenとimmutableな位置snapshotを
一度のmappingから返す`selectParsedTokenWithSourceMap`を追加した（[#165](https://github.com/opaopa6969/unlaxer-parser/issues/165)）。
Rustの生成ASTはもともとspanを所有するため、別parseや別threadのmappingで位置が
上書きされるglobal source mapを持たない。両言語で後続・並行mappingとUnicode位置の
保持を検証する。これはJavaのpreferred型探索そのものがRustへ移植済みという意味ではない。

`[ Item ] @head`はoptionalの中へcaptureを置き、`{ Item } @items`、`Item+ @items`、`Item{1,2} @items`、`Item % ',' @items`は各要素をcaptureする。区切り文字はitemsに入れない。量指定子の内側に置いた`{ Item @items }`も扱う。同名captureの履歴は平坦な列で、順序を保つ。透明なunmapped ruleが複数のmapped nodeを包む場合も、mapperはそのnode列を収集する。

`[[ Item ]] @head`など入れ子container全体のcaptureは、`Option<Option<_>>`を失わないよう現時点では明示拒否する。内側の要素に名前を付けるか、各階層をmapped ruleに分ける。再帰的なunmapped ruleの型推論、入れ子container型、全Java capture規則との互換性は今後の作業。scalarからoptionalに変わると手書きSemanticsも型変更が必要になり、古い引数型は`E0053`で検出される。

RustのLSP/DAP、回復・incremental cache、PropagationStopper、Java MatchedTokenParserとの完全互換、tinyexpressionのstring/variable/function-call意味論、proc macroは未実装。Rust製UBNF frontendの構文対応とbackend生成対応は上記のとおり区別する。性能最適化・Java比の速度優位も未評価。文法構造はparseごとに構築し、runtimeのrule呼出深さには256の上限がある。大規模・敵対的入力の資源量保証はない。

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

呼出側は`context.parse(&parser)`を使う。これにより`Err`時には消費・マッチ両カーソル・追加CST node・capture履歴・利用者状態を戻す。独自実装の`parser.parse(&mut context)`を直接呼ぶ場合、この外側のtransactionは付かない。最遠失敗の位置・候補はrollback後も保持する。PEG型の`ahead`は成功時も状態を戻し、`not_ahead`の内部失敗候補は外へ漏らさない。UBNFの先読みは異なる契約を持つ（下記）。

`source`/`remaining`/`position`/`advance`/`text`で入力を扱い、`set_state`/`state::<T>`/`state_mut::<T>`で`Any + Clone`の値を共有する。位置・移動量はコードポイント単位。利用者状態の`Clone`は変更可能な内容を独立コピーする必要がある。`Rc<RefCell<_>>`等の共有内部状態、ファイル操作等の副作用、panic unwindはrollback対象ではない。panicを捕捉した後はcontextを再利用しない。

`Expr`には`then`/`or`/`optional`/`repeat`/`zero_or_more`/`one_or_more`/`separated_by`/`capture`/`ahead`/`not_ahead`を用意する。`separated_by`は1個以上。genericな無限反復の子が入力を消費せず成功した場合はエラーとする。`Custom(fn)`でcontextを取る関数を組み込める。`Any`/`CharRange`/`Except`は1コードポイント、`Until`は終端の直前まで（終端がなければ失敗）、`Eof`/`Empty`/`Error`も利用できる。UBNFではJava互換用の`JavaOptional`/`JavaRepeat`等も生成する。Custom・BackreferenceのUBNF接続は未実装。

`captured(name)`と`Backreference(name)`はcontext全体の同名captureの最新成功分を使用する。`capture_spans`は成功履歴を返す。字句スコープやJavaのcapture伝播規則を再現したものではない。

字句スコープの宣言・参照・semantic diagnostics は別の `scopes()` / `scopes_mut()` で扱う。
`with_scope` は子スコープを開き、成功後は名前を隠してイベント履歴を保持し、失敗時は
開始前へ戻す。scope store も parser の rollback 対象で、context ごとに独立する。
[transactional scope の契約](../docs/transactional-scopes.md)を参照。
`@scopeTree` / `@declares` / scope付き文法の`@backref`も両frontendから生成する。
`RuleEffects`がmode・description・対象capture名を保持し、`Tree.scopes()`でowned snapshotを得られる。
両modeは解析時stackであり、評価時のdynamic環境を意味しない。
[生成スコープの契約とJava移行](../docs/generated-scope-effects.md)を参照。

同名captureのAST fieldも完了順に全出現を保持する。共存する内外/並列captureはlist、
排他的choiceはscalar、欠損する枝はoptionalになる。Java側の型変更と再生成手順は
[同名captureの移行](../docs/nested-capture-migration.md)を参照。

生成器は`rules()`・`parse_context(&mut ParseContext)`も出力する。context入口は現在位置からの**prefix解析**であり、全入力検証は従来の`parse_tree[_detailed]`、または後続の`Expr::Eof`を使う。文法とtrivia設定は呼出中だけ切り替わり、入力と利用者状態は共通。`matched.root_node()`から`context.tree(root)`で所有されたsnapshotを取り、その文法のmapperへ渡す。異なる文法のrule IDはローカルなので、複数文法のnodeを一つのmapperに混ぜない。node IDはcontext内だけで有効で、rollbackされた結果は再利用しない。

公開APIの使用例とrollback契約は[`context_combinators.rs`](unlaxer-runtime/tests/context_combinators.rs)、scope store は[`scopes.rs`](unlaxer-runtime/tests/scopes.rs)、生成parserとの混在とAST評価は[`context.rs`](examples/evolution/tests/context.rs)で検証する。現在はtransactionごとに利用者状態・capture履歴・scope storeをコピーする単純実装で、性能評価・最適化は未実施。

### UBNF primitiveの互換契約

Javaの`ParseContext`は消費位置とマッチ専用位置を分ける。Rustも`position()`と`matched_position()`で両方を公開し、失敗transactionは両方を復元する。UBNF互換動作を通常のPEG combinatorから分離するため、次の生成先を用意した。

| UBNF | Rust生成先 | 動作 |
|---|---|---|
| `ANY` / `CHAR_RANGE` / `NEGATION` | `Any` / `CharRange` / `Except` | 1コードポイントを消費し、マッチ位置を消費位置へ同期 |
| `EOF` | `Eof` | 消費位置が末尾のとき成功 |
| `EMPTY` | `JavaEmpty` | 常に成功。マッチ位置に文字があれば1コードポイント進めるが消費しない |
| `LOOKAHEAD` / `NEGATIVE_LOOKAHEAD` | `JavaLookahead` | マッチ位置から検査。肯定成功ではマッチ位置だけ進む。空patternは肯定失敗・否定成功 |
| `UNTIL` | `JavaUntil` | 終端は非消費だがマッチ位置を進める。終端欠損または空終端なら末尾まで消費して成功 |

例えば`LOOKAHEAD('a')`の後に`LOOKAHEAD('b')`を置くと、`ab`を消費せず順にマッチする。`Expr::literal("a").ahead()`を2回行うPEGの検査とは異なる。既存の`ahead`/`not_ahead`/`Until`/`Empty`の契約は変更していない。UBNFのマッチ専用tokenをcaptureした文字列は**消費した部分**なので空文字であり、先読みしたpattern本体ではない。

UBNFのoptional/repeatは`optional_java`/`repeat_java`を生成する。Java `Occurs`と同じく、有限repeatも消費が進まない成功を1回数えた時点で停止する。上限0でも子を1回試し、成功すれば個数超過で失敗するJavaの挙動も保持する。直接の消費atomが失敗した場合、optionalやrepeat内ではマッチ位置だけが消費位置へ戻る。一方、rule/sequence/captureで包んだ子はtransactionで元の位置へ戻る。これらをgenericな`optional`/`repeat`に混入させない。空消費が可能な無限repeatは引き続きUBNF生成前に拒否する。

`CHAR_RANGE`境界は現在のUBNF ASTの`char`型に合わせて単一の非surrogate BMP文字に限定し、空・複数文字・補助文字・逆順は明示拒否する。`ANY`/`NEGATION`と文字列patternは補助文字もコードポイントとして扱う。Rustへ渡す文字列の不正な単独surrogateは生成前に拒否する。

[`primitives/corpus.json`](../unlaxer-dsl/src/test/resources/primitives/corpus.json)の48文法から両backendを実生成・コンパイルして、109入力のprefix受理/消費位置/マッチ位置と全入力受理を比較する。受理56入力ではAST field/spanも独立fixtureと一致する。空・終端欠損・Unicode・javaStyle trivia・直接/間接capture・量指定子・rollbackを含み、結果は`target/rust-primitives.tsv`とCI artifactへ保存する。選択肢とliteralの反復でもJavaのhelper chain相当の空白処理境界を保持する。IRの`Delimited`は補助境界をcaptureの外へ置き、元の文法のgroupとは区別する。`(T | 'z')+`と`((T | 'z'))+`でコメントのcapture範囲が異なることもJavaと照合する。これは現token群の有限corpusであり、汎用MatchOnly/Not/PropagationStopper・virtual token・全CST形状の完全互換を意味しない。診断候補の文言もbackend固有のままである。

Java生成Mapperも、空のcapture-siteを除去する汎用reducerを通さず元のCSTを読むようにした。成功した零幅captureは欠損ではなく空文字になる。`mapParsedTokenWithSourceMap`へ渡す側も零幅captureを保持する必要がある場合は`parsed.getRootToken(false)`を使う。呼出前にreducerで失った情報は復元できない。

### Identifier・引用文字列の互換契約

| Java token binding | Rust Expr | 字句契約 |
|---|---|---|
| `IdentifierParser` / `org.unlaxer.parser.clang.IdentifierParser` | `Identifier` | ASCIIの`[A-Za-z_][A-Za-z0-9_]*`。キーワード除外なし |
| `SingleQuotedParser` / `org.unlaxer.parser.elementary.SingleQuotedParser` | `Quoted('\'')` | 単引用符で囲む。backslashと次の任意1コードポイントを優先して読む |
| `DoubleQuotedParser` / `org.unlaxer.parser.elementary.DoubleQuotedParser` | `Quoted('"')` | 二重引用符で囲む。それ以外は単引用符版と同じ規則 |
| `EndOfSourceParser` / `org.unlaxer.parser.elementary.EndOfSourceParser` | `Eof` | 消費位置が末尾なら成功する零幅token |

引用文字列は生改行・NUL・補助文字を受理し、`\q`や`\u0041`も字句として受理する。JSONやJavaのescape whitelistではなく、escapeのデコードもしない。runtimeの`Quoted`は単引用符と二重引用符に限定し、その他のdelimiterは明示エラー。`Identifier`/`Quoted`失敗時は開始時の両cursorを復元し、成功時は消費末尾へ両方を同期する。EOFのaliasもnullable検査対象。

**生captureとASTのtext fieldは別契約**。CST・`context.captured`は引用符とbackslashをそのまま保持する。Java生成mapperとの互換用`java_capture_text`は`String.strip`相当の後に外側の単引用符だけを除く。二重引用符とescapeは保持し、単引用符の内側の空白は再trimしない。既存の`strip_capture`（空白だけ除去）は変更しない。AST node spanは変換前のソース範囲を保持する。新しいmapperはこのruntime APIが必要なので、runtime更新と再生成を合わせて行う。

[`lexical/corpus.json`](../unlaxer-dsl/src/test/resources/lexical/corpus.json)は22文法・78入力をJava/Rustで実生成・コンパイルし、prefix受理/両cursorと全入力受理を比較する。受理48入力のAST field/spanも独立fixtureと照合する。プローブへの入力はUTF-8をhexでframe化し、生改行とNULを無改変で運ぶ。結果は`target/rust-lexical.tsv`とCI artifactへ保存する。direct token repeatにはJavaの空白処理境界が付かない場合があるため、`T+`と`(T | 'none')+`を区別して検査する。Identifierのgroup captureと非Identifier側のchoice、zero-field nodeも含み、先頭の識別子だけを拾うJava mapper不具合[欠陥#132](https://github.com/opaopa6969/unlaxer-parser/issues/132)とzero-field生成[欠陥#129](https://github.com/opaopa6969/unlaxer-parser/issues/129)の再発を防ぐ。

tinyexpression基準revisionの`StringLiteralParser`はDoubleQuoted/SingleQuotedのchoiceなので、その字句部分はこれらを組み合わせて記述できる。ただし、tinyexpression固有クラス名へのbinding、評価層のquote正規化、legacy Java出力でのjavacによるescape解釈は別の未完了事項。任意の外部parserをクラス名の末尾だけで近似せず、上表以外は明示拒否する。

## 再現実験と結果

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=RustBackendTest,RustConformanceTest,NumericCaptureRuntimeTest -DrustConformance=true \
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

## optional/repeatの追加実験

[`cardinality/`](../unlaxer-dsl/src/test/resources/cardinality/)に共通文法・14入力・期待AST・Rust Semanticsを固定した。zero/plus/bounded/unbounded/separatedの5文法×14入力＝70ケースをJava/Rustで実生成・コンパイル・実行する。受理／拒否は70件で一致し、受理20件では両backendのAST全field・全node spanが独立した期待fixtureと一致する。Rustの評価値も独立oracleで検査する。この限定corpusの一致であり、全Java文法への互換性を主張するものではない。

差分実験で見つかったJava mapperのcapture選択不具合[issue #116](https://github.com/opaopa6969/unlaxer-parser/issues/116)は、生成parserの文法位置bindingで修正した。欠損optionalがrepeat側のItemを奪うことや、literalの代わりに区切りを拾うことを防ぎ、plus/bounded/separatedも各値の出現順に対応する。旧`javaKnown`不具合snapshotを除き、正しい期待ASTとの一致とJava/Rust相互一致を検査する。双方の結果は`target/rust-cardinality.tsv`とCI artifactに保存する。

Javaではparserとmapperを同じgenerator revisionで**一緒に再生成**する必要がある。新mapperは新parserの`__CaptureBinding` metadataを必要とし、旧parserとの混在は非互換（コンパイルエラー）になる。旧parser＋旧mapperの組はそのまま利用できるが、旧capture不具合も残る。追加wrapperは専用instanceであり`Parser.get`の共有instanceは変更しない。scope付き文法の宣言/参照listenerも位置bindingを使い、別ルール内部へは入らない（#176）。scopeなしbackrefの旧経路は変更しない。入れ子量指定子の内側に置いたcaptureは検証済みだが、`[ { Item } ] @values`のような外側captureの`Optional<List<_>>`再構築はJava/Rust双方の未完了項目で、今回の位置binding修正とは別である。

数値は既存evolutionと同じ`Digits ::= NUMBER`のtext用rule経由でcaptureする。[issue #115](https://github.com/opaopa6969/unlaxer-parser/issues/115)のJava直接captureの不正なprimitive初期化とgeneric型は修正した。scalarの既存`int` APIを維持し、optional/listは`Integer`へboxingする。Java mapperは`Integer.parseInt`により小数・指数・overflowを明示的に拒否し、Rust mapperは字句を`String`として保持する。この型・変換契約はまだ同値ではない。

別の[数値境界corpus](../unlaxer-dsl/src/test/resources/numeric-capture/conformance.json)は13入力を両backendで実生成・コンパイル・実行する。Javaは符号付き整数・範囲境界・先頭ゼロの6入力を`int`へ変換し、小数/指数/overflowの7入力で`NumberFormatException`、Rustは13入力すべての字句を保持する。`NumericCaptureRuntimeTest`はoptional/repeatのcapture内外、短い名前/FQN、DigitParser、欠損必須captureの明示エラーも検査する。構文成功をAST変換成功と取り違えない。

追加13ケースはRustの実生成・コンパイル・実行で、量指定子内capture、欠ける選択肢、同名captureの複数出現、透明なoptional/list rule、group capture、0回/厳密回数を検証する。2ケースでは古いSemantics引数型をコンパイルして`E0053`を確認する。これらはJUnit内のケース数で独立テストメソッド数ではない。UBNF frontendの入れ子量指定子が脱落する不具合も修正し、外側のrepeat/optional/groupと内側のsuffixを区別する回帰テストを追加した。

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
